package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.resolution.ClassHierarchy
import java.util.*

class FrameAnalyzer(private val hierarchy: ClassHierarchy? = null) {
    fun analyze(ownerInternalName: String, method: RawMethod, graph: ControlFlowGraph): FrameAnalysis {
        val code = requireNotNull(method.code) { "Method ${method.name}${method.descriptor} has no code." }
        val entryBlock = graph.entryBlock ?: return FrameAnalysis(emptyMap(), emptyMap(), 0, 0)
        val consumer = "$ownerInternalName.${method.name}${method.descriptor}"
        val maxLocals = requireNotNull(code.maxLocals) { "Missing maxLocals for ${method.name}${method.descriptor}." }
        val labelPositions = code.labels.associate { it.id to it.instructionIndex }
        val handlers = code.exceptionHandlers.map { handler ->
            ResolvedHandler(
                start = requireNotNull(labelPositions[handler.tryStart]) { "Unknown try-start label ${handler.tryStart.value}." },
                endExclusive = requireNotNull(labelPositions[handler.tryEnd]) { "Unknown try-end label ${handler.tryEnd.value}." },
                handlerBlock = blockForInstruction(graph, requireNotNull(labelPositions[handler.handler]) {
                    "Unknown handler label ${handler.handler.value}."
                }),
                raw = handler,
            )
        }

        // JSR/RET subroutines are context-sensitive: the same subroutine body may be entered from
        // several JSR sites while caller locals contain different values. Merging those callers at
        // the subroutine entry destroys locals that must be preserved across the finally body.
        // Keep an independent data-flow state for every active legacy-subroutine call stack.
        val rootLocation = FrameLocation(entryBlock, LegacySubroutineContext.EMPTY)
        val contextualEntryFrames = mutableMapOf<FrameLocation, FrameState>()
        val contextualExitFrames = mutableMapOf<FrameLocation, FrameState>()
        val queue = ArrayDeque<FrameLocation>()
        val queued = mutableSetOf<FrameLocation>()
        val counters = MergeCounters()

        contextualEntryFrames[rootLocation] = initialFrame(ownerInternalName, method, maxLocals)
        queue.addLast(rootLocation)
        queued += rootLocation

        fun schedule(location: FrameLocation) {
            if (queued.add(location)) queue.addLast(location)
        }

        fun mergeInto(location: FrameLocation, incoming: FrameState, context: String) {
            val current = contextualEntryFrames[location]
            if (current == null) {
                contextualEntryFrames[location] = incoming
                schedule(location)
                return
            }
            val merged = mergeFrames(current, incoming, counters, context, consumer)
            if (merged != current) {
                contextualEntryFrames[location] = merged
                schedule(location)
            }
        }

        fun propagateOrdinarySuccessors(location: FrameLocation, output: FrameState) {
            graph.edges.asSequence().filter { it.from == location.block && it.kind != ControlFlowEdgeKind.EXCEPTION }.forEach { edge ->
                    mergeInto(
                        FrameLocation(edge.to, location.context),
                        output,
                        "edge ${edge.from.value}->${edge.to.value}",
                    )
                }
        }

        while (queue.isNotEmpty()) {
            val location = queue.removeFirst()
            queued.remove(location)
            val blockId = location.block
            val block = graph.block(blockId)
            val input = requireNotNull(contextualEntryFrames[location])
            val mutable = MutableFrame(input.locals.toMutableList(), input.stack.toMutableList())

            for (instructionIndex in block.startInstructionIndex until block.endInstructionIndexExclusive) { // Any instruction inside a protected range is conservatively treated as a potential throw site.
                // An exception raised inside a JSR subroutine stays in the same subroutine invocation context.
                handlers.asSequence().filter { instructionIndex >= it.start && instructionIndex < it.endExclusive }.forEach { handler ->
                        val exceptionFrame = FrameState(
                            locals = mutable.locals.toList(),
                            stack = listOf(
                                FrameValue.reference(
                                    JvmReferenceType.Exact(
                                        JvmType.ObjectType(handler.raw.catchType ?: "java/lang/Throwable"),
                                    ),
                                    ValueOrigin.ExceptionHandler(
                                        handlerInstructionIndex = graph.block(handler.handlerBlock).startInstructionIndex,
                                        catchType = handler.raw.catchType,
                                    ),
                                ),
                            ),
                        )
                        mergeInto(
                            FrameLocation(handler.handlerBlock, location.context),
                            exceptionFrame,
                            "exception handler for instruction $instructionIndex",
                        )
                    }

                execute(code.instructions[instructionIndex], instructionIndex, mutable)
            }

            val output = mutable.freeze()
            contextualExitFrames[location] = output
            when (val terminator = code.instructions[block.endInstructionIndexExclusive - 1]) {
                is RawRetInstruction -> {
                    val returnAddress = mutable.requireLocal(terminator.slot, FrameValueKind.RETURN_ADDRESS)
                    val expectedReturnSite = location.context.currentReturnSite ?: throw StackInconsistencyException(
                        "RET in block ${block.id.value} executes outside a JSR subroutine context.",
                    )
                    val returnSites = returnAddress.origins.filterIsInstance<ValueOrigin.ReturnAddress>().mapTo(linkedSetOf()) { it.returnInstructionIndex }
                    if (expectedReturnSite !in returnSites) {
                        throw StackInconsistencyException(
                            "RET in block ${block.id.value} uses return-address local ${terminator.slot} for " + "$returnSites, expected $expectedReturnSite.",
                        )
                    }
                    val returnBlock = blockForInstruction(graph, expectedReturnSite)
                    mergeInto(
                        FrameLocation(returnBlock, location.context.returnFromSubroutine()),
                        output,
                        "legacy RET from block ${block.id.value} to instruction $expectedReturnSite",
                    )
                }

                is RawBranchInstruction -> {
                    if (terminator.opcode.mnemonic in LEGACY_JSR_OPCODES) {
                        val returnSite = block.endInstructionIndexExclusive
                        require(returnSite in code.instructions.indices) {
                            "JSR at the end of method has no return site."
                        }
                        val subroutineContext = location.context.enterSubroutine(returnSite) // The CFG keeps the post-JSR block reachable for structural diagnostics, but execution
                        // reaches it only through RET. Enter only the actual subroutine target here.
                        graph.edges.asSequence().filter { it.from == blockId && it.kind == ControlFlowEdgeKind.JUMP }.forEach { edge ->
                                mergeInto(
                                    FrameLocation(edge.to, subroutineContext),
                                    output,
                                    "legacy JSR edge ${edge.from.value}->${edge.to.value}",
                                )
                            }
                    } else {
                        propagateOrdinarySuccessors(location, output)
                    }
                }

                else -> propagateOrdinarySuccessors(location, output)
            }
        }

        return FrameAnalysis(
            // For ordinary bytecode every location is in the root context. Legacy JSR subroutine
            // bodies can have several valid caller-specific frames and therefore cannot be losslessly
            // represented by Map<BasicBlockId, FrameState>; expose the root-context view here.
            entryFrames = rootContextFrames(contextualEntryFrames),
            exitFrames = rootContextFrames(contextualExitFrames),
            frameMergeCount = counters.frameMerges,
            valueMergeCount = counters.valueMerges,
            referenceMergeCount = counters.referenceMerges,
            impreciseReferenceMergeCount = counters.impreciseReferenceMerges,
        )
    }

