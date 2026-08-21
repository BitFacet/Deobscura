package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmOpcode
import io.github.relvl.deobscura.raw.RawConstantInstruction
import io.github.relvl.deobscura.raw.RawOperatorInstruction
import java.lang.constant.ConstantDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsaDeadValueEliminatorTest {
    private val eliminator = SsaDeadValueEliminator()

    @Test
    fun `removes a transitively dead pure expression tree`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val sum = ValueId(2)
        val analysis = analysis(
            definitions = listOf(
                instructionValue(left, 0),
                instructionValue(right, 1),
                instructionValue(sum, 2),
            ),
            operations = listOf(
                constantOperation(0, left, 10),
                constantOperation(1, right, 20),
                operatorOperation(2, "iadd", listOf(left, right), sum),
            ),
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(3, result.removedOperationCount)
        assertEquals(3, result.removedValueCount)
        assertTrue(result.analysis.operations.isEmpty())
        assertTrue(result.analysis.values.isEmpty())
    }

    @Test
    fun `retains integer division because evaluating it may throw`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val quotient = ValueId(2)
        val analysis = analysis(
            definitions = listOf(
                instructionValue(left, 0),
                instructionValue(right, 1),
                instructionValue(quotient, 2),
            ),
            operations = listOf(
                constantOperation(0, left, 10),
                constantOperation(1, right, 0),
                operatorOperation(2, "idiv", listOf(left, right), quotient),
            ),
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(0, result.removedOperationCount)
        assertEquals(0, result.removedValueCount)
        assertEquals(3, result.analysis.operations.size)
    }

    @Test
    fun `removes floating point division because division by zero does not throw`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val quotient = ValueId(2)
        val analysis = analysis(
            definitions = listOf(
                instructionValue(left, 0, FrameValueKind.FLOAT),
                instructionValue(right, 1, FrameValueKind.FLOAT),
                instructionValue(quotient, 2, FrameValueKind.FLOAT),
            ),
            operations = listOf(
                floatConstantOperation(0, left, 10.0f),
                floatConstantOperation(1, right, 0.0f),
                operatorOperation(2, "fdiv", listOf(left, right), quotient, JvmComputationalType.FLOAT),
            ),
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(3, result.removedOperationCount)
        assertTrue(result.analysis.values.isEmpty())
    }

    @Test
    fun `removes unused phi and then its now unused inputs`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val phi = ValueId(2)
        val phiDefinition = SsaValueDefinition.Phi(
            phi,
            FrameValueKind.INT,
            BasicBlockId(3),
            SsaPhiLocation.Local(0),
            listOf(SsaPhiInput(left), SsaPhiInput(right)),
        )
        val analysis = SsaAnalysis(
            values = linkedMapOf(
                left to instructionValue(left, 0),
                right to instructionValue(right, 1),
                phi to phiDefinition,
            ),
            operations = listOf(
                constantOperation(0, left, 1),
                constantOperation(1, right, 2),
            ),
            phiNodes = listOf(
                SsaPhiNode(phi, BasicBlockId(3), SsaPhiLocation.Local(0), listOf(SsaPhiInput(left), SsaPhiInput(right))),
            ),
            uses = emptyMap(),
            constants = mapOf(left to SsaConstant.IntValue(1), phi to SsaConstant.IntValue(1)),
            eliminatedLocalInstructionCount = 0,
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(2, result.removedOperationCount)
        assertEquals(3, result.removedValueCount)
        assertEquals(1, result.removedPhiNodeCount)
        assertTrue(result.analysis.phiNodes.isEmpty())
        assertTrue(result.analysis.constants.isEmpty())
    }

    @Test
    fun `removes a dead self-referential phi cycle`() {
        val seed = ValueId(0)
        val phi = ValueId(1)
        val phiDefinition = SsaValueDefinition.Phi(
            phi,
            FrameValueKind.INT,
            BasicBlockId(1),
            SsaPhiLocation.Local(0),
            listOf(SsaPhiInput(seed), SsaPhiInput(phi)),
        )
        val analysis = SsaAnalysis(
            values = linkedMapOf(
                seed to instructionValue(seed, 0),
                phi to phiDefinition,
            ),
            operations = listOf(constantOperation(0, seed, 1)),
            phiNodes = listOf(
                SsaPhiNode(phi, BasicBlockId(1), SsaPhiLocation.Local(0), listOf(SsaPhiInput(seed), SsaPhiInput(phi))),
            ),
            uses = emptyMap(),
            eliminatedLocalInstructionCount = 0,
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(1, result.removedOperationCount)
        assertEquals(2, result.removedValueCount)
        assertEquals(1, result.removedPhiNodeCount)
        assertTrue(result.analysis.values.isEmpty())
    }

    @Test
    fun `keeps inputs alive when unused result belongs to an unclassified operator`() {
        val input = ValueId(0)
        val output = ValueId(1)
        val analysis = analysis(
            definitions = listOf(instructionValue(input, 0), instructionValue(output, 1)),
            operations = listOf(
                constantOperation(0, input, 1),
                operatorOperation(1, "future_opcode", listOf(input), output),
            ),
        )

        val result = eliminator.eliminate(analysis)

        assertEquals(0, result.removedOperationCount)
        assertTrue(input in result.analysis.values)
        assertTrue(output in result.analysis.values)
        assertFalse(result.analysis.operations.isEmpty())
    }

    private fun analysis(
        definitions: List<SsaValueDefinition>,
        operations: List<ValueOperation>,
    ) = SsaAnalysis(
        values = definitions.associateByTo(linkedMapOf()) { it.id },
        operations = operations,
        phiNodes = emptyList(),
        uses = emptyMap(),
        eliminatedLocalInstructionCount = 0,
    )

    private fun instructionValue(
        id: ValueId,
        instructionIndex: Int,
        kind: FrameValueKind = FrameValueKind.INT,
    ) = SsaValueDefinition.Instruction(id, kind, instructionIndex)

    private fun constantOperation(index: Int, output: ValueId, value: Int) = ValueOperation(
        instructionIndex = index,
        instruction = RawConstantInstruction(JvmOpcode("iconst"), JvmComputationalType.INT, constantDesc(value)),
        inputs = emptyList(),
        output = output,
    )

    private fun floatConstantOperation(index: Int, output: ValueId, value: Float) = ValueOperation(
        instructionIndex = index,
        instruction = RawConstantInstruction(JvmOpcode("fconst"), JvmComputationalType.FLOAT, constantDesc(value)),
        inputs = emptyList(),
        output = output,
    )

    private fun operatorOperation(
        index: Int,
        mnemonic: String,
        inputs: List<ValueId>,
        output: ValueId,
        type: JvmComputationalType = JvmComputationalType.INT,
    ) = ValueOperation(
        instructionIndex = index,
        instruction = RawOperatorInstruction(JvmOpcode(mnemonic), type),
        inputs = inputs,
        output = output,
    )

    private fun constantDesc(value: Any): ConstantDesc = value as ConstantDesc
}
