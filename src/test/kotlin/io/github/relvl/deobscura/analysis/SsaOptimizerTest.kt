package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.raw.*
import java.lang.constant.ConstantDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsaOptimizerTest {
    private val optimizer = SsaOptimizer()

    @Test
    fun `iterates when pruning one branch exposes another constant branch`() {
        val condition = ValueId(0)
        val left = ValueId(1)
        val right = ValueId(2)
        val phi = ValueId(3)
        val firstBranch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val secondBranch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(2))
        val instructions = listOf(
            constantInstruction(0),
            firstBranch,
            constantInstruction(0),
            nop(),
            constantInstruction(1),
            nop(),
            secondBranch,
            nop(),
            nop(),
        )
        val graph = ControlFlowGraph(
            code = RawCode(null, null, null, instructions, emptyList(), emptyList(), emptyList()),
            blocks = listOf(
                block(0, 0, 2, successors = listOf(1, 2)),
                block(1, 2, 4, predecessors = listOf(0), successors = listOf(3)),
                block(2, 4, 6, predecessors = listOf(0), successors = listOf(3)),
                block(3, 6, 7, predecessors = listOf(1, 2), successors = listOf(4, 5)),
                block(4, 7, 8, predecessors = listOf(3)),
                block(5, 8, 9, predecessors = listOf(3)),
            ),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
                edge(1, 3, ControlFlowEdgeKind.JUMP),
                edge(2, 3, ControlFlowEdgeKind.JUMP),
                edge(3, 4, ControlFlowEdgeKind.CONDITIONAL),
                edge(3, 5, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = BasicBlockId(0),
        )
        val phiDefinition = SsaValueDefinition.Phi(
            phi,
            FrameValueKind.INT,
            BasicBlockId(3),
            SsaPhiLocation.Local(0),
            listOf(left, right),
        )
        val analysis = SsaAnalysis(
            values = linkedMapOf(
                condition to SsaValueDefinition.Instruction(condition, FrameValueKind.INT, 0),
                left to SsaValueDefinition.Instruction(left, FrameValueKind.INT, 2),
                right to SsaValueDefinition.Instruction(right, FrameValueKind.INT, 4),
                phi to phiDefinition,
            ),
            operations = listOf(
                constantOperation(0, condition, 0),
                ValueOperation(1, firstBranch, listOf(condition)),
                constantOperation(2, left, 0),
                constantOperation(4, right, 1),
                ValueOperation(6, secondBranch, listOf(phi)),
            ),
            phiNodes = listOf(SsaPhiNode(phi, BasicBlockId(3), SsaPhiLocation.Local(0), listOf(left, right))),
            uses = emptyMap(),
            eliminatedLocalInstructionCount = 0,
        )

        val result = optimizer.optimize(graph, analysis)

        assertEquals(3, result.stats.iterationCount)
        assertEquals(2, result.stats.resolvedConditionalBranchCount)
        assertEquals(2, result.eliminatedEdges.size)
        assertEquals(2, result.stats.newlyUnreachableBlockCount)
        assertEquals(1, result.stats.propagatedAliasCount)
        assertFalse(phi in result.analysis.values)
        assertFalse(right in result.analysis.values)
        assertEquals(listOf(left), result.analysis.operations.single { it.instructionIndex == 6 }.inputs)
        assertTrue(result.analysis.constants[left] == SsaConstant.IntValue(0))
    }

    @Test
    fun `fails rather than spinning past iteration guard`() {
        val optimizer = SsaOptimizer(maxIterations = 1)
        val condition = ValueId(0)
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val graph = ControlFlowGraph(
            code = RawCode(null, null, null, listOf(branch, nop(), nop()), emptyList(), emptyList(), emptyList()),
            blocks = listOf(
                block(0, 0, 1, successors = listOf(1, 2)),
                block(1, 1, 2, predecessors = listOf(0)),
                block(2, 2, 3, predecessors = listOf(0)),
            ),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = BasicBlockId(0),
        )
        val analysis = SsaAnalysis(
            values = mapOf(condition to SsaValueDefinition.Instruction(condition, FrameValueKind.INT, 0)),
            operations = listOf(constantOperation(0, condition, 0), ValueOperation(0, branch, listOf(condition))),
            phiNodes = emptyList(),
            uses = emptyMap(),
            eliminatedLocalInstructionCount = 0,
        )

        kotlin.test.assertFailsWith<SsaInconsistencyException> { optimizer.optimize(graph, analysis) }
    }

    private fun constantInstruction(value: Int) =
        RawConstantInstruction(JvmOpcode("iconst"), JvmComputationalType.INT, constantDesc(value))

    private fun constantDesc(value: Any): ConstantDesc = value as ConstantDesc

    private fun constantOperation(index: Int, output: ValueId, value: Int) = ValueOperation(
        instructionIndex = index,
        instruction = constantInstruction(value),
        inputs = emptyList(),
        output = output,
    )

    private fun nop() = RawNopInstruction(JvmOpcode("nop"))

    private fun block(
        id: Int,
        start: Int,
        end: Int,
        predecessors: List<Int> = emptyList(),
        successors: List<Int> = emptyList(),
    ) = BasicBlock(BasicBlockId(id), start, end, predecessors.map(::BasicBlockId), successors.map(::BasicBlockId))

    private fun edge(from: Int, to: Int, kind: ControlFlowEdgeKind) =
        ControlFlowEdge(BasicBlockId(from), BasicBlockId(to), kind)
}
