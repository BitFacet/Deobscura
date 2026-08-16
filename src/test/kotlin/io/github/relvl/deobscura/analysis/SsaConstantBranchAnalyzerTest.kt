package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SsaConstantBranchAnalyzerTest {
    private val analyzer = SsaConstantBranchAnalyzer()

    @Test
    fun `resolves constant conditional branch and makes untaken block unreachable`() {
        val condition = ValueId(0)
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val graph = graph(
            instructions = listOf(branch, nop(), nop()),
            blocks = listOf(block(0, 0, 1, successors = listOf(1, 2)), block(1, 1, 2, predecessors = listOf(0)), block(2, 2, 3, predecessors = listOf(0))),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
            ),
        )
        val analysis = analysis(
            operation = ValueOperation(0, branch, listOf(condition)),
            constants = mapOf(condition to SsaConstant.IntValue(0)),
        )

        val result = analyzer.analyze(graph, analysis)

        assertEquals(1, result.resolvedConditionalBranchCount)
        assertEquals(1, result.eliminatedEdgeCount)
        assertEquals(1, result.newlyUnreachableBlockCount)
        assertTrue(result.reachableBlocks.contains(BasicBlockId(1)))
        assertTrue(!result.reachableBlocks.contains(BasicBlockId(2)))
    }

    @Test
    fun `resolves switch to matching case`() {
        val selector = ValueId(0)
        val switch = RawSwitchInstruction(
            opcode = JvmOpcode("lookupswitch"),
            defaultTarget = RawLabelId(3),
            cases = listOf(RawSwitchCase(7, RawLabelId(1)), RawSwitchCase(9, RawLabelId(2))),
        )
        val graph = graph(
            instructions = listOf(switch, nop(), nop(), nop()),
            blocks = listOf(
                block(0, 0, 1, successors = listOf(1, 2, 3)),
                block(1, 1, 2, predecessors = listOf(0)),
                block(2, 2, 3, predecessors = listOf(0)),
                block(3, 3, 4, predecessors = listOf(0)),
            ),
            edges = listOf(
                ControlFlowEdge(BasicBlockId(0), BasicBlockId(3), ControlFlowEdgeKind.SWITCH),
                ControlFlowEdge(BasicBlockId(0), BasicBlockId(1), ControlFlowEdgeKind.SWITCH, switchValue = 7),
                ControlFlowEdge(BasicBlockId(0), BasicBlockId(2), ControlFlowEdgeKind.SWITCH, switchValue = 9),
            ),
        )
        val analysis = analysis(
            operation = ValueOperation(0, switch, listOf(selector)),
            constants = mapOf(selector to SsaConstant.IntValue(9)),
        )

        val result = analyzer.analyze(graph, analysis)

        assertEquals(1, result.resolvedSwitchCount)
        assertEquals(2, result.eliminatedEdgeCount)
        assertEquals(2, result.newlyUnreachableBlockCount)
        assertTrue(result.reachableBlocks.contains(BasicBlockId(2)))
    }


    @Test
    fun `keeps previously eliminated edges pruned on later analysis`() {
        val condition = ValueId(0)
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val graph = graph(
            instructions = listOf(branch, nop(), nop()),
            blocks = listOf(block(0, 0, 1, successors = listOf(1, 2)), block(1, 1, 2, predecessors = listOf(0)), block(2, 2, 3, predecessors = listOf(0))),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
            ),
        )
        val analysis = analysis(
            operation = ValueOperation(0, branch, listOf(condition)),
            constants = mapOf(condition to SsaConstant.IntValue(0)),
        )
        val first = analyzer.analyze(graph, analysis)
        val second = analyzer.analyze(graph, analysis, first.eliminatedEdges)

        assertEquals(1, first.newlyEliminatedEdgeCount)
        assertEquals(0, second.newlyEliminatedEdgeCount)
        assertEquals(first.eliminatedEdges, second.eliminatedEdges)
        assertEquals(first.reachableBlocks, second.reachableBlocks)
        assertEquals(0, second.newlyUnreachableBlockCount)
    }

    private fun analysis(operation: ValueOperation, constants: Map<ValueId, SsaConstant>) = SsaAnalysis(
        values = emptyMap(),
        operations = listOf(operation),
        phiNodes = emptyList(),
        uses = emptyMap(),
        constants = constants,
        eliminatedLocalInstructionCount = 0,
    )

    private fun graph(instructions: List<RawInstruction>, blocks: List<BasicBlock>, edges: List<ControlFlowEdge>) =
        ControlFlowGraph(
            code = RawCode(null, null, null, instructions, emptyList(), emptyList(), emptyList()),
            blocks = blocks,
            edges = edges,
            entryBlock = BasicBlockId(0),
        )

    private fun block(id: Int, start: Int, end: Int, predecessors: List<Int> = emptyList(), successors: List<Int> = emptyList()) =
        BasicBlock(BasicBlockId(id), start, end, predecessors.map(::BasicBlockId), successors.map(::BasicBlockId))

    private fun edge(from: Int, to: Int, kind: ControlFlowEdgeKind) =
        ControlFlowEdge(BasicBlockId(from), BasicBlockId(to), kind)

    private fun nop() = RawNopInstruction(JvmOpcode("nop"))
}
