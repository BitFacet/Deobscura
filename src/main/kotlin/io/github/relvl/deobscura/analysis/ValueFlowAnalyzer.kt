package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*

class ValueFlowAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        frames: FrameAnalysis,
    ): ValueFlowAnalysis {
        val allocator = ValueAllocator()
        val operations = mutableListOf<ValueOperation>()
        val blockEntryLocals = linkedMapOf<io.github.relvl.deobscura.cfg.BasicBlockId, List<ValueId?>>()
        val blockEntryStacks = linkedMapOf<io.github.relvl.deobscura.cfg.BasicBlockId, List<ValueId>>()
        val blockExitLocals = linkedMapOf<io.github.relvl.deobscura.cfg.BasicBlockId, List<ValueId?>>()
        val blockExitStacks = linkedMapOf<io.github.relvl.deobscura.cfg.BasicBlockId, List<ValueId>>()
        var eliminatedStackInstructionCount = 0
        var unanalyzedBlockCount = 0

        graph.blocks.forEach { block ->
            val entry = frames.entryFrames[block.id]
            if (entry == null) {
                unanalyzedBlockCount++
                return@forEach
            }
            val state = MutableValueState(
                locals = entry.locals.mapIndexed { slot, value ->
                    value?.let { allocator.valueForFrame(it, ValueMergeSite.Local(block.id, slot)) }
                }.toMutableList(),
                stack = entry.stack.mapIndexed { stackIndex, value ->
                    allocator.valueForFrame(value, ValueMergeSite.Stack(block.id, stackIndex))
                }.toMutableList(),
            )
            blockEntryLocals[block.id] = state.locals.map { it?.id }
            blockEntryStacks[block.id] = state.stack.map { it.id }

            for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                val instruction = graph.code.instructions[index]
                if (instruction is RawStackInstruction) {
                    executeStack(instruction.opcode.mnemonic, state)
                    eliminatedStackInstructionCount++
                    continue
                }
                val operation = execute(instruction, index, state, allocator)
                if (operation != null) operations += operation
            }
            blockExitLocals[block.id] = state.locals.map { it?.id }
            blockExitStacks[block.id] = state.stack.map { it.id }
        }

        allocator.verifyInstructionDefinitions(graph.code.instructions)
        return ValueFlowAnalysis(
            values = allocator.definitions.toMap(),
            operations = operations,
            blockEntryLocals = blockEntryLocals,
            blockEntryStacks = blockEntryStacks,
            blockExitLocals = blockExitLocals,
            blockExitStacks = blockExitStacks,
            mergeValueCount = allocator.definitions.values.count { it is ValueDefinition.Merge },
            eliminatedStackInstructionCount = eliminatedStackInstructionCount,
            unanalyzedBlockCount = unanalyzedBlockCount,
        )
    }

    private fun execute(
        instruction: RawInstruction,
        index: Int,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation? = when (instruction) {
        is RawConstantInstruction -> produce(instruction, index, instruction.toValueType(), emptyList(), state, allocator)
        is RawLocalInstruction -> executeLocal(instruction, index, state)
        is RawIncrementInstruction -> {
            val input = state.requireLocal(instruction.slot, FrameValueKind.INT)
            val output = allocator.instructionValue(index, JvmValueType.of(FrameValueKind.INT))
            state.writeLocal(instruction.slot, output)
            ValueOperation(index, instruction, listOf(input.id), output.id, instruction.slot)
        }

        is RawArrayInstruction -> executeArray(instruction, index, state, allocator)
        is RawOperatorInstruction -> executeOperator(instruction, index, state, allocator)
        is RawConversionInstruction -> {
            val input = state.pop(instruction.fromType.toFrameValueKind())
            produce(instruction, index, JvmValueType.of(instruction.toType), listOf(input), state, allocator)
        }

        is RawBranchInstruction -> executeBranch(instruction, index, state)
        is RawSwitchInstruction -> ValueOperation(index, instruction, listOf(state.pop(FrameValueKind.INT).id))
        is RawFieldInstruction -> executeField(instruction, index, state, allocator)
        is RawInvokeInstruction -> executeInvoke(instruction, index, state, allocator)
        is RawInvokeDynamicInstruction -> {
            val arguments = popArguments(instruction.type.parameterTypes, state)
            produceOptional(instruction, index, instruction.type.returnType, arguments, state, allocator)
        }

        is RawNewObjectInstruction -> produce(instruction, index, JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType(instruction.internalName))), emptyList(), state, allocator)
        is RawNewArrayInstruction -> {
            val size = state.pop(FrameValueKind.INT)
            produce(instruction, index, JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ArrayType(instruction.componentType))), listOf(size), state, allocator)
        }

        is RawNewMultiArrayInstruction -> {
            val dimensions = MutableList(instruction.dimensions) { state.pop(FrameValueKind.INT) }.asReversed()
            produce(instruction, index, JvmValueType.Reference(JvmReferenceType.Exact(instruction.arrayType)), dimensions, state, allocator)
        }

        is RawTypeCheckInstruction -> when (instruction.opcode.mnemonic) {
            "checkcast" -> produce(
                instruction,
                index,
                JvmValueType.Reference(JvmReferenceType.Exact(instruction.type)),
                listOf(state.pop(FrameValueKind.REFERENCE)),
                state,
                allocator,
            )

            "instanceof" -> produce(
                instruction,
                index,
                FrameValueKind.INT,
                listOf(state.pop(FrameValueKind.REFERENCE)),
                state,
                allocator,
            )

            else -> unsupported(instruction, index)
        }

        is RawReturnInstruction -> {
            val inputs = if (instruction.type == JvmComputationalType.VOID) {
                emptyList()
            } else {
                listOf(state.pop(instruction.type.toFrameValueKind()).id)
            }
            ValueOperation(index, instruction, inputs)
        }

        is RawMonitorInstruction -> ValueOperation(index, instruction, listOf(state.pop(FrameValueKind.REFERENCE).id))
        is RawThrowInstruction -> ValueOperation(index, instruction, listOf(state.pop(FrameValueKind.REFERENCE).id))
        is RawNopInstruction -> null
        is RawRetInstruction -> throw UnsupportedValueFlowInstructionException(
            "RET remains after legacy subroutine normalization at instruction $index.",
        )

        is RawUnknownInstruction -> unsupported(instruction, index)
        is RawStackInstruction -> error("Stack instructions are handled before execute().")
    }

    private fun executeLocal(
        instruction: RawLocalInstruction,
        index: Int,
        state: MutableValueState,
    ): ValueOperation {
        val kind = instruction.type.toFrameValueKind()
        return when (instruction.operation) {
            LocalOperation.LOAD -> {
                val value = state.requireLocal(instruction.slot, kind)
                state.push(value)
                ValueOperation(index, instruction, listOf(value.id), localSlot = instruction.slot)
            }

            LocalOperation.STORE -> {
                val value = if (kind == FrameValueKind.REFERENCE) state.popReferenceLike() else state.pop(kind)
                state.writeLocal(instruction.slot, value)
                ValueOperation(index, instruction, listOf(value.id), localSlot = instruction.slot)
            }
        }
    }

    private fun executeArray(
        instruction: RawArrayInstruction,
        index: Int,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation {
        val componentKind = instruction.componentType.toFrameValueKind()
        return when (instruction.operation) {
            ArrayOperation.LOAD -> {
                val arrayIndex = state.pop(FrameValueKind.INT)
                val array = state.pop(FrameValueKind.REFERENCE)
                produce(instruction, index, arrayLoadType(array, instruction.componentType), listOf(array, arrayIndex), state, allocator)
            }

            ArrayOperation.STORE -> {
                val value = state.pop(componentKind)
                val arrayIndex = state.pop(FrameValueKind.INT)
                val array = state.pop(FrameValueKind.REFERENCE)
                ValueOperation(index, instruction, listOf(array.id, arrayIndex.id, value.id))
            }
        }
    }

    private fun arrayLoadType(array: SymbolicValue, componentType: JvmComputationalType): JvmValueType {
        if (componentType != JvmComputationalType.REFERENCE) return JvmValueType.of(componentType)
        val exactArray = (array.type as? JvmValueType.Reference)?.referenceType as? JvmReferenceType.Exact
        val component = (exactArray?.type as? JvmType.ArrayType)?.componentType
        return if (component is JvmType.ObjectType || component is JvmType.ArrayType) {
            JvmValueType.Reference(JvmReferenceType.Exact(component))
        } else {
            JvmValueType.Reference(JvmReferenceType.Unknown)
        }
    }

    private fun executeOperator(
        instruction: RawOperatorInstruction,
        index: Int,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation {
        val mnemonic = instruction.opcode.mnemonic
        val kind = instruction.type.toFrameValueKind()
        return when {
            mnemonic == "arraylength" -> produce(
                instruction,
                index,
                FrameValueKind.INT,
                listOf(state.pop(FrameValueKind.REFERENCE)),
                state,
                allocator,
            )

            mnemonic.endsWith("neg") -> produce(
                instruction,
                index,
                JvmValueType.of(instruction.type),
                listOf(state.pop(kind)),
                state,
                allocator,
            )

            mnemonic in COMPARISONS -> {
                val right = state.pop(kind)
                val left = state.pop(kind)
                produce(instruction, index, FrameValueKind.INT, listOf(left, right), state, allocator)
            }

            mnemonic in SHIFT_OPERATORS -> {
                val distance = state.pop(FrameValueKind.INT)
                val value = state.pop(kind)
                produce(instruction, index, JvmValueType.of(instruction.type), listOf(value, distance), state, allocator)
            }

            else -> {
                val right = state.pop(kind)
                val left = state.pop(kind)
                produce(instruction, index, JvmValueType.of(instruction.type), listOf(left, right), state, allocator)
            }
        }
    }

    private fun executeBranch(
        instruction: RawBranchInstruction,
        index: Int,
        state: MutableValueState,
    ): ValueOperation {
        val mnemonic = instruction.opcode.mnemonic
        val inputs = when {
            mnemonic in NO_STACK_BRANCHES -> emptyList()
            mnemonic.startsWith("if_icmp") -> {
                val right = state.pop(FrameValueKind.INT)
                val left = state.pop(FrameValueKind.INT)
                listOf(left.id, right.id)
            }

            mnemonic.startsWith("if_acmp") -> {
                val right = state.pop(FrameValueKind.REFERENCE)
                val left = state.pop(FrameValueKind.REFERENCE)
                listOf(left.id, right.id)
            }

            mnemonic == "ifnull" || mnemonic == "ifnonnull" -> listOf(state.pop(FrameValueKind.REFERENCE).id)
            mnemonic.startsWith("if") -> listOf(state.pop(FrameValueKind.INT).id)
            else -> throw UnsupportedValueFlowInstructionException("Unsupported branch opcode '$mnemonic' at instruction $index.")
        }
        return ValueOperation(index, instruction, inputs)
    }

    private fun executeField(
        instruction: RawFieldInstruction,
        index: Int,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation {
        val kind = instruction.type.toFrameValueKind()
        return when (instruction.opcode.mnemonic) {
            "getstatic" -> produce(instruction, index, JvmValueType.of(instruction.type), emptyList(), state, allocator)
            "putstatic" -> ValueOperation(index, instruction, listOf(state.pop(kind).id))
            "getfield" -> produce(
                instruction,
                index,
                JvmValueType.of(instruction.type),
                listOf(state.pop(FrameValueKind.REFERENCE)),
                state,
                allocator,
            )

            "putfield" -> {
                val value = state.pop(kind)
                val receiver = state.pop(FrameValueKind.REFERENCE)
                ValueOperation(index, instruction, listOf(receiver.id, value.id))
            }

            else -> unsupported(instruction, index)
        }
    }

    private fun executeInvoke(
        instruction: RawInvokeInstruction,
        index: Int,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation {
        val arguments = popArguments(instruction.type.parameterTypes, state)
        val inputs = if (instruction.opcode.mnemonic == "invokestatic") {
            arguments
        } else {
            listOf(state.pop(FrameValueKind.REFERENCE)) + arguments
        }
        return produceOptional(instruction, index, instruction.type.returnType, inputs, state, allocator)
    }

    private fun popArguments(types: List<JvmType>, state: MutableValueState): List<SymbolicValue> {
        val reversed = types.asReversed().map { state.pop(it.toFrameValueKind()) }
        return reversed.asReversed()
    }

    private fun produceOptional(
        instruction: RawInstruction,
        index: Int,
        returnType: JvmType,
        inputs: List<SymbolicValue>,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation = if (returnType == JvmType.VoidType) {
        ValueOperation(index, instruction, inputs.map { it.id })
    } else {
        produce(instruction, index, JvmValueType.of(returnType), inputs, state, allocator)
    }

    private fun produce(
        instruction: RawInstruction,
        index: Int,
        type: JvmValueType,
        inputs: List<SymbolicValue>,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation {
        val output = allocator.instructionValue(index, type)
        state.push(output)
        return ValueOperation(index, instruction, inputs.map { it.id }, output.id)
    }

    private fun produce(
        instruction: RawInstruction,
        index: Int,
        kind: FrameValueKind,
        inputs: List<SymbolicValue>,
        state: MutableValueState,
        allocator: ValueAllocator,
    ): ValueOperation = produce(instruction, index, JvmValueType.of(kind), inputs, state, allocator)

    private fun executeStack(mnemonic: String, state: MutableValueState) {
        JvmStackOperations.execute(
            mnemonic = mnemonic,
            pop = state::popAny,
            push = state::push,
            category = { it.kind.category },
            invalidCategory = { opcode, expected, value ->
                throw ValueFlowInconsistencyException(
                    "Opcode $opcode requires category-$expected value, got ${value.kind}.",
                )
            },
            unsupported = { opcode ->
                throw UnsupportedValueFlowInstructionException("Unsupported stack opcode '$opcode'.")
            },
        )
    }

    private fun requireCategory(value: SymbolicValue, category: Int, mnemonic: String): SymbolicValue {
        if (value.kind.category != category) {
            throw ValueFlowInconsistencyException("Opcode $mnemonic requires category-$category value, got ${value.kind}.")
        }
        return value
    }

    private fun unsupported(instruction: RawInstruction, index: Int): Nothing =
        throw UnsupportedValueFlowInstructionException(
            "Unsupported instruction '${instruction.opcode.mnemonic}' at instruction $index (${instruction::class.simpleName}).",
        )

    private data class SymbolicValue(val id: ValueId, val type: JvmValueType) {
        val kind: FrameValueKind get() = type.kind
    }

    private class MutableValueState(
        val locals: MutableList<SymbolicValue?>,
        val stack: MutableList<SymbolicValue>,
    ) {
        fun push(value: SymbolicValue) {
            stack += value
        }

        fun pop(expected: FrameValueKind): SymbolicValue {
            val value = popAny()
            if (value.kind != expected) {
                throw ValueFlowInconsistencyException("Expected $expected on stack, got ${value.kind}.")
            }
            return value
        }

        fun popReferenceLike(): SymbolicValue {
            val value = popAny()
            if (value.kind != FrameValueKind.REFERENCE && value.kind != FrameValueKind.RETURN_ADDRESS) {
                throw ValueFlowInconsistencyException("Expected reference-like value on stack, got ${value.kind}.")
            }
            return value
        }

        fun popAny(): SymbolicValue = stack.removeLastOrNull()
            ?: throw ValueFlowInconsistencyException("Operand stack underflow.")

        fun requireLocal(slot: Int, expected: FrameValueKind): SymbolicValue {
            val value = locals.getOrNull(slot)
                ?: throw ValueFlowInconsistencyException("Local slot $slot is unavailable.")
            if (value.kind != expected) {
                throw ValueFlowInconsistencyException("Expected $expected in local slot $slot, got ${value.kind}.")
            }
            return value
        }

        fun writeLocal(slot: Int, value: SymbolicValue) {
            require(slot in locals.indices) { "Local slot $slot is outside frame size ${locals.size}." }
            if (slot > 0 && locals[slot - 1]?.kind?.category == 2) locals[slot - 1] = null
            if (locals[slot]?.kind?.category == 2 && slot + 1 < locals.size) locals[slot + 1] = null
            locals[slot] = value
            if (value.kind.category == 2) {
                require(slot + 1 in locals.indices) { "Wide local at slot $slot exceeds frame size ${locals.size}." }
                locals[slot + 1] = null
            }
        }
    }

    private class ValueAllocator {
        private var nextId = 0
        val definitions = linkedMapOf<ValueId, ValueDefinition>()
        private val roots = mutableMapOf<ValueOrigin, SymbolicValue>()
        private val instructions = mutableMapOf<Int, SymbolicValue>()
        private val merges = mutableMapOf<MergeKey, SymbolicValue>()

        fun valueForFrame(value: FrameValue, site: ValueMergeSite): SymbolicValue {
            if (value.origins.size == 1) return valueForOrigin(value.type, value.origins.single())
            val origins = value.origins.sortedBy(::originSortKey)
            val inputs = origins.map { valueForOrigin(value.type, it) }
            val key = MergeKey(value.type, site, origins)
            return merges.getOrPut(key) {
                val id = newId()
                definitions[id] = ValueDefinition.Merge(id, value.type, site, inputs.map { it.id })
                SymbolicValue(id, value.type)
            }
        }

        fun instructionValue(index: Int, type: JvmValueType): SymbolicValue {
            val existing = instructions[index]
            if (existing == null) return createInstructionValue(index, type)
            requireSameInstructionKind(index, existing, type)
            if (existing.type == type) return existing

            // The instruction itself is authoritative for its produced type. Entry-frame reconstruction
            // may have materialized the value earlier using a widened merge type (for example BYTE
            // joined with INT, or an exact reference joined with a supertype). Once the instruction is
            // executed, keep its intrinsic result type instead of permanently widening ValueId.
            val precise = SymbolicValue(existing.id, type)
            instructions[index] = precise
            definitions[existing.id] = ValueDefinition.Instruction(existing.id, type, index)
            return precise
        }

        fun instructionValue(index: Int, kind: FrameValueKind): SymbolicValue =
            instructionValue(index, JvmValueType.of(kind))

        fun verifyInstructionDefinitions(rawInstructions: List<RawInstruction>) {
            instructions.keys.forEach { index ->
                require(index in rawInstructions.indices) { "Value refers to missing instruction $index." }
            }
        }

        private fun valueForOrigin(type: JvmValueType, origin: ValueOrigin): SymbolicValue = when (origin) {
            is ValueOrigin.Instruction -> instructionValueForFrame(origin.index, type)
            else -> roots.getOrPut(origin) {
                // A root is identified by its semantic origin, not by the contextual type observed
                // at a particular block entry. Parameters, `this`, return addresses, and handler
                // values are single SSA values even when frame merging later widens their type.
                val id = newId()
                definitions[id] = ValueDefinition.Root(id, type, origin)
                SymbolicValue(id, type)
            }.also { existing ->
                if (existing.kind != type.kind) {
                    throw ValueFlowInconsistencyException(
                        "Root $origin is used as both ${existing.kind} and ${type.kind}.",
                    )
                }
            }
        }

        private fun instructionValueForFrame(index: Int, contextualType: JvmValueType): SymbolicValue {
            val existing = instructions[index]
            if (existing != null) {
                requireSameInstructionKind(index, existing, contextualType)
                return existing
            }
            return createInstructionValue(index, contextualType)
        }

        private fun createInstructionValue(index: Int, type: JvmValueType): SymbolicValue {
            val id = newId()
            val value = SymbolicValue(id, type)
            instructions[index] = value
            definitions[id] = ValueDefinition.Instruction(id, type, index)
            return value
        }

        private fun requireSameInstructionKind(index: Int, existing: SymbolicValue, type: JvmValueType) {
            if (existing.kind != type.kind) {
                throw ValueFlowInconsistencyException(
                    "Instruction $index is used as both ${existing.kind} and ${type.kind}.",
                )
            }
        }

        private fun newId(): ValueId = ValueId(nextId++)

        private data class MergeKey(
            val type: JvmValueType,
            val site: ValueMergeSite,
            val origins: List<ValueOrigin>,
        )

        private companion object {
            fun originSortKey(origin: ValueOrigin): String = when (origin) {
                is ValueOrigin.This -> "0:${origin.ownerInternalName}"
                is ValueOrigin.Parameter -> "1:${origin.index}"
                is ValueOrigin.Instruction -> "2:${origin.index}"
                is ValueOrigin.ReturnAddress -> "3:${origin.returnInstructionIndex}"
                is ValueOrigin.ExceptionHandler -> "4:${origin.handlerInstructionIndex}:${origin.catchType}"
            }
        }
    }

    private companion object {
        val COMPARISONS = setOf("lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg")
        val SHIFT_OPERATORS = setOf("ishl", "ishr", "iushr", "lshl", "lshr", "lushr")
        val NO_STACK_BRANCHES = setOf("goto", "goto_w")
    }
}

class ValueFlowInconsistencyException(message: String) : IllegalStateException(message)
class UnsupportedValueFlowInstructionException(message: String) : IllegalStateException(message)
