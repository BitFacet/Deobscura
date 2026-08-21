package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmOpcode
import io.github.relvl.deobscura.raw.RawOperatorInstruction
import kotlin.test.*

class SsaSimplifierTest {
    private val simplifier = SsaSimplifier()

    @Test
    fun `returns same analysis when there are no aliases to propagate`() {
        val left = root(ValueId(0), 0)
        val right = root(ValueId(1), 1)
        val phi = phi(ValueId(2), listOf(left.id, right.id))
        val analysis = analysisOf(listOf(left, right, phi), listOf(phi))

        val result = simplifier.simplify(analysis)

        assertSame(analysis, result.analysis)
        assertEquals(0, result.propagatedAliasCount)
        assertEquals(0, result.removedPhiCount)
    }

    @Test
    fun `removes trivial phi and rewrites operation use to canonical value`() {
        val root = root(ValueId(0), 0)
        val phi = phi(ValueId(1), listOf(root.id, root.id))
        val output = SsaValueDefinition.Instruction(ValueId(2), FrameValueKind.INT, 10)
        val operation = ValueOperation(
            instructionIndex = 10,
            instruction = RawOperatorInstruction(JvmOpcode("test"), JvmComputationalType.INT),
            inputs = listOf(phi.id),
            output = output.id,
        )
        val analysis = analysisOf(listOf(root, phi, output), listOf(phi), listOf(operation))

        val result = simplifier.simplify(analysis)
        val simplified = result.analysis

        assertEquals(1, result.propagatedAliasCount)
        assertEquals(1, result.removedPhiCount)
        assertFalse(phi.id in simplified.values)
        assertTrue(simplified.phiNodes.isEmpty())
        assertEquals(listOf(root.id), simplified.operations.single().inputs)
        assertEquals(
            listOf(SsaValueUse.Operation(10, 0)),
            simplified.uses[root.id],
        )
    }

    @Test
    fun `collapses chained phi aliases to original definition`() {
        val root = root(ValueId(0), 0)
        val first = phi(ValueId(1), listOf(root.id, root.id))
        val second = phi(ValueId(2), listOf(first.id, first.id))
        val output = SsaValueDefinition.Instruction(ValueId(3), FrameValueKind.INT, 11)
        val operation = ValueOperation(
            instructionIndex = 11,
            instruction = RawOperatorInstruction(JvmOpcode("test"), JvmComputationalType.INT),
            inputs = listOf(second.id),
            output = output.id,
        )
        val analysis = analysisOf(listOf(root, first, second, output), listOf(first, second), listOf(operation))

        val result = simplifier.simplify(analysis)

        assertEquals(2, result.propagatedAliasCount)
        assertEquals(2, result.removedPhiCount)
        assertEquals(listOf(root.id), result.analysis.operations.single().inputs)
        assertEquals(setOf(root.id, output.id), result.analysis.values.keys)
    }

    @Test
    fun `self-referential phi with one external value simplifies to external value`() {
        val root = root(ValueId(0), 0)
        val loopPhi = phi(ValueId(1), listOf(ValueId(1), root.id))
        val analysis = analysisOf(listOf(root, loopPhi), listOf(loopPhi))

        val result = simplifier.simplify(analysis)

        assertEquals(1, result.removedPhiCount)
        assertEquals(setOf(root.id), result.analysis.values.keys)
    }

    @Test
    fun `canonicalizes constant facts when trivial phi becomes alias`() {
        val root = root(ValueId(0), 0)
        val phi = phi(ValueId(1), listOf(root.id, root.id))
        val analysis = analysisOf(listOf(root, phi), listOf(phi)).copy(
            constants = mapOf(
                root.id to SsaConstant.IntValue(42),
                phi.id to SsaConstant.IntValue(42),
            ),
        )

        val result = simplifier.simplify(analysis)

        assertEquals(mapOf(root.id to SsaConstant.IntValue(42)), result.analysis.constants)
    }

    private fun root(id: ValueId, parameter: Int) = SsaValueDefinition.Root(id, FrameValueKind.INT, ValueOrigin.Parameter(parameter))

    private fun phi(id: ValueId, inputs: List<ValueId>): SsaValueDefinition.Phi = SsaValueDefinition.Phi(
        id = id,
        kind = FrameValueKind.INT,
        blockId = BasicBlockId(id.value + 1),
        location = SsaPhiLocation.Local(0),
        inputs = inputs.map { SsaPhiInput(it) },
    )

    private fun analysisOf(
        definitions: List<SsaValueDefinition>,
        phis: List<SsaValueDefinition.Phi>,
        operations: List<ValueOperation> = emptyList(),
    ): SsaAnalysis = SsaAnalysis(
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
