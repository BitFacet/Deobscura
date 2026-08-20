package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.controlflow.*
import io.github.relvl.deobscura.expression.BranchCondition
import io.github.relvl.deobscura.expression.BranchOperand
import io.github.relvl.deobscura.expression.ComparisonOperator
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceStructureBuilderTest {
    private val builder = SourceStructureBuilder()

    @Test
    fun `nests proven regions and keeps unresolved header local to its source scope`() {
        val graph = graph(6)
        val flow = flow(6)
        val condition = StructuredCondition.Atomic(
            BranchCondition(ComparisonOperator.NE, ValueId(0), BranchOperand.Zero),
        )
        val loop = StructuredRegion.While(
            header = BasicBlockId(0),
            condition = condition,
            negateCondition = false,
            bodyEntry = BasicBlockId(1),
            bodyBlocks = setOf(BasicBlockId(1), BasicBlockId(2), BasicBlockId(3), BasicBlockId(4)),
            exit = BasicBlockId(5),
            latches = setOf(BasicBlockId(4)),
        )
        val nestedTry = StructuredRegion.TryCatch(
            header = BasicBlockId(1),
            tryBlocks = setOf(BasicBlockId(1), BasicBlockId(2)),
            catches = listOf(StructuredCatch(listOf("java/lang/Exception"), BasicBlockId(3), setOf(BasicBlockId(3)))),
            continuation = BasicBlockId(4),
            protectedStartInstructionIndex = 1,
            protectedEndInstructionIndexExclusive = 3,
        )
        val unresolved = UnstructuredControlFlowDiagnostic(
            header = BasicBlockId(2),
            kind = UnstructuredControlFlowKind.CONDITIONAL,
            reason = UnstructuredControlFlowReason.NO_COMMON_POST_DOMINATOR,
        )

        val result = builder.build(
            graph,
            flow,
            StructuredControlFlowAnalysis(
                regions = listOf(loop, nestedTry),
                conditionalBranchCount = 2,
                switchCount = 0,
                unstructured = listOf(unresolved),
            ),
        )

        val loopNode = assertIs<SourceNode.Structured>(result.root.nodes.first())
        assertEquals(loop, loopNode.region)
        val loopBody = loopNode.parts.single { it.kind == SourceRegionPartKind.LOOP_BODY }.body
        val tryNode = assertIs<SourceNode.Structured>(loopBody.nodes.first())
        assertEquals(nestedTry, tryNode.region)
        val tryBody = tryNode.parts.single { it.kind == SourceRegionPartKind.TRY_BODY }.body
        val unresolvedNode = assertIs<SourceNode.Unstructured>(tryBody.nodes.single { it.provenance.blocks == setOf(BasicBlockId(2)) })
        assertEquals(listOf(unresolved), unresolvedNode.diagnostics)
        assertEquals((0..5).map(::BasicBlockId).toSet(), result.accountedBlocks)
    }

    @Test
    fun `suppresses finally normal-copy nested regions without losing their physical blocks`() {
        val graph = graph(9)
        val flow = flow(9)
        val outer = StructuredRegion.TryFinally(
            header = BasicBlockId(0),
            tryBlocks = setOf(BasicBlockId(0), BasicBlockId(1), BasicBlockId(2)),
            handlerEntry = BasicBlockId(5),
            handlerBlocks = setOf(BasicBlockId(5), BasicBlockId(6)),
            finallyBodyInstructionRanges = listOf(5..5),
            normalCopyInstructionIndices = listOf(3..3),
            normalCopyBlocks = setOf(BasicBlockId(3)),
            continuation = BasicBlockId(7),
            protectedStartInstructionIndex = 0,
            protectedEndInstructionIndexExclusive = 3,
        )
        val duplicatedNestedTry = StructuredRegion.TryCatch(
            header = BasicBlockId(3),
            tryBlocks = setOf(BasicBlockId(3)),
            catches = listOf(StructuredCatch(listOf("java/lang/Throwable"), BasicBlockId(4), setOf(BasicBlockId(4)))),
            continuation = BasicBlockId(7),
            protectedStartInstructionIndex = 3,
            protectedEndInstructionIndexExclusive = 4,
        )

        val result = builder.build(
            graph,
            flow,
            StructuredControlFlowAnalysis(
                regions = listOf(outer, duplicatedNestedTry),
                conditionalBranchCount = 0,
                switchCount = 0,
            ),
        )

        val renderedRegions = collectStructuredRegions(result.root)
        assertTrue(outer in renderedRegions)
        assertTrue(duplicatedNestedTry !in renderedRegions)
        assertTrue(result.consumptions.any {
            it.block == BasicBlockId(4) && it.reason == SourceConsumptionReason.FINALLY_NORMAL_COPY
        })
        assertTrue(result.consumptions.any {
            it.block == BasicBlockId(6) && it.reason == SourceConsumptionReason.FINALLY_EXCEPTIONAL_SCAFFOLDING
        })
        assertEquals((0..8).map(::BasicBlockId).toSet(), result.accountedBlocks)
    }

    @Test
    fun `retains ambiguous same-header projection as explicit fallback without failing`() {
        val graph = graph(5)
        val flow = flow(5)
        val condition = StructuredCondition.Atomic(
            BranchCondition(ComparisonOperator.NE, ValueId(0), BranchOperand.Zero),
        )
        val first = StructuredRegion.If(
            header = BasicBlockId(0),
            condition = condition,
            thenEntry = BasicBlockId(1),
            thenBlocks = setOf(BasicBlockId(1)),
            elseEntry = null,
            elseBlocks = emptySet(),
            continuation = BasicBlockId(4),
        )
        val second = StructuredRegion.If(
            header = BasicBlockId(0),
            condition = condition,
            thenEntry = BasicBlockId(2),
            thenBlocks = setOf(BasicBlockId(2), BasicBlockId(3)),
            elseEntry = null,
            elseBlocks = emptySet(),
            continuation = BasicBlockId(4),
        )

        val result = builder.build(
            graph,
            flow,
            StructuredControlFlowAnalysis(
                regions = listOf(first, second),
                conditionalBranchCount = 2,
                switchCount = 0,
            ),
        )

        assertTrue(result.issues.isNotEmpty())
        assertTrue(result.root.nodes.any { it is SourceNode.ProjectionFallback })
        assertEquals((0..4).map(::BasicBlockId).toSet(), result.accountedBlocks)
    }

    private fun graph(count: Int): ControlFlowGraph {
        val blocks = List(count) { BasicBlock(BasicBlockId(it), it, it + 1, emptyList(), emptyList()) }
        val edges = (0 until count - 1).map {
            ControlFlowEdge(BasicBlockId(it), BasicBlockId(it + 1), ControlFlowEdgeKind.FALLTHROUGH)
        }
        return ControlFlowGraph(
            code = RawCode(
                maxStack = null,
                maxLocals = null,
                bytecodeLength = null,
                instructions = List(count) { RawNopInstruction(JvmOpcode("nop")) },
                labels = emptyList(),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
            blocks = blocks,
            edges = edges,
            entryBlock = blocks.firstOrNull()?.id,
        )
    }

    private fun flow(count: Int): SsaControlFlowGraph {
        val blocks = (0 until count).mapTo(linkedSetOf(), ::BasicBlockId)
        val edges = (0 until count - 1).map {
            ControlFlowEdge(BasicBlockId(it), BasicBlockId(it + 1), ControlFlowEdgeKind.FALLTHROUGH)
        }
        return SsaControlFlowGraph(blocks, edges, blocks.firstOrNull())
    }

    private fun collectStructuredRegions(block: SourceBlock): List<StructuredRegion> = buildList {
        block.nodes.forEach { node ->
            if (node is SourceNode.Structured) {
                add(node.region)
                node.parts.forEach { addAll(collectStructuredRegions(it.body)) }
            }
        }
    }
}
