package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmOpcode
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawLabelId
import io.github.relvl.deobscura.raw.RawReturnInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsaControlFlowCanonicalizerTest {
    private val canonicalizer = SsaControlFlowCanonicalizer()

    @Test
    fun `removes direct gotos and bypasses an empty intermediate block`() {
        val firstGoto = goto(1)
        val secondGoto = goto(2)
        val returnInstruction = returnVoid()
        val graph = graph(
            instructions = listOf(firstGoto, secondGoto, returnInstruction),
            blocks = listOf(block(0, 0, 1), block(1, 1, 2), block(2, 2, 3)),
            edges = listOf(edge(0, 1, ControlFlowEdgeKind.JUMP), edge(1, 2, ControlFlowEdgeKind.JUMP)),
        )
        val analysis = analysis(
            operations = listOf(
                ValueOperation(0, firstGoto, emptyList()),
                ValueOperation(1, secondGoto, emptyList()),
                ValueOperation(2, returnInstruction, emptyList()),
            ),
        )

        val result = canonicalizer.canonicalize(graph, SsaControlFlowGraph.from(graph), analysis)

        assertEquals(2, result.removedGotoOperationCount)
        assertEquals(1, result.removedPassthroughBlockCount)
        assertEquals(setOf(BasicBlockId(0), BasicBlockId(2)), result.controlFlow.blocks)
        assertEquals(listOf(edge(0, 2, ControlFlowEdgeKind.JUMP)), result.controlFlow.edges)
        assertEquals(listOf(2), result.analysis.operations.map { it.instructionIndex })
    }

    @Test
    fun `removes a resolved conditional terminator from a single-edge control-flow view`() {
        val condition = ValueId(0)
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val returnInstruction = returnVoid()
        val graph = graph(
            instructions = listOf(branch, returnInstruction, returnInstruction),
            blocks = listOf(block(0, 0, 1), block(1, 1, 2), block(2, 2, 3)),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH),
            ),
        )
        val controlFlow = SsaControlFlowGraph(
            blocks = linkedSetOf(BasicBlockId(0), BasicBlockId(2)),
            edges = listOf(edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH)),
            entryBlock = BasicBlockId(0),
        )
        val analysis = analysis(
            values = mapOf(condition to SsaValueDefinition.Root(condition, FrameValueKind.INT, ValueOrigin.Parameter(0))),
            operations = listOf(
                ValueOperation(0, branch, listOf(condition)),
                ValueOperation(2, returnInstruction, emptyList()),
            ),
        )

        val result = canonicalizer.canonicalize(graph, controlFlow, analysis)

        assertEquals(1, result.collapsedControlFlowOperationCount)
        assertFalse(result.analysis.operations.any { it.instructionIndex == 0 })
        assertEquals(listOf(edge(0, 2, ControlFlowEdgeKind.FALLTHROUGH)), result.controlFlow.edges)
    }

    @Test
    fun `collapses duplicate branch edges and keeps phi inputs aligned`() {
        val condition = ValueId(0)
        val incomingValue = ValueId(1)
        val phiValue = ValueId(2)
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        val returnInstruction = returnVoid()
        val graph = graph(
            instructions = listOf(branch, returnInstruction),
            blocks = listOf(block(0, 0, 1), block(1, 1, 2)),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.CONDITIONAL),
                edge(0, 1, ControlFlowEdgeKind.FALLTHROUGH),
            ),
        )
        val phi = SsaPhiNode(phiValue, BasicBlockId(1), SsaPhiLocation.Local(0), listOf(SsaPhiInput(incomingValue, BasicBlockId(0))))
        val analysis = analysis(
            values = linkedMapOf(
                condition to SsaValueDefinition.Root(condition, FrameValueKind.INT, ValueOrigin.Parameter(0)),
                incomingValue to SsaValueDefinition.Root(incomingValue, FrameValueKind.INT, ValueOrigin.Parameter(1)),
                phiValue to SsaValueDefinition.Phi(
                    phiValue,
                    FrameValueKind.INT,
                    BasicBlockId(1),
                    SsaPhiLocation.Local(0),
                    phi.inputs,
                ),
            ),
            operations = listOf(
                ValueOperation(0, branch, listOf(condition)),
                ValueOperation(1, returnInstruction, emptyList()),
            ),
            phiNodes = listOf(phi),
        )

        val result = canonicalizer.canonicalize(graph, SsaControlFlowGraph.from(graph), analysis)

        assertEquals(1, result.collapsedEdgeCount)
        assertEquals(listOf(edge(0, 1, ControlFlowEdgeKind.FALLTHROUGH)), result.controlFlow.edges)
        assertEquals(listOf(SsaPhiInput(incomingValue, BasicBlockId(0))), result.analysis.phiNodes.single().inputs)
        assertEquals(
            listOf(SsaPhiInput(incomingValue, BasicBlockId(0))),
            (result.analysis.values.getValue(phiValue) as SsaValueDefinition.Phi).inputs,
        )
    }


    @Test
    fun `bypassing passthrough block rewrites phi predecessor identity`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val phiValue = ValueId(2)
        val returnInstruction = returnVoid()
        val graph = graph(
            instructions = listOf(returnInstruction, returnInstruction, returnInstruction, returnInstruction, returnInstruction),
            blocks = listOf(
                block(0, 0, 1),
                block(1, 1, 2),
                block(2, 2, 3),
                block(3, 3, 4),
                block(4, 4, 5),
            ),
            edges = listOf(
                edge(0, 2, ControlFlowEdgeKind.JUMP),
                edge(1, 2, ControlFlowEdgeKind.JUMP),
                edge(2, 3, ControlFlowEdgeKind.JUMP),
                edge(4, 3, ControlFlowEdgeKind.JUMP),
            ),
        )
        val phi = SsaPhiNode(
            phiValue,
            BasicBlockId(3),
            SsaPhiLocation.Local(0),
            listOf(
                SsaPhiInput(left, BasicBlockId(2)),
                SsaPhiInput(right, BasicBlockId(4)),
            ),
        )
        val analysis = analysis(
            values = linkedMapOf(
                left to SsaValueDefinition.Root(left, FrameValueKind.INT, ValueOrigin.Parameter(0)),
                right to SsaValueDefinition.Root(right, FrameValueKind.INT, ValueOrigin.Parameter(1)),
                phiValue to SsaValueDefinition.Phi(
                    phiValue,
                    FrameValueKind.INT,
                    BasicBlockId(3),
                    SsaPhiLocation.Local(0),
                    phi.inputs,
                ),
            ),
            operations = listOf(
                ValueOperation(0, returnInstruction, emptyList()),
                ValueOperation(1, returnInstruction, emptyList()),
                ValueOperation(3, returnInstruction, emptyList()),
                ValueOperation(4, returnInstruction, emptyList()),
            ),
            phiNodes = listOf(phi),
        )

        val result = canonicalizer.canonicalize(graph, SsaControlFlowGraph.from(graph), analysis)

        assertFalse(BasicBlockId(2) in result.controlFlow.blocks)
        assertEquals(
            listOf(
                SsaPhiInput(right, BasicBlockId(4)),
                SsaPhiInput(left, BasicBlockId(0)),
                SsaPhiInput(left, BasicBlockId(1)),
            ),
            result.analysis.phiNodes.single().inputs,
        )
    }

    @Test
    fun `does not bypass a block adjacent to exceptional flow`() {
        val returnInstruction = returnVoid()
        val graph = graph(
            instructions = listOf(returnInstruction, returnInstruction, returnInstruction),
            blocks = listOf(block(0, 0, 1), block(1, 1, 2), block(2, 2, 3)),
            edges = listOf(
                edge(0, 1, ControlFlowEdgeKind.FALLTHROUGH),
                edge(1, 2, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(BasicBlockId(1), BasicBlockId(2), ControlFlowEdgeKind.EXCEPTION, catchType = "java/lang/Exception"),
            ),
        )
        val analysis = analysis(
            operations = listOf(
                ValueOperation(0, returnInstruction, emptyList()),
                ValueOperation(2, returnInstruction, emptyList()),
            ),
        )

        val result = canonicalizer.canonicalize(graph, SsaControlFlowGraph.from(graph), analysis)

        assertEquals(0, result.removedPassthroughBlockCount)
        assertTrue(BasicBlockId(1) in result.controlFlow.blocks)
    }

    private fun analysis(
        values: Map<ValueId, SsaValueDefinition> = emptyMap(),
        operations: List<ValueOperation>,
        phiNodes: List<SsaPhiNode> = emptyList(),
    ) = SsaAnalysis(
        values = values,
        operations = operations,
        phiNodes = phiNodes,
        uses = rebuildSsaUses(values, operations, phiNodes, "Test"),
        eliminatedLocalInstructionCount = 0,
    )

    private fun graph(
        instructions: List<io.github.relvl.deobscura.raw.RawInstruction>,
        blocks: List<BasicBlock>,
        edges: List<ControlFlowEdge>,
    ) = ControlFlowGraph(
        code = RawCode(null, null, null, instructions, emptyList(), emptyList(), emptyList()),
        blocks = blocks,
        edges = edges,
        entryBlock = BasicBlockId(0),
    )

    private fun block(id: Int, start: Int, end: Int) =
        BasicBlock(BasicBlockId(id), start, end, emptyList(), emptyList())

    private fun edge(from: Int, to: Int, kind: ControlFlowEdgeKind) =
        ControlFlowEdge(BasicBlockId(from), BasicBlockId(to), kind)

    private fun goto(target: Int) = RawBranchInstruction(JvmOpcode("goto"), RawLabelId(target))

    private fun returnVoid() = RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID)
}
