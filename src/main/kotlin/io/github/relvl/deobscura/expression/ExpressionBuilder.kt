package io.github.relvl.deobscura.expression

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaValueDefinition
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOperation
import io.github.relvl.deobscura.raw.*

/** Lifts optimized SSA values and side effects into typed source-like expressions. */
class ExpressionBuilder(
    private val materializer: ExpressionMaterializer = ExpressionMaterializer(),
) {
    fun build(ssa: SsaAnalysis): ExpressionAnalysis {
        val operationsByOutput = ssa.operations.mapNotNull { operation -> operation.output?.let { it to operation } }.toMap()
        val values = linkedMapOf<ValueId, ExpressionValue>()

        ssa.values.values.sortedBy { it.id.value }.forEach { definition ->
            val expression = when (definition) {
                is SsaValueDefinition.Root -> ExpressionValue(
                    definition.id,
                    definition.type,
                    ExpressionNode.Root(definition.origin),
                )

                is SsaValueDefinition.Phi -> ExpressionValue(
                    definition.id,
                    definition.type,
                    ExpressionNode.Phi(definition.blockId, definition.location, definition.inputs),
                )

                is SsaValueDefinition.Instruction -> {
                    val operation = operationsByOutput[definition.id]
                        ?: throw ExpressionIrInconsistencyException(
                            "SSA value v${definition.id.value} refers to instruction ${definition.instructionIndex} without a producing operation.",
                        )
                    if (operation.instructionIndex != definition.instructionIndex) {
                        throw ExpressionIrInconsistencyException(
                            "SSA value v${definition.id.value} definition points to instruction ${definition.instructionIndex}, " +
                                "but its producing operation is ${operation.instructionIndex}.",
                        )
                    }
                    ExpressionValue(
                        definition.id,
                        definition.type,
                        liftValue(operation, definition.type),
                        listOf(operation.instructionIndex),
                    )
                }
            }
            values[definition.id] = expression
        }

        val consumedConstructors = mutableSetOf<Int>()
        ssa.operations.asSequence()
            .filter { it.output == null }
            .forEach { operation ->
                if (attachConstructor(operation, values)) consumedConstructors += operation.instructionIndex
            }

        val statements = ssa.operations.asSequence()
            .filter { it.output == null && it.instructionIndex !in consumedConstructors }
            .map(::liftStatement)
            .toList()

        val initial = ExpressionAnalysis(values, statements)
        return initial.copy(materialization = materializer.materialize(ssa, initial))
    }

    private fun liftValue(operation: ValueOperation, type: JvmValueType): ExpressionNode {
        val instruction = operation.instruction
        val inputs = operation.inputs
        return when (instruction) {
            is RawConstantInstruction -> ExpressionNode.Constant(instruction.value)
            is RawIncrementInstruction -> requireInputs(operation, 1) {
                ExpressionNode.Increment(inputs[0], instruction.amount)
            }
            is RawArrayInstruction -> when (instruction.operation) {
                ArrayOperation.LOAD -> requireInputs(operation, 2) { ExpressionNode.ArrayRead(inputs[0], inputs[1]) }
                ArrayOperation.STORE -> rawValue(operation)
            }

            is RawOperatorInstruction -> liftOperator(operation)
            is RawConversionInstruction -> requireInputs(operation, 1) {
                ExpressionNode.Conversion(inputs[0], type)
            }

            is RawFieldInstruction -> when (instruction.opcode.mnemonic) {
                "getstatic" -> requireInputs(operation, 0) { ExpressionNode.FieldRead(instruction.toSymbol(), null) }
                "getfield" -> requireInputs(operation, 1) { ExpressionNode.FieldRead(instruction.toSymbol(), inputs[0]) }
                else -> rawValue(operation)
            }

            is RawInvokeInstruction -> {
                val (receiver, arguments) = splitInvokeInputs(operation, instruction)
                ExpressionNode.Call(instruction.toSymbol(), receiver, arguments)
            }

            is RawInvokeDynamicInstruction -> ExpressionNode.DynamicCall(instruction.toCallSite(), inputs)
            is RawNewObjectInstruction -> requireInputs(operation, 0) { ExpressionNode.NewObject(instruction.internalName) }
            is RawNewArrayInstruction -> requireInputs(operation, 1) {
                ExpressionNode.NewArray(JvmType.ArrayType(instruction.componentType), inputs)
            }

            is RawNewMultiArrayInstruction -> {
                if (inputs.size != instruction.dimensions) {
                    throw inputCountError(operation, instruction.dimensions)
                }
                ExpressionNode.NewArray(instruction.arrayType, inputs)
            }

            is RawTypeCheckInstruction -> requireInputs(operation, 1) {
                when (instruction.opcode.mnemonic) {
                    "checkcast" -> ExpressionNode.Cast(inputs[0], instruction.type)
                    "instanceof" -> ExpressionNode.InstanceOf(inputs[0], instruction.type)
                    else -> rawValue(operation)
                }
            }

            else -> rawValue(operation)
        }
    }

    private fun liftOperator(operation: ValueOperation): ExpressionNode {
        val mnemonic = operation.instruction.opcode.mnemonic
        val inputs = operation.inputs
        if (mnemonic == "arraylength") {
            return requireInputs(operation, 1) { ExpressionNode.ArrayLength(inputs[0]) }
        }
        if (mnemonic.endsWith("neg")) {
            return requireInputs(operation, 1) { ExpressionNode.Unary(UnaryOperator.NEGATE, inputs[0]) }
        }
        val binary = BINARY_OPERATORS[mnemonic]
        if (binary != null) {
            return requireInputs(operation, 2) { ExpressionNode.Binary(binary, inputs[0], inputs[1]) }
        }
        val nanResult = when (mnemonic) {
            "fcmpl", "dcmpl" -> -1
            "fcmpg", "dcmpg" -> 1
            "lcmp" -> null
            else -> return rawValue(operation)
        }
        return requireInputs(operation, 2) { ExpressionNode.ThreeWayCompare(inputs[0], inputs[1], nanResult) }
    }

    private fun liftStatement(operation: ValueOperation): ExpressionStatement {
        val instruction = operation.instruction
        val inputs = operation.inputs
        return when (instruction) {
            is RawArrayInstruction -> when (instruction.operation) {
                ArrayOperation.STORE -> requireInputs(operation, 3) {
                    ExpressionStatement.ArrayWrite(operation.instructionIndex, inputs[0], inputs[1], inputs[2])
                }

                ArrayOperation.LOAD -> rawStatement(operation)
            }

            is RawFieldInstruction -> when (instruction.opcode.mnemonic) {
                "putstatic" -> requireInputs(operation, 1) {
                    ExpressionStatement.FieldWrite(operation.instructionIndex, instruction.toSymbol(), null, inputs[0])
                }

                "putfield" -> requireInputs(operation, 2) {
                    ExpressionStatement.FieldWrite(operation.instructionIndex, instruction.toSymbol(), inputs[0], inputs[1])
                }

                else -> rawStatement(operation)
            }

            is RawInvokeInstruction -> {
                val (receiver, arguments) = splitInvokeInputs(operation, instruction)
                ExpressionStatement.Call(operation.instructionIndex, instruction.toSymbol(), receiver, arguments)
            }

            is RawInvokeDynamicInstruction ->
                ExpressionStatement.DynamicCall(operation.instructionIndex, instruction.toCallSite(), inputs)

            is RawReturnInstruction -> ExpressionStatement.Return(operation.instructionIndex, inputs.singleOrNull())
            is RawThrowInstruction -> requireInputs(operation, 1) {
                ExpressionStatement.Throw(operation.instructionIndex, inputs[0])
            }

            is RawMonitorInstruction -> requireInputs(operation, 1) {
                val monitorOperation = when (instruction.opcode.mnemonic) {
                    "monitorenter" -> MonitorOperation.ENTER
                    "monitorexit" -> MonitorOperation.EXIT
                    else -> return@requireInputs rawStatement(operation)
                }
                ExpressionStatement.Monitor(operation.instructionIndex, monitorOperation, inputs[0])
            }

            is RawBranchInstruction -> ExpressionStatement.Branch(
                operation.instructionIndex,
                branchCondition(operation, instruction),
            )

            is RawSwitchInstruction -> requireInputs(operation, 1) {
                ExpressionStatement.Switch(operation.instructionIndex, inputs[0])
            }

            else -> rawStatement(operation)
        }
    }

    private fun attachConstructor(
        operation: ValueOperation,
        values: MutableMap<ValueId, ExpressionValue>,
    ): Boolean {
        val instruction = operation.instruction as? RawInvokeInstruction ?: return false
        if (instruction.opcode.mnemonic != "invokespecial" || instruction.name != "<init>") return false
        val receiver = operation.inputs.firstOrNull() ?: return false
        val value = values[receiver] ?: return false
        val newObject = value.node as? ExpressionNode.NewObject ?: return false
        if (newObject.internalName != instruction.owner) return false

        values[receiver] = value.copy(
            node = ExpressionNode.ConstructObject(
                internalName = newObject.internalName,
                constructor = instruction.toSymbol(),
                arguments = operation.inputs.drop(1),
            ),
            instructionIndices = value.instructionIndices + operation.instructionIndex,
        )
        return true
    }

    private fun branchCondition(operation: ValueOperation, instruction: RawBranchInstruction): BranchCondition? {
        val inputs = operation.inputs
        val mnemonic = instruction.opcode.mnemonic
        val operator = comparisonOperator(mnemonic) ?: return null
        return when {
            mnemonic.startsWith("if_icmp") || mnemonic.startsWith("if_acmp") -> {
                if (inputs.size != 2) throw inputCountError(operation, 2)
                BranchCondition(operator, inputs[0], BranchOperand.Value(inputs[1]))
            }

            mnemonic == "ifnull" || mnemonic == "ifnonnull" -> {
                if (inputs.size != 1) throw inputCountError(operation, 1)
                BranchCondition(operator, inputs[0], BranchOperand.Null)
            }

            mnemonic.startsWith("if") -> {
                if (inputs.size != 1) throw inputCountError(operation, 1)
                BranchCondition(operator, inputs[0], BranchOperand.Zero)
            }

            else -> null
        }
    }

    private fun comparisonOperator(mnemonic: String): ComparisonOperator? = when {
        mnemonic.endsWith("eq") -> ComparisonOperator.EQ
        mnemonic.endsWith("ne") -> ComparisonOperator.NE
        mnemonic.endsWith("lt") -> ComparisonOperator.LT
        mnemonic.endsWith("le") -> ComparisonOperator.LE
        mnemonic.endsWith("gt") -> ComparisonOperator.GT
        mnemonic.endsWith("ge") -> ComparisonOperator.GE
        else -> null
    }

    private fun splitInvokeInputs(
        operation: ValueOperation,
        instruction: RawInvokeInstruction,
    ): Pair<ValueId?, List<ValueId>> = if (instruction.opcode.mnemonic == "invokestatic") {
        null to operation.inputs
    } else {
        val receiver = operation.inputs.firstOrNull()
            ?: throw ExpressionIrInconsistencyException(
                "${instruction.opcode.mnemonic} ${instruction.owner}.${instruction.name} at ${operation.instructionIndex} has no receiver.",
            )
        receiver to operation.inputs.drop(1)
    }

    private fun RawFieldInstruction.toSymbol() = FieldSymbol(owner, name, descriptor, type)

    private fun RawInvokeInstruction.toSymbol() = MethodSymbol(
        ownerInternalName = owner,
        name = name,
        descriptor = descriptor,
        type = type,
        invocationKind = when (opcode.mnemonic) {
            "invokestatic" -> InvocationKind.STATIC
            "invokevirtual" -> InvocationKind.VIRTUAL
            "invokespecial" -> InvocationKind.SPECIAL
            "invokeinterface" -> InvocationKind.INTERFACE
            else -> InvocationKind.VIRTUAL
        },
    )

    private fun RawInvokeDynamicInstruction.toCallSite() = DynamicCallSite(
        name = name,
        descriptor = descriptor,
        type = type,
        bootstrapMethod = bootstrapMethod,
        bootstrapArguments = bootstrapArguments,
    )

    private fun rawValue(operation: ValueOperation) =
        ExpressionNode.Raw(operation.instruction.opcode.mnemonic, operation.inputs)

    private fun rawStatement(operation: ValueOperation) =
        ExpressionStatement.Raw(operation.instructionIndex, operation.instruction.opcode.mnemonic, operation.inputs)

    private inline fun <T> requireInputs(operation: ValueOperation, expected: Int, block: () -> T): T {
        if (operation.inputs.size != expected) throw inputCountError(operation, expected)
        return block()
    }

    private fun inputCountError(operation: ValueOperation, expected: Int) = ExpressionIrInconsistencyException(
        "Instruction ${operation.instructionIndex} (${operation.instruction.opcode.mnemonic}) has ${operation.inputs.size} input(s), expected $expected.",
    )

    private companion object {
        val BINARY_OPERATORS = mapOf(
            "iadd" to BinaryOperator.ADD, "ladd" to BinaryOperator.ADD, "fadd" to BinaryOperator.ADD, "dadd" to BinaryOperator.ADD,
            "isub" to BinaryOperator.SUBTRACT, "lsub" to BinaryOperator.SUBTRACT, "fsub" to BinaryOperator.SUBTRACT, "dsub" to BinaryOperator.SUBTRACT,
            "imul" to BinaryOperator.MULTIPLY, "lmul" to BinaryOperator.MULTIPLY, "fmul" to BinaryOperator.MULTIPLY, "dmul" to BinaryOperator.MULTIPLY,
            "idiv" to BinaryOperator.DIVIDE, "ldiv" to BinaryOperator.DIVIDE, "fdiv" to BinaryOperator.DIVIDE, "ddiv" to BinaryOperator.DIVIDE,
            "irem" to BinaryOperator.REMAINDER, "lrem" to BinaryOperator.REMAINDER, "frem" to BinaryOperator.REMAINDER, "drem" to BinaryOperator.REMAINDER,
            "iand" to BinaryOperator.BIT_AND, "land" to BinaryOperator.BIT_AND,
            "ior" to BinaryOperator.BIT_OR, "lor" to BinaryOperator.BIT_OR,
            "ixor" to BinaryOperator.BIT_XOR, "lxor" to BinaryOperator.BIT_XOR,
            "ishl" to BinaryOperator.SHIFT_LEFT, "lshl" to BinaryOperator.SHIFT_LEFT,
            "ishr" to BinaryOperator.SHIFT_RIGHT, "lshr" to BinaryOperator.SHIFT_RIGHT,
            "iushr" to BinaryOperator.UNSIGNED_SHIFT_RIGHT, "lushr" to BinaryOperator.UNSIGNED_SHIFT_RIGHT,
        )
    }
}
