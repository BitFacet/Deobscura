package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.ArrayOperation
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.LocalOperation
import io.github.relvl.deobscura.raw.RawArrayInstruction
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawConstantInstruction
import io.github.relvl.deobscura.raw.RawConversionInstruction
import io.github.relvl.deobscura.raw.RawExceptionHandler
import io.github.relvl.deobscura.raw.RawFieldInstruction
import io.github.relvl.deobscura.raw.RawIncrementInstruction
import io.github.relvl.deobscura.raw.RawInstruction
import io.github.relvl.deobscura.raw.RawInvokeDynamicInstruction
import io.github.relvl.deobscura.raw.RawInvokeInstruction
import io.github.relvl.deobscura.raw.RawLocalInstruction
import io.github.relvl.deobscura.raw.RawMethod
import io.github.relvl.deobscura.raw.RawMonitorInstruction
import io.github.relvl.deobscura.raw.RawNewArrayInstruction
import io.github.relvl.deobscura.raw.RawNewMultiArrayInstruction
import io.github.relvl.deobscura.raw.RawNewObjectInstruction
import io.github.relvl.deobscura.raw.RawNopInstruction
import io.github.relvl.deobscura.raw.RawOperatorInstruction
import io.github.relvl.deobscura.raw.RawRetInstruction
import io.github.relvl.deobscura.raw.RawReturnInstruction
import io.github.relvl.deobscura.raw.RawStackInstruction
import io.github.relvl.deobscura.raw.RawSwitchInstruction
import io.github.relvl.deobscura.raw.RawThrowInstruction
import io.github.relvl.deobscura.raw.RawTypeCheckInstruction
import io.github.relvl.deobscura.raw.RawUnknownInstruction
import java.util.ArrayDeque

class FrameAnalyzer {
    fun analyze(ownerInternalName: String, method: RawMethod, graph: ControlFlowGraph): FrameAnalysis {
        val code = requireNotNull(method.code) { "Method ${method.name}${method.descriptor} has no code." }
        val entryBlock = graph.entryBlock ?: return FrameAnalysis(emptyMap(), emptyMap(), 0, 0)
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

        val entryFrames = mutableMapOf<BasicBlockId, FrameState>()
        val exitFrames = mutableMapOf<BasicBlockId, FrameState>()
        val queue = ArrayDeque<BasicBlockId>()
        val queued = mutableSetOf<BasicBlockId>()
        val counters = MergeCounters()

        entryFrames[entryBlock] = initialFrame(ownerInternalName, method, maxLocals)
        queue.addLast(entryBlock)
        queued += entryBlock

        fun schedule(block: BasicBlockId) {
            if (queued.add(block)) queue.addLast(block)
        }

        fun mergeInto(block: BasicBlockId, incoming: FrameState, context: String) {
            val current = entryFrames[block]
            if (current == null) {
                entryFrames[block] = incoming
                schedule(block)
                return
            }
            val merged = mergeFrames(current, incoming, counters, context)
            if (merged != current) {
                entryFrames[block] = merged
                schedule(block)
            }
        }

        while (queue.isNotEmpty()) {
            val blockId = queue.removeFirst()
            queued.remove(blockId)
            val block = graph.block(blockId)
            val input = requireNotNull(entryFrames[blockId])
            val mutable = MutableFrame(input.locals.toMutableList(), input.stack.toMutableList())

            for (instructionIndex in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                // Any instruction inside a protected range is conservatively treated as a potential throw site.
                handlers.asSequence()
                    .filter { instructionIndex >= it.start && instructionIndex < it.endExclusive }
                    .forEach { handler ->
                        val exceptionFrame = FrameState(
                            locals = mutable.locals.toList(),
                            stack = listOf(
                                FrameValue.of(
                                    FrameValueKind.REFERENCE,
                                    ValueOrigin.ExceptionHandler(
                                        handlerInstructionIndex = graph.block(handler.handlerBlock).startInstructionIndex,
                                        catchType = handler.raw.catchType,
                                    ),
                                ),
                            ),
                        )
                        mergeInto(
                            handler.handlerBlock,
                            exceptionFrame,
                            "exception handler for instruction $instructionIndex",
                        )
                    }

                execute(code.instructions[instructionIndex], instructionIndex, mutable)
            }

            val output = mutable.freeze()
            exitFrames[blockId] = output
            when (val terminator = code.instructions[block.endInstructionIndexExclusive - 1]) {
                is RawRetInstruction -> {
                    val returnAddress = mutable.requireLocal(terminator.slot, FrameValueKind.RETURN_ADDRESS)
                    val returnSites = returnAddress.origins.filterIsInstance<ValueOrigin.ReturnAddress>()
                    require(returnSites.isNotEmpty()) {
                        "RET in block ${block.id.value} has no return-address origin in local ${terminator.slot}."
                    }
                    returnSites.forEach { origin ->
                        val returnBlock = blockForInstruction(graph, origin.returnInstructionIndex)
                        mergeInto(
                            returnBlock,
                            output,
                            "legacy RET from block ${block.id.value} to instruction ${origin.returnInstructionIndex}",
                        )
                    }
                }

                is RawBranchInstruction -> {
                    if (terminator.opcode.mnemonic in LEGACY_JSR_OPCODES) {
                        // The CFG keeps the post-JSR block reachable for structural diagnostics, but execution
                        // reaches it only through RET. Propagate the frame only into the subroutine target here.
                        graph.edges.asSequence()
                            .filter {
                                it.from == blockId && it.kind == ControlFlowEdgeKind.JUMP
                            }
                            .forEach { edge ->
                                mergeInto(edge.to, output, "legacy JSR edge ${edge.from.value}->${edge.to.value}")
                            }
                    } else {
                        propagateOrdinarySuccessors(graph, blockId, output, ::mergeInto)
                    }
                }

                else -> propagateOrdinarySuccessors(graph, blockId, output, ::mergeInto)
            }
        }

        return FrameAnalysis(
            entryFrames = entryFrames.toMap(),
            exitFrames = exitFrames.toMap(),
            frameMergeCount = counters.frameMerges,
            valueMergeCount = counters.valueMerges,
        )
    }