    private fun initialFrame(ownerInternalName: String, method: RawMethod, maxLocals: Int): FrameState {
        val locals = MutableList<FrameValue?>(maxLocals) { null }
        var slot = 0
        if (method.accessFlags and ACC_STATIC == 0) {
            require(slot < maxLocals) { "Instance method has no local slot for this." }
            locals[slot++] = FrameValue.reference(
                JvmReferenceType.Exact(JvmType.ObjectType(ownerInternalName)),
                ValueOrigin.This(ownerInternalName),
            )
        }
        method.type.parameterTypes.forEachIndexed { parameterIndex, type ->
            val kind = type.toFrameValueKind()
            require(slot < maxLocals) { "Parameter $parameterIndex does not fit in maxLocals=$maxLocals." }
            locals[slot] = FrameValue.of(type, ValueOrigin.Parameter(parameterIndex))
            if (kind.category == 2) {
                require(slot + 1 < maxLocals) { "Wide parameter $parameterIndex does not fit in maxLocals=$maxLocals." }
                locals[slot + 1] = null
            }
            slot += kind.category
        }
        return FrameState(locals, emptyList())
    }

    private fun execute(instruction: RawInstruction, index: Int, frame: MutableFrame) {
        when (instruction) {
            is RawConstantInstruction -> frame.push(
                FrameValue.of(instruction.toValueType(), ValueOrigin.Instruction(index)),
            )

            is RawLocalInstruction -> executeLocal(instruction, frame)
            is RawIncrementInstruction -> {
                frame.requireLocal(instruction.slot, FrameValueKind.INT)
                frame.writeLocal(instruction.slot, value(FrameValueKind.INT, index))
            }

            is RawArrayInstruction -> executeArray(instruction, index, frame)
            is RawOperatorInstruction -> executeOperator(instruction, index, frame)
            is RawConversionInstruction -> {
                frame.pop(instruction.fromType.toFrameValueKind())
                frame.push(value(instruction.toType, index))
            }

            is RawStackInstruction -> executeStack(instruction.opcode.mnemonic, frame)
            is RawBranchInstruction -> executeBranch(instruction.opcode.mnemonic, index, frame)
            is RawSwitchInstruction -> frame.pop(FrameValueKind.INT)
            is RawFieldInstruction -> executeField(instruction, index, frame)
            is RawInvokeInstruction -> executeInvoke(instruction, index, frame)
            is RawInvokeDynamicInstruction -> {
                popArguments(instruction.type.parameterTypes, frame)
                pushReturn(instruction.type.returnType, index, frame)
            }

            is RawNewObjectInstruction -> frame.push(
                referenceValue(JvmReferenceType.Exact(JvmType.ObjectType(instruction.internalName)), index),
            )

            is RawNewArrayInstruction -> {
                frame.pop(FrameValueKind.INT)
                frame.push(referenceValue(JvmReferenceType.Exact(JvmType.ArrayType(instruction.componentType)), index))
            }

            is RawNewMultiArrayInstruction -> {
                repeat(instruction.dimensions) { frame.pop(FrameValueKind.INT) }
                frame.push(referenceValue(JvmReferenceType.Exact(instruction.arrayType), index))
            }

            is RawTypeCheckInstruction -> when (instruction.opcode.mnemonic) {
                "checkcast" -> {
                    frame.pop(FrameValueKind.REFERENCE)
                    frame.push(referenceValue(JvmReferenceType.Exact(instruction.type), index))
                }

                "instanceof" -> {
                    frame.pop(FrameValueKind.REFERENCE)
                    frame.push(value(FrameValueKind.INT, index))
                }

                else -> unsupported(instruction, index)
            }

            is RawReturnInstruction -> if (instruction.type != JvmComputationalType.VOID) {
                frame.pop(instruction.type.toFrameValueKind())
            }

            is RawMonitorInstruction -> frame.pop(FrameValueKind.REFERENCE)
            is RawThrowInstruction -> frame.pop(FrameValueKind.REFERENCE)
            is RawNopInstruction -> Unit
            is RawRetInstruction -> frame.requireLocal(instruction.slot, FrameValueKind.RETURN_ADDRESS)
            is RawUnknownInstruction -> unsupported(instruction, index)
        }
    }

