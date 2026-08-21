package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.*
import java.lang.constant.ConstantDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SsaConstantPropagatorTest {
    private val propagator = SsaConstantPropagator()

    @Test
    fun `propagates literals through integer arithmetic and conversion`() {
        val one = instruction(ValueId(0), FrameValueKind.INT, 0)
        val two = instruction(ValueId(1), FrameValueKind.INT, 1)
        val sum = instruction(ValueId(2), FrameValueKind.INT, 2)
        val widened = instruction(ValueId(3), FrameValueKind.LONG, 3)
        val analysis = analysisOf(
            definitions = listOf(one, two, sum, widened),
            operations = listOf(
                constant(0, one.id, 1),
                constant(1, two.id, 2),
                operator(2, sum.id, "iadd", JvmComputationalType.INT, one.id, two.id),
                ValueOperation(
                    instructionIndex = 3,
                    instruction = RawConversionInstruction(
                        JvmOpcode("i2l"),
                        JvmComputationalType.INT,
                        JvmComputationalType.LONG,
                    ),
                    inputs = listOf(sum.id),
                    output = widened.id,
                ),
            ),
        )

        val result = propagator.propagate(analysis)

        assertEquals(SsaConstant.IntValue(1), result.analysis.constants[one.id])
        assertEquals(SsaConstant.IntValue(2), result.analysis.constants[two.id])
        assertEquals(SsaConstant.IntValue(3), result.analysis.constants[sum.id])
        assertEquals(SsaConstant.LongValue(3), result.analysis.constants[widened.id])
        assertEquals(4, result.constantValueCount)
        assertEquals(2, result.literalConstantCount)
        assertEquals(2, result.foldedOperationCount)
    }

    @Test
    fun `does not fold integer division by zero`() {
        val value = instruction(ValueId(0), FrameValueKind.INT, 0)
        val zero = instruction(ValueId(1), FrameValueKind.INT, 1)
        val quotient = instruction(ValueId(2), FrameValueKind.INT, 2)
        val analysis = analysisOf(
            definitions = listOf(value, zero, quotient),
            operations = listOf(
                constant(0, value.id, 10),
                constant(1, zero.id, 0),
                operator(2, quotient.id, "idiv", JvmComputationalType.INT, value.id, zero.id),
            ),
        )

        val result = propagator.propagate(analysis)

        assertFalse(quotient.id in result.analysis.constants)
        assertEquals(0, result.foldedOperationCount)
    }

    @Test
    fun `uses JVM NaN result for floating point comparisons`() {
        val nan = instruction(ValueId(0), FrameValueKind.FLOAT, 0)
        val one = instruction(ValueId(1), FrameValueKind.FLOAT, 1)
        val cmpl = instruction(ValueId(2), FrameValueKind.INT, 2)
        val cmpg = instruction(ValueId(3), FrameValueKind.INT, 3)
        val analysis = analysisOf(
            definitions = listOf(nan, one, cmpl, cmpg),
            operations = listOf(
                constant(0, nan.id, Float.NaN),
                constant(1, one.id, 1.0f),
                operator(2, cmpl.id, "fcmpl", JvmComputationalType.FLOAT, nan.id, one.id),
                operator(3, cmpg.id, "fcmpg", JvmComputationalType.FLOAT, nan.id, one.id),
            ),
        )

        val result = propagator.propagate(analysis)

        assertEquals(SsaConstant.IntValue(-1), result.analysis.constants[cmpl.id])
        assertEquals(SsaConstant.IntValue(1), result.analysis.constants[cmpg.id])
    }

    @Test
    fun `resolves phi when all incoming values have the same constant`() {
        val left = instruction(ValueId(0), FrameValueKind.INT, 0)
        val right = instruction(ValueId(1), FrameValueKind.INT, 1)
        val phi = SsaValueDefinition.Phi(
            id = ValueId(2),
            kind = FrameValueKind.INT,
            blockId = BasicBlockId(3),
            location = SsaPhiLocation.Local(0),
            inputs = listOf(SsaPhiInput(left.id), SsaPhiInput(right.id)),
        )
        val analysis = analysisOf(
            definitions = listOf(left, right, phi),
            operations = listOf(constant(0, left.id, 7), constant(1, right.id, 7)),
            phis = listOf(phi),
        )

        val result = propagator.propagate(analysis)

        assertEquals(SsaConstant.IntValue(7), result.analysis.constants[phi.id])
        assertEquals(1, result.constantPhiCount)
    }

    private fun instruction(id: ValueId, kind: FrameValueKind, index: Int) = SsaValueDefinition.Instruction(id, kind, index)

    private fun constant(index: Int, output: ValueId, value: Int) = ValueOperation(
        instructionIndex = index,
        instruction = RawConstantInstruction(JvmOpcode("iconst"), JvmComputationalType.INT, constantDesc(value)),
        inputs = emptyList(),
        output = output,
    )

    private fun constant(index: Int, output: ValueId, value: Float) = ValueOperation(
        instructionIndex = index,
        instruction = RawConstantInstruction(JvmOpcode("fconst"), JvmComputationalType.FLOAT, constantDesc(value)),
        inputs = emptyList(),
        output = output,
    )

    private fun operator(
        index: Int,
        output: ValueId,
        mnemonic: String,
        type: JvmComputationalType,
        left: ValueId,
        right: ValueId,
    ) = ValueOperation(
        instructionIndex = index,
        instruction = RawOperatorInstruction(JvmOpcode(mnemonic), type),
        inputs = listOf(left, right),
        output = output,
    )

    private fun constantDesc(value: Any): ConstantDesc = value as ConstantDesc

    private fun analysisOf(
        definitions: List<SsaValueDefinition>,
        operations: List<ValueOperation>,
        phis: List<SsaValueDefinition.Phi> = emptyList(),
    ) = SsaAnalysis(
        values = definitions.associateByTo(linkedMapOf()) { it.id },
        operations = operations,
        phiNodes = phis.map { definition ->
            SsaPhiNode(
                output = definition.id,
                blockId = definition.blockId,
                location = definition.location,
                inputs = definition.inputs,
            )
        },
        uses = emptyMap(),
        eliminatedLocalInstructionCount = 0,
    )
}