    private fun initialFrame(ownerInternalName: String, method: RawMethod, maxLocals: Int): FrameState {
        val locals = MutableList<FrameValue?>(maxLocals) { null }
        var slot = 0
        if (method.accessFlags and ACC_STATIC == 0) {
            require(slot < maxLocals) { "Instance method has no local slot for this." }
            locals[slot++] = FrameValue.of(FrameValueKind.REFERENCE, ValueOrigin.This(ownerInternalName))
        }
        method.type.parameterTypes.forEachIndexed { parameterIndex, type ->
            val kind = type.toFrameValueKind()
            require(slot < maxLocals) { "Parameter $parameterIndex does not fit in maxLocals=$maxLocals." }
            locals[slot] = FrameValue.of(kind, ValueOrigin.Parameter(parameterIndex))
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
            is RawConstantInstruction -> frame.push(value(instruction.type.toFrameValueKind(), index))
            is RawLocalInstruction -> executeLocal(instruction, frame)
            is RawIncrementInstruction -> {
                frame.requireLocal(instruction.slot, FrameValueKind.INT)
                frame.writeLocal(instruction.slot, value(FrameValueKind.INT, index))
            }
            is RawArrayInstruction -> executeArray(instruction, index, frame)
            is RawOperatorInstruction -> executeOperator(instruction, index, frame)
            is RawConversionInstruction -> {
                frame.pop(instruction.fromType.toFrameValueKind())
                frame.push(value(instruction.toType.toFrameValueKind(), index))
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
            is RawNewObjectInstruction -> frame.push(value(FrameValueKind.REFERENCE, index))
            is RawNewArrayInstruction -> {
                frame.pop(FrameValueKind.INT)
                frame.push(value(FrameValueKind.REFERENCE, index))
            }
            is RawNewMultiArrayInstruction -> {
                repeat(instruction.dimensions) { frame.pop(FrameValueKind.INT) }
                frame.push(value(FrameValueKind.REFERENCE, index))
            }
            is RawTypeCheckInstruction -> when (instruction.opcode.mnemonic) {
                "checkcast" -> {
                    frame.pop(FrameValueKind.REFERENCE)
                    frame.push(value(FrameValueKind.REFERENCE, index))
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
                if (kind == FrameValueKind.REFERENCE) {
                    // JVMS permits astore to store either a reference or the returnAddress produced by JSR.
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
                frame.pop(FrameValueKind.REFERENCE)
                frame.push(value(componentKind, index))
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
                frame.push(value(kind, index))
            }
            mnemonic in COMPARISONS -> {
                frame.pop(kind)
                frame.pop(kind)
                frame.push(value(FrameValueKind.INT, index))
            }
            mnemonic in SHIFT_OPERATORS -> {
                frame.pop(FrameValueKind.INT)
                frame.pop(kind)
                frame.push(value(kind, index))
            }
            else -> {
                frame.pop(kind)
                frame.pop(kind)
                frame.push(value(kind, index))
            }
        }
    }

    private fun executeStack(mnemonic: String, frame: MutableFrame) {
        when (mnemonic) {
            "pop" -> requireCategory(frame.popAny(), 1, mnemonic)
            "pop2" -> {
                val first = frame.popAny()
                if (first.kind.category == 1) requireCategory(frame.popAny(), 1, mnemonic)
            }
            "dup" -> {
                val a = requireCategory(frame.popAny(), 1, mnemonic)
                frame.push(a)
                frame.push(a)
            }
            "dup_x1" -> {
                val a = requireCategory(frame.popAny(), 1, mnemonic)
                val b = requireCategory(frame.popAny(), 1, mnemonic)
                frame.push(a); frame.push(b); frame.push(a)
            }
            "dup_x2" -> {
                val a = requireCategory(frame.popAny(), 1, mnemonic)
                val b = frame.popAny()
                if (b.kind.category == 2) {
                    frame.push(a); frame.push(b); frame.push(a)
                } else {
                    val c = requireCategory(frame.popAny(), 1, mnemonic)
                    frame.push(a); frame.push(c); frame.push(b); frame.push(a)
                }
            }
            "dup2" -> {
                val a = frame.popAny()
                if (a.kind.category == 2) {
                    frame.push(a); frame.push(a)
                } else {
                    val b = requireCategory(frame.popAny(), 1, mnemonic)
                    frame.push(b); frame.push(a); frame.push(b); frame.push(a)
                }
            }
            "dup2_x1" -> {
                val a = frame.popAny()
                if (a.kind.category == 2) {
                    val b = requireCategory(frame.popAny(), 1, mnemonic)
                    frame.push(a); frame.push(b); frame.push(a)
                } else {
                    val b = requireCategory(frame.popAny(), 1, mnemonic)
                    val c = requireCategory(frame.popAny(), 1, mnemonic)
                    frame.push(b); frame.push(a); frame.push(c); frame.push(b); frame.push(a)
                }
            }
            "dup2_x2" -> executeDup2X2(frame)
            "swap" -> {
                val a = requireCategory(frame.popAny(), 1, mnemonic)
                val b = requireCategory(frame.popAny(), 1, mnemonic)
                frame.push(a); frame.push(b)
            }
            else -> throw UnsupportedFrameInstructionException("Unsupported stack opcode '$mnemonic'.")
        }
    }

    private fun executeDup2X2(frame: MutableFrame) {
        val a = frame.popAny()
        if (a.kind.category == 2) {
            val b = frame.popAny()
            if (b.kind.category == 2) {
                frame.push(a); frame.push(b); frame.push(a)
            } else {
                val c = requireCategory(frame.popAny(), 1, "dup2_x2")
                frame.push(a); frame.push(c); frame.push(b); frame.push(a)
            }
        } else {
            val b = requireCategory(frame.popAny(), 1, "dup2_x2")
            val c = frame.popAny()
            if (c.kind.category == 2) {
                frame.push(b); frame.push(a); frame.push(c); frame.push(b); frame.push(a)
            } else {
                val d = requireCategory(frame.popAny(), 1, "dup2_x2")
                frame.push(b); frame.push(a); frame.push(d); frame.push(c); frame.push(b); frame.push(a)
            }
        }
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
            "getstatic" -> frame.push(value(kind, index))
            "putstatic" -> frame.pop(kind)
            "getfield" -> {
                frame.pop(FrameValueKind.REFERENCE)
                frame.push(value(kind, index))
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
        if (type != JvmType.VoidType) frame.push(value(type.toFrameValueKind(), index))
    }

    private fun value(kind: FrameValueKind, instructionIndex: Int): FrameValue =
        FrameValue.of(kind, ValueOrigin.Instruction(instructionIndex))

    private fun mergeFrames(
        current: FrameState,
        incoming: FrameState,
        counters: MergeCounters,
        context: String,
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
            val merged = mergeLocal(current.locals[index], incoming.locals[index], counters, "$context local $index")
            if (merged != current.locals[index]) changed = true
            merged
        }
        val stack = current.stack.indices.map { index ->
            val merged = mergeValue(current.stack[index], incoming.stack[index], counters, "$context stack $index")
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
    ): FrameValue? {
        // A local slot is allowed to have unrelated types on different control-flow paths as long as
        // the value is not used after those paths merge. In verifier terminology the merged slot
        // becomes TOP/unavailable. Operand-stack values are different: their kinds must still agree.
        if (current == incoming) return current
        if (current == null) return null
        if (incoming == null || current.kind != incoming.kind) {
            counters.valueMerges++
            return null
        }
        return mergeValue(current, incoming, counters, context)
    }

    private fun mergeValue(
        current: FrameValue,
        incoming: FrameValue,
        counters: MergeCounters,
        context: String,
    ): FrameValue {
        if (current == incoming) return current
        if (current.kind != incoming.kind) {
            throw StackInconsistencyException("Value kind mismatch at $context: ${current.kind} vs ${incoming.kind}.")
        }
        val origins = current.origins + incoming.origins
        if (origins == current.origins) return current
        counters.valueMerges++
        return FrameValue(current.kind, origins)
    }

    private fun propagateOrdinarySuccessors(
        graph: ControlFlowGraph,
        blockId: BasicBlockId,
        output: FrameState,
        mergeInto: (BasicBlockId, FrameState, String) -> Unit,
    ) {
        graph.edges.asSequence()
            .filter { it.from == blockId && it.kind != ControlFlowEdgeKind.EXCEPTION }
            .forEach { edge -> mergeInto(edge.to, output, "edge ${edge.from.value}->${edge.to.value}") }
    }

    private fun blockForInstruction(graph: ControlFlowGraph, instructionIndex: Int): BasicBlockId =
        graph.blocks.firstOrNull {
            instructionIndex >= it.startInstructionIndex && instructionIndex < it.endInstructionIndexExclusive
        }?.id ?: error("No basic block contains instruction $instructionIndex.")

    private fun requireCategory(value: FrameValue, category: Int, mnemonic: String): FrameValue {
        if (value.kind.category != category) {
            throw StackInconsistencyException("Opcode $mnemonic requires category-$category value, got ${value.kind}.")
        }
        return value
    }

    private fun unsupported(instruction: RawInstruction, index: Int): Nothing =
        throw UnsupportedFrameInstructionException(
            "Unsupported instruction '${instruction.opcode.mnemonic}' at instruction $index (${instruction::class.simpleName}).",
        )

    private data class ResolvedHandler(
        val start: Int,
        val endExclusive: Int,
        val handlerBlock: BasicBlockId,
        val raw: RawExceptionHandler,
    )

    private data class MergeCounters(
        var frameMerges: Long = 0,
        var valueMerges: Long = 0,
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

        fun popAny(): FrameValue = stack.removeLastOrNull()
            ?: throw StackInconsistencyException("Operand stack underflow.")

        fun requireLocal(slot: Int, expected: FrameValueKind): FrameValue {
            val value = locals.getOrNull(slot)
                ?: throw StackInconsistencyException("Local slot $slot is unavailable.")
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