    private fun executeLocal(instruction: RawLocalInstruction, frame: MutableFrame) {
        val kind = instruction.type.toFrameValueKind()
        when (instruction.operation) {
            LocalOperation.LOAD -> frame.push(frame.requireLocal(instruction.slot, kind))
            LocalOperation.STORE -> {
                if (kind == FrameValueKind.REFERENCE) { // JVMS permits astore to store either a reference or the returnAddress produced by JSR.
                    val value = frame.popAny()
                    if (value.kind != FrameValueKind.REFERENCE && value.kind != FrameValueKind.RETURN_ADDRESS) {
                        throw StackInconsistencyException(
                            "ASTORE requires REFERENCE or RETURN_ADDRESS, got ${value.kind}.",
                        )
                    }
                    frame.writeLocal(instruction.slot, value)
                } else {
                    frame.writeLocal(instruction.slot, frame.pop(kind))
                }
            }
        }
    }

    private fun executeArray(instruction: RawArrayInstruction, index: Int, frame: MutableFrame) {
        val componentKind = instruction.componentType.toFrameValueKind()
        when (instruction.operation) {
            ArrayOperation.LOAD -> {
                frame.pop(FrameValueKind.INT)
                val array = frame.pop(FrameValueKind.REFERENCE)
                if (componentKind == FrameValueKind.REFERENCE) {
                    frame.push(referenceValue(arrayComponentType(array.referenceType), index))
                } else {
                    frame.push(value(instruction.componentType, index))
                }
            }

            ArrayOperation.STORE -> {
                frame.pop(componentKind)
                frame.pop(FrameValueKind.INT)
                frame.pop(FrameValueKind.REFERENCE)
            }
        }
    }

