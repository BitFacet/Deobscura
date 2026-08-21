package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.RawConstantInstruction
import io.github.relvl.deobscura.raw.RawConversionInstruction
import io.github.relvl.deobscura.raw.RawIncrementInstruction
import io.github.relvl.deobscura.raw.RawOperatorInstruction

sealed interface SsaConstant {
    val kind: FrameValueKind

    data class IntValue(val value: Int) : SsaConstant {
        override val kind: FrameValueKind = FrameValueKind.INT
    }

    data class LongValue(val value: Long) : SsaConstant {
        override val kind: FrameValueKind = FrameValueKind.LONG
    }

    data class FloatValue(val value: Float) : SsaConstant {
        override val kind: FrameValueKind = FrameValueKind.FLOAT
    }

    data class DoubleValue(val value: Double) : SsaConstant {
        override val kind: FrameValueKind = FrameValueKind.DOUBLE
    }
}

/**
 * Computes numeric constants over SSA values without changing control flow.
 *
 * Integer/long division and remainder by zero are intentionally left unknown because evaluating
 * them would remove the ArithmeticException produced by the original JVM instruction. Reference
 * constants and hierarchy-dependent operations are outside this pass for now.
 */
class SsaConstantPropagator {
    fun propagate(analysis: SsaAnalysis): SsaConstantPropagationResult {
        val constants = linkedMapOf<ValueId, SsaConstant>()
        val operationsByOutput = analysis.operations.mapNotNull { operation ->
            operation.output?.let { it to operation }
        }.toMap()
        var literalConstantCount = 0
        var foldedOperationCount = 0
        var constantPhiCount = 0

        var changed: Boolean
        do {
            changed = false

            analysis.values.forEach { (id, definition) ->
                if (id in constants) return@forEach

                val constant = when (definition) {
                    is SsaValueDefinition.Root -> null
                    is SsaValueDefinition.Instruction -> operationsByOutput[id]?.let { evaluateOperation(it, constants) }
                    is SsaValueDefinition.Phi -> evaluatePhi(definition.id, definition.inputs, constants)
                }

                if (constant != null) {
                    constants[id] = constant
                    when {
                        definition is SsaValueDefinition.Phi -> constantPhiCount++
                        operationsByOutput[id]?.instruction is RawConstantInstruction -> literalConstantCount++
                        else -> foldedOperationCount++
                    }
                    changed = true
                }
            }
        } while (changed)

        return SsaConstantPropagationResult(
            analysis = analysis.copy(constants = constants.toMap()),
            constantValueCount = constants.size,
            literalConstantCount = literalConstantCount,
            foldedOperationCount = foldedOperationCount,
            constantPhiCount = constantPhiCount,
        )
    }

    private fun evaluatePhi(
        output: ValueId,
        inputs: List<SsaPhiInput>,
        constants: Map<ValueId, SsaConstant>,
    ): SsaConstant? {
        val meaningfulInputs = inputs.filter { it.value != output }
        if (meaningfulInputs.isEmpty()) return null
        val values = meaningfulInputs.map { constants[it.value] ?: return null }
        val first = values.first()
        return first.takeIf { candidate -> values.all { sameConstant(candidate, it) } }
    }

    private fun evaluateOperation(
        operation: ValueOperation,
        constants: Map<ValueId, SsaConstant>,
    ): SsaConstant? {
        return when (val instruction = operation.instruction) {
            is RawConstantInstruction -> numericConstant(instruction.value)
            is RawIncrementInstruction -> {
                val input = operation.inputs.singleOrNull()?.let(constants::get) as? SsaConstant.IntValue ?: return null
                SsaConstant.IntValue(input.value + instruction.amount)
            }

            is RawConversionInstruction -> {
                val input = operation.inputs.singleOrNull()?.let(constants::get) ?: return null
                evaluateConversion(instruction.opcode.mnemonic, input)
            }

            is RawOperatorInstruction -> {
                val inputs = operation.inputs.map { constants[it] ?: return null }
                evaluateOperator(instruction.opcode.mnemonic, inputs)
            }

            else -> null
        }
    }

    private fun numericConstant(value: Any): SsaConstant? = when (value) {
        is Int -> SsaConstant.IntValue(value)
        is Long -> SsaConstant.LongValue(value)
        is Float -> SsaConstant.FloatValue(value)
        is Double -> SsaConstant.DoubleValue(value)
        else -> null
    }

