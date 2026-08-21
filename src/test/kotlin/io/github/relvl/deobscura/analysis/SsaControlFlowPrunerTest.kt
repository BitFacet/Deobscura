package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SsaControlFlowPrunerTest {
    private val pruner = SsaControlFlowPruner()
    private val simplifier = SsaSimplifier()

    @Test
    fun `prunes dead predecessor from phi and simplifier removes resulting trivial phi`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val phi = ValueId(2)
        val result = ValueId(3)
        val graph = ControlFlowGraph(
            code = RawCode(null, null, null, List(4) { RawNopInstruction(JvmOpcode("nop")) }, emptyList(), emptyList(), emptyList()),
            blocks = listOf(
                block(0, 0, 1, successors = listOf(1, 2)),
                block(1, 1, 2, predecessors = listOf(0), successors = listOf(3)),
                block(2, 2, 3, predecessors = listOf(0), successors = listOf(3)),
                block(3, 3, 4, predecessors = listOf(1, 2)),
            ),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
                edge(1, 3, ControlFlowEdgeKind.JUMP),
                edge(2, 3, ControlFlowEdgeKind.JUMP),
            ),
            entryBlock = BasicBlockId(0),
        )
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            left to SsaValueDefinition.Instruction(left, FrameValueKind.INT, 1),
            right to SsaValueDefinition.Instruction(right, FrameValueKind.INT, 2),
            phi to SsaValueDefinition.Phi(
                phi, FrameValueKind.INT, BasicBlockId(3), SsaPhiLocation.Local(0), listOf(SsaPhiInput(left, BasicBlockId(1)), SsaPhiInput(right, BasicBlockId(2)))
            ),
            result to SsaValueDefinition.Instruction(result, FrameValueKind.INT, 3),
        )
        val leftOperation = operation(1, emptyList(), left)
        val rightOperation = operation(2, emptyList(), right)
        val useOperation = operation(3, listOf(phi), result)
        val analysis = SsaAnalysis(
            values = values,
            operations = listOf(leftOperation, rightOperation, useOperation),
            phiNodes = listOf(SsaPhiNode(phi, BasicBlockId(3), SsaPhiLocation.Local(0), listOf(SsaPhiInput(left, BasicBlockId(1)), SsaPhiInput(right, BasicBlockId(2))))),
            uses = emptyMap(),
            constants = mapOf(left to SsaConstant.IntValue(7), right to SsaConstant.IntValue(9)),
            eliminatedLocalInstructionCount = 0,
            localAccesses = listOf(
                SsaLocalAccess(1, 0, SsaLocalAccessKind.WRITE, left),
                SsaLocalAccess(2, 0, SsaLocalAccessKind.WRITE, right),
            ),
        )
        val eliminated = graph.edges.first { it.from == BasicBlockId(0) && it.to == BasicBlockId(2) }
        val branchResult = SsaConstantBranchResult(
            controlFlow = SsaControlFlowGraph.from(graph),
            eliminatedEdges = setOf(eliminated),
            reachableBlocks = setOf(BasicBlockId(0), BasicBlockId(1), BasicBlockId(3)),
            resolvedConditionalBranchCount = 1,
            resolvedSwitchCount = 0,
            newlyUnreachableBlockCount = 1,
        )

        val pruning = pruner.prune(graph, analysis, branchResult)
        val simplified = simplifier.simplify(pruning.analysis)

        assertEquals(1, pruning.removedOperationCount)
        assertEquals(1, pruning.removedPhiInputCount)
        assertEquals(listOf(SsaPhiInput(left, BasicBlockId(1))), pruning.analysis.phiNodes.single().inputs)
        assertEquals(1, simplified.removedPhiCount)
        assertFalse(phi in simplified.analysis.values)
        assertEquals(listOf(left), simplified.analysis.operations.single { it.instructionIndex == 3 }.inputs)
        assertEquals(SsaConstant.IntValue(7), simplified.analysis.constants[left])
        assertFalse(right in simplified.analysis.constants)
        assertEquals(
            listOf(SsaLocalAccess(1, 0, SsaLocalAccessKind.WRITE, left)),
            pruning.analysis.localAccesses,
        )
    }

    private fun operation(index: Int, inputs: List<ValueId>, output: ValueId) = ValueOperation(
        instructionIndex = index,
        instruction = RawOperatorInstruction(JvmOpcode("test"), JvmComputationalType.INT),
        inputs = inputs,
        output = output,
    )

    private fun block(
        id: Int,
        start: Int,
        end: Int,
        predecessors: List<Int> = emptyList(),
        successors: List<Int> = emptyList(),
    ) = BasicBlock(BasicBlockId(id), start, end, predecessors.map(::BasicBlockId), successors.map(::BasicBlockId))

    private fun edge(from: Int, to: Int, kind: ControlFlowEdgeKind) = ControlFlowEdge(BasicBlockId(from), BasicBlockId(to), kind)
}