    private fun executeOperator(instruction: RawOperatorInstruction, index: Int, frame: MutableFrame) {
        val mnemonic = instruction.opcode.mnemonic
        val kind = instruction.type.toFrameValueKind()
        when {
            mnemonic == "arraylength" -> {
                frame.pop(FrameValueKind.REFERENCE)
                frame.push(value(FrameValueKind.INT, index))
            }

            mnemonic.endsWith("neg") -> {
                frame.pop(kind)
                frame.push(value(instruction.type, index))
            }

            mnemonic in COMPARISONS -> {
                frame.pop(kind)
                frame.pop(kind)
                frame.push(value(FrameValueKind.INT, index))
            }

            mnemonic in SHIFT_OPERATORS -> {
                frame.pop(FrameValueKind.INT)
                frame.pop(kind)
                frame.push(value(instruction.type, index))
            }

            else -> {
                frame.pop(kind)
                frame.pop(kind)
                frame.push(value(instruction.type, index))
            }
        }
    }

    private fun executeStack(mnemonic: String, frame: MutableFrame) {
        JvmStackOperations.execute(
            mnemonic = mnemonic,
            pop = frame::popAny,
            push = frame::push,
            category = { it.kind.category },
            invalidCategory = { opcode, expected, value ->
                throw StackInconsistencyException(
                    "Opcode $opcode requires category-$expected value, got ${value.kind}.",
                )
            },
            unsupported = { opcode ->
                throw UnsupportedFrameInstructionException("Unsupported stack opcode '$opcode'.")
            },
        )
    }

    private fun executeBranch(mnemonic: String, instructionIndex: Int, frame: MutableFrame) {
        when {
            mnemonic in LEGACY_JSR_OPCODES -> frame.push(
                FrameValue.of(
                    FrameValueKind.RETURN_ADDRESS,
                    ValueOrigin.ReturnAddress(returnInstructionIndex = instructionIndex + 1),
                ),
            )

            mnemonic in NO_STACK_BRANCHES -> Unit
            mnemonic.startsWith("if_icmp") -> {
                frame.pop(FrameValueKind.INT); frame.pop(FrameValueKind.INT)
            }

            mnemonic.startsWith("if_acmp") -> {
                frame.pop(FrameValueKind.REFERENCE); frame.pop(FrameValueKind.REFERENCE)
            }

            mnemonic == "ifnull" || mnemonic == "ifnonnull" -> frame.pop(FrameValueKind.REFERENCE)
            mnemonic.startsWith("if") -> frame.pop(FrameValueKind.INT)
            else -> throw UnsupportedFrameInstructionException("Unsupported branch opcode '$mnemonic'.")
        }
    }

    private fun executeField(instruction: RawFieldInstruction, index: Int, frame: MutableFrame) {
        val kind = instruction.type.toFrameValueKind()
        when (instruction.opcode.mnemonic) {
            "getstatic" -> frame.push(value(instruction.type, index))
            "putstatic" -> frame.pop(kind)
            "getfield" -> {
                frame.pop(FrameValueKind.REFERENCE)
                frame.push(value(instruction.type, index))
            }

            "putfield" -> {
                frame.pop(kind)
                frame.pop(FrameValueKind.REFERENCE)
            }

            else -> unsupported(instruction, index)
        }
    }

    private fun executeInvoke(instruction: RawInvokeInstruction, index: Int, frame: MutableFrame) {
        popArguments(instruction.type.parameterTypes, frame)
        if (instruction.opcode.mnemonic != "invokestatic") frame.pop(FrameValueKind.REFERENCE)
        pushReturn(instruction.type.returnType, index, frame)
    }

    private fun popArguments(types: List<JvmType>, frame: MutableFrame) {
        types.asReversed().forEach { frame.pop(it.toFrameValueKind()) }
    }

    private fun pushReturn(type: JvmType, index: Int, frame: MutableFrame) {
        if (type != JvmType.VoidType) frame.push(value(type, index))
    }

    private fun value(type: JvmType, instructionIndex: Int): FrameValue = FrameValue.of(type, ValueOrigin.Instruction(instructionIndex))

    private fun value(type: JvmComputationalType, instructionIndex: Int): FrameValue = FrameValue.of(type, ValueOrigin.Instruction(instructionIndex))

    private fun value(kind: FrameValueKind, instructionIndex: Int): FrameValue = FrameValue.of(kind, ValueOrigin.Instruction(instructionIndex))

    private fun referenceValue(type: JvmReferenceType, instructionIndex: Int): FrameValue = FrameValue.reference(type, ValueOrigin.Instruction(instructionIndex))