    private fun evaluateConversion(mnemonic: String, input: SsaConstant): SsaConstant? = when (mnemonic) {
        "i2b" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.IntValue(it.value.toByte().toInt()) }
        "i2c" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.IntValue(it.value.toChar().code) }
        "i2s" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.IntValue(it.value.toShort().toInt()) }
        "i2l" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.LongValue(it.value.toLong()) }
        "i2f" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.FloatValue(it.value.toFloat()) }
        "i2d" -> (input as? SsaConstant.IntValue)?.let { SsaConstant.DoubleValue(it.value.toDouble()) }
        "l2i" -> (input as? SsaConstant.LongValue)?.let { SsaConstant.IntValue(it.value.toInt()) }
        "l2f" -> (input as? SsaConstant.LongValue)?.let { SsaConstant.FloatValue(it.value.toFloat()) }
        "l2d" -> (input as? SsaConstant.LongValue)?.let { SsaConstant.DoubleValue(it.value.toDouble()) }
        "f2i" -> (input as? SsaConstant.FloatValue)?.let { SsaConstant.IntValue(it.value.toInt()) }
        "f2l" -> (input as? SsaConstant.FloatValue)?.let { SsaConstant.LongValue(it.value.toLong()) }
        "f2d" -> (input as? SsaConstant.FloatValue)?.let { SsaConstant.DoubleValue(it.value.toDouble()) }
        "d2i" -> (input as? SsaConstant.DoubleValue)?.let { SsaConstant.IntValue(it.value.toInt()) }
        "d2l" -> (input as? SsaConstant.DoubleValue)?.let { SsaConstant.LongValue(it.value.toLong()) }
        "d2f" -> (input as? SsaConstant.DoubleValue)?.let { SsaConstant.FloatValue(it.value.toFloat()) }
        else -> null
    }

    private fun evaluateOperator(mnemonic: String, inputs: List<SsaConstant>): SsaConstant? = when (mnemonic) {
        "ineg" -> unaryInt(inputs) { -it }
        "lneg" -> unaryLong(inputs) { -it }
        "fneg" -> unaryFloat(inputs) { -it }
        "dneg" -> unaryDouble(inputs) { -it }

        "iadd" -> binaryInt(inputs, Int::plus)
        "isub" -> binaryInt(inputs, Int::minus)
        "imul" -> binaryInt(inputs, Int::times)
        "idiv" -> binaryInt(inputs) { left, right -> if (right == 0) return null else left / right }
        "irem" -> binaryInt(inputs) { left, right -> if (right == 0) return null else left % right }
        "iand" -> binaryInt(inputs) { left, right -> left and right }
        "ior" -> binaryInt(inputs) { left, right -> left or right }
        "ixor" -> binaryInt(inputs) { left, right -> left xor right }
        "ishl" -> intShift(inputs) { value, distance -> value shl distance }
        "ishr" -> intShift(inputs) { value, distance -> value shr distance }
        "iushr" -> intShift(inputs) { value, distance -> value ushr distance }

        "ladd" -> binaryLong(inputs, Long::plus)
        "lsub" -> binaryLong(inputs, Long::minus)
        "lmul" -> binaryLong(inputs, Long::times)
        "ldiv" -> binaryLong(inputs) { left, right -> if (right == 0L) return null else left / right }
        "lrem" -> binaryLong(inputs) { left, right -> if (right == 0L) return null else left % right }
        "land" -> binaryLong(inputs) { left, right -> left and right }
        "lor" -> binaryLong(inputs) { left, right -> left or right }
        "lxor" -> binaryLong(inputs) { left, right -> left xor right }
        "lshl" -> longShift(inputs) { value, distance -> value shl distance }
        "lshr" -> longShift(inputs) { value, distance -> value shr distance }
        "lushr" -> longShift(inputs) { value, distance -> value ushr distance }

        "fadd" -> binaryFloat(inputs, Float::plus)
        "fsub" -> binaryFloat(inputs, Float::minus)
        "fmul" -> binaryFloat(inputs, Float::times)
        "fdiv" -> binaryFloat(inputs, Float::div)
        "frem" -> binaryFloat(inputs, Float::rem)

        "dadd" -> binaryDouble(inputs, Double::plus)
        "dsub" -> binaryDouble(inputs, Double::minus)
        "dmul" -> binaryDouble(inputs, Double::times)
        "ddiv" -> binaryDouble(inputs, Double::div)
        "drem" -> binaryDouble(inputs, Double::rem)

        "lcmp" -> compareLong(inputs)
        "fcmpl" -> compareFloat(inputs, nanResult = -1)
        "fcmpg" -> compareFloat(inputs, nanResult = 1)
        "dcmpl" -> compareDouble(inputs, nanResult = -1)
        "dcmpg" -> compareDouble(inputs, nanResult = 1)
        else -> null
    }

    private inline fun unaryInt(inputs: List<SsaConstant>, operation: (Int) -> Int): SsaConstant? {
        val value = (inputs.singleOrNull() as? SsaConstant.IntValue)?.value ?: return null
        return SsaConstant.IntValue(operation(value))
    }

    private inline fun unaryLong(inputs: List<SsaConstant>, operation: (Long) -> Long): SsaConstant? {
        val value = (inputs.singleOrNull() as? SsaConstant.LongValue)?.value ?: return null
        return SsaConstant.LongValue(operation(value))
    }

    private inline fun unaryFloat(inputs: List<SsaConstant>, operation: (Float) -> Float): SsaConstant? {
        val value = (inputs.singleOrNull() as? SsaConstant.FloatValue)?.value ?: return null
        return SsaConstant.FloatValue(operation(value))
    }

    private inline fun unaryDouble(inputs: List<SsaConstant>, operation: (Double) -> Double): SsaConstant? {
        val value = (inputs.singleOrNull() as? SsaConstant.DoubleValue)?.value ?: return null
        return SsaConstant.DoubleValue(operation(value))
    }

    private inline fun binaryInt(inputs: List<SsaConstant>, operation: (Int, Int) -> Int): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.IntValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.IntValue)?.value ?: return null
        return SsaConstant.IntValue(operation(left, right))
    }

    private inline fun binaryLong(inputs: List<SsaConstant>, operation: (Long, Long) -> Long): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.LongValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.LongValue)?.value ?: return null
        return SsaConstant.LongValue(operation(left, right))
    }

    private inline fun binaryFloat(inputs: List<SsaConstant>, operation: (Float, Float) -> Float): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.FloatValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.FloatValue)?.value ?: return null
        return SsaConstant.FloatValue(operation(left, right))
    }

    private inline fun binaryDouble(inputs: List<SsaConstant>, operation: (Double, Double) -> Double): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.DoubleValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.DoubleValue)?.value ?: return null
        return SsaConstant.DoubleValue(operation(left, right))
    }

    private inline fun intShift(inputs: List<SsaConstant>, operation: (Int, Int) -> Int): SsaConstant? {
        if (inputs.size != 2) return null
        val value = (inputs[0] as? SsaConstant.IntValue)?.value ?: return null
        val distance = (inputs[1] as? SsaConstant.IntValue)?.value ?: return null
        return SsaConstant.IntValue(operation(value, distance))
    }

    private inline fun longShift(inputs: List<SsaConstant>, operation: (Long, Int) -> Long): SsaConstant? {
        if (inputs.size != 2) return null
        val value = (inputs[0] as? SsaConstant.LongValue)?.value ?: return null
        val distance = (inputs[1] as? SsaConstant.IntValue)?.value ?: return null
        return SsaConstant.LongValue(operation(value, distance))
    }

    private fun compareLong(inputs: List<SsaConstant>): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.LongValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.LongValue)?.value ?: return null
        return SsaConstant.IntValue(left.compareTo(right))
    }

    private fun compareFloat(inputs: List<SsaConstant>, nanResult: Int): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.FloatValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.FloatValue)?.value ?: return null
        return SsaConstant.IntValue(
            when {
                left.isNaN() || right.isNaN() -> nanResult
                left > right -> 1
                left < right -> -1
                else -> 0
            },
        )
    }

    private fun compareDouble(inputs: List<SsaConstant>, nanResult: Int): SsaConstant? {
        if (inputs.size != 2) return null
        val left = (inputs[0] as? SsaConstant.DoubleValue)?.value ?: return null
        val right = (inputs[1] as? SsaConstant.DoubleValue)?.value ?: return null
        return SsaConstant.IntValue(
            when {
                left.isNaN() || right.isNaN() -> nanResult
                left > right -> 1
                left < right -> -1
                else -> 0
            },
        )
    }

    private fun sameConstant(left: SsaConstant, right: SsaConstant): Boolean = when {
        left is SsaConstant.FloatValue && right is SsaConstant.FloatValue -> left.value.toRawBits() == right.value.toRawBits()

        left is SsaConstant.DoubleValue && right is SsaConstant.DoubleValue -> left.value.toRawBits() == right.value.toRawBits()

        else -> left == right
    }
}

data class SsaConstantPropagationResult(
    val analysis: SsaAnalysis,
    val constantValueCount: Int,
    val literalConstantCount: Int,
    val foldedOperationCount: Int,
    val constantPhiCount: Int,
)