    private fun arrayComponentType(type: JvmReferenceType?): JvmReferenceType {
        val array = (type as? JvmReferenceType.Exact)?.type as? JvmType.ArrayType ?: return JvmReferenceType.Unknown
        val component = array.componentType
        return if (component.isReferenceType) {
            JvmReferenceType.Exact(component)
        } else {
            JvmReferenceType.Unknown
        }
    }

    private fun mergeFrames(
        current: FrameState,
        incoming: FrameState,
        counters: MergeCounters,
        context: String,
        consumer: String,
    ): FrameState {
        if (current.stack.size != incoming.stack.size) {
            throw StackInconsistencyException(
                "Stack height mismatch at $context: ${current.stack.size} vs ${incoming.stack.size}.",
            )
        }
        require(current.locals.size == incoming.locals.size) {
            "Local frame size mismatch at $context: ${current.locals.size} vs ${incoming.locals.size}."
        }

        var changed = false
        val locals = current.locals.indices.map { index ->
            val merged = mergeLocal(current.locals[index], incoming.locals[index], counters, "$context local $index", consumer)
            if (merged != current.locals[index]) changed = true
            merged
        }
        val stack = current.stack.indices.map { index ->
            val merged = mergeValue(current.stack[index], incoming.stack[index], counters, "$context stack $index", consumer)
            if (merged != current.stack[index]) changed = true
            merged
        }
        if (!changed) return current
        counters.frameMerges++
        return FrameState(locals, stack)
    }

    private fun mergeLocal(
        current: FrameValue?,
        incoming: FrameValue?,
        counters: MergeCounters,
        context: String,
        consumer: String,
    ): FrameValue? { // A local slot is allowed to have unrelated types on different control-flow paths as long as
        // the value is not used after those paths merge. In verifier terminology the merged slot
        // becomes TOP/unavailable. Operand-stack values are different: their kinds must still agree.
        if (current == incoming) return current
        if (current == null) return null
        if (incoming == null || current.kind != incoming.kind) {
            counters.valueMerges++
            return null
        }
        return mergeValue(current, incoming, counters, context, consumer)
    }

    private fun mergeValue(
        current: FrameValue,
        incoming: FrameValue,
        counters: MergeCounters,
        context: String,
        consumer: String,
    ): FrameValue {
        if (current == incoming) return current
        if (current.kind != incoming.kind) {
            throw StackInconsistencyException("Value kind mismatch at $context: ${current.kind} vs ${incoming.kind}.")
        }
        val origins = current.origins + incoming.origins
        val referenceType = if (current.kind == FrameValueKind.REFERENCE) {
            val left = requireNotNull(current.referenceType)
            val right = requireNotNull(incoming.referenceType)
            hierarchy?.commonSupertype(left, right, consumer) ?: conservativeCommonSupertype(left, right)
        } else {
            null
        }
        if (origins == current.origins && referenceType == current.referenceType) return current
        if (current.kind == FrameValueKind.REFERENCE && current.referenceType != incoming.referenceType) {
            counters.referenceMerges++
            if (referenceType == JvmReferenceType.Unknown) counters.impreciseReferenceMerges++
        }
        counters.valueMerges++
        return FrameValue(mergeValueType(current.type, incoming.type, referenceType), origins)
    }

    private fun mergeValueType(
        left: JvmValueType,
        right: JvmValueType,
        mergedReferenceType: JvmReferenceType?,
    ): JvmValueType {
        if (left == right) return left
        if (left.kind != right.kind) error("Cannot merge value types with different frame kinds: $left vs $right")
        if (left.kind == FrameValueKind.REFERENCE) {
            return JvmValueType.Reference(requireNotNull(mergedReferenceType))
        } // The verifier collapses boolean/byte/char/short/int to the INT computational category.
        // Preserve a narrow primitive only while all incoming values agree on it.
        return JvmValueType.of(left.kind)
    }

    private fun conservativeCommonSupertype(
        left: JvmReferenceType,
        right: JvmReferenceType,
    ): JvmReferenceType = when {
        left == right -> left
        left == JvmReferenceType.Null -> right
        right == JvmReferenceType.Null -> left
        else -> JvmReferenceType.Unknown
    }

    private fun rootContextFrames(
        contextualFrames: Map<FrameLocation, FrameState>,
    ): Map<BasicBlockId, FrameState> = contextualFrames.asSequence().filter { (location, _) -> location.context == LegacySubroutineContext.EMPTY }.associate { (location, frame) -> location.block to frame }

    private fun blockForInstruction(graph: ControlFlowGraph, instructionIndex: Int): BasicBlockId = graph.blocks.firstOrNull {
        instructionIndex >= it.startInstructionIndex && instructionIndex < it.endInstructionIndexExclusive
    }?.id ?: error("No basic block contains instruction $instructionIndex.")

    private fun requireCategory(value: FrameValue, category: Int, mnemonic: String): FrameValue {
        if (value.kind.category != category) {
            throw StackInconsistencyException("Opcode $mnemonic requires category-$category value, got ${value.kind}.")
        }
        return value
    }

    private fun unsupported(instruction: RawInstruction, index: Int): Nothing = throw UnsupportedFrameInstructionException(
        "Unsupported instruction '${instruction.opcode.mnemonic}' at instruction $index (${instruction::class.simpleName}).",
    )

    private data class FrameLocation(
        val block: BasicBlockId,
        val context: LegacySubroutineContext,
    )

    private data class LegacySubroutineContext(
        val returnSites: List<Int>,
    ) {
        val currentReturnSite: Int?
            get() = returnSites.lastOrNull()

        fun enterSubroutine(returnSite: Int): LegacySubroutineContext {
            require(returnSites.size < MAX_LEGACY_SUBROUTINE_DEPTH) {
                "Legacy JSR nesting exceeds $MAX_LEGACY_SUBROUTINE_DEPTH levels."
            }
            return LegacySubroutineContext(returnSites + returnSite)
        }

        fun returnFromSubroutine(): LegacySubroutineContext {
            require(returnSites.isNotEmpty()) { "Cannot RET from an empty legacy subroutine context." }
            return LegacySubroutineContext(returnSites.dropLast(1))
        }

        companion object {
            val EMPTY = LegacySubroutineContext(emptyList())
            private const val MAX_LEGACY_SUBROUTINE_DEPTH = 64
        }
    }

    private data class ResolvedHandler(
        val start: Int,
        val endExclusive: Int,
        val handlerBlock: BasicBlockId,
        val raw: RawExceptionHandler,
    )

    private data class MergeCounters(
        var frameMerges: Long = 0,
        var valueMerges: Long = 0,
        var referenceMerges: Long = 0,
        var impreciseReferenceMerges: Long = 0,
    )

    private class MutableFrame(
        val locals: MutableList<FrameValue?>,
        val stack: MutableList<FrameValue>,
    ) {
        fun freeze(): FrameState = FrameState(locals.toList(), stack.toList())

        fun push(value: FrameValue) {
            stack += value
        }

        fun pop(expected: FrameValueKind): FrameValue {
            val value = popAny()
            if (value.kind != expected) {
                throw StackInconsistencyException("Expected $expected on stack, got ${value.kind}.")
            }
            return value
        }

        fun popAny(): FrameValue = stack.removeLastOrNull() ?: throw StackInconsistencyException("Operand stack underflow.")

        fun requireLocal(slot: Int, expected: FrameValueKind): FrameValue {
            val value = locals.getOrNull(slot) ?: throw StackInconsistencyException("Local slot $slot is unavailable.")
            if (value.kind != expected) {
                throw StackInconsistencyException("Expected $expected in local slot $slot, got ${value.kind}.")
            }
            return value
        }

        fun writeLocal(slot: Int, value: FrameValue) {
            require(slot in locals.indices) { "Local slot $slot is outside frame size ${locals.size}." }
            invalidateWideOverlap(slot)
            locals[slot] = value
            if (value.kind.category == 2) {
                require(slot + 1 in locals.indices) { "Wide local at slot $slot exceeds frame size ${locals.size}." }
                locals[slot + 1] = null
            }
        }

        private fun invalidateWideOverlap(slot: Int) {
            if (slot > 0 && locals[slot - 1]?.kind?.category == 2) locals[slot - 1] = null
            if (locals[slot]?.kind?.category == 2 && slot + 1 < locals.size) locals[slot + 1] = null
        }
    }

    private companion object {
        val LEGACY_JSR_OPCODES = setOf("jsr", "jsr_w")
        const val ACC_STATIC = 0x0008
        val COMPARISONS = setOf("lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg")
        val SHIFT_OPERATORS = setOf("ishl", "ishr", "iushr", "lshl", "lshr", "lushr")
        val NO_STACK_BRANCHES = setOf("goto", "goto_w")
    }
}

class StackInconsistencyException(message: String) : IllegalStateException(message)
class UnsupportedFrameInstructionException(message: String) : IllegalStateException(message)
