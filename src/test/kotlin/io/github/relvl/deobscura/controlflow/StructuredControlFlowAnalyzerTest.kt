package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmOpcode
import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawNopInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructuredControlFlowAnalyzerTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    @Test
    fun `recognizes a single-entry if-else diamond`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            expression(branch(0)),
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b1), region.thenBlocks)
        assertEquals(setOf(b2), region.elseBlocks)
        assertEquals(b3, region.join)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    @Test
    fun `recognizes a natural while loop and preserves branch orientation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression(branch(0)),
        )

        val region = assertIs<StructuredRegion.While>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(b2, region.exit)
        assertEquals(setOf(b1), region.latches)
        assertFalse(region.negateCondition)
    }

    @Test
    fun `negates while condition when conditional edge exits the loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression(branch(0)),
        )

        val region = assertIs<StructuredRegion.While>(result.regions.single())
        assertTrue(region.negateCondition)
    }


    @Test
    fun `folds boolean materialization diamond into consuming condition`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
        )
        val condition = ValueId(0)
        val trueValue = ValueId(1)
        val falseValue = ValueId(2)
        val phiValue = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                condition to ExpressionValue(
                    condition,
                    JvmValueType.of(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
                trueValue to ExpressionValue(
                    trueValue,
                    JvmValueType.of(JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(1)),
                    instructionIndices = listOf(1),
                ),
                falseValue to ExpressionValue(
                    falseValue,
                    JvmValueType.of(JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(0)),
                    instructionIndices = listOf(2),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    JvmValueType.of(JvmComputationalType.INT),
                    ExpressionNode.Phi(
                        b3,
                        io.github.relvl.deobscura.analysis.SsaPhiLocation.Stack(0),
                        listOf(
                            io.github.relvl.deobscura.analysis.SsaPhiInput(trueValue, b1),
                            io.github.relvl.deobscura.analysis.SsaPhiInput(falseValue, b2),
                        ),
                    ),
                ),
            ),
            statements = listOf(
                ExpressionStatement.Branch(0, BranchCondition(ComparisonOperator.NE, condition, BranchOperand.Zero)),
                ExpressionStatement.Branch(3, BranchCondition(ComparisonOperator.EQ, phiValue, BranchOperand.Zero)),
            ),
            materialization = ExpressionMaterialization(
                inlineValues = setOf(trueValue, falseValue),
                booleanValues = setOf(phiValue),
            ),
        )

        val result = analyzer.analyze(
            graph(blocks(6), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4, b5), edges, b0),
            expression,
        )

        val fold = result.booleanConditionFolds.single()
        assertEquals(b0, fold.producerHeader)
        assertEquals(b3, fold.consumerHeader)
        assertEquals(phiValue, fold.phiValue)
        assertEquals(ComparisonOperator.EQ, fold.condition.operator)
        assertTrue(result.regions.none { it.header == b0 })
        val consumer = assertIs<StructuredRegion.If>(result.regions.single { it.header == b3 })
        assertEquals(ComparisonOperator.EQ, consumer.condition.operator)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    @Test
    fun `records why a conditional remains block based`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression(branch(0)),
        )

        val diagnostic = result.unstructured.single()
        assertEquals(b0, diagnostic.header)
        assertEquals(UnstructuredControlFlowKind.CONDITIONAL, diagnostic.kind)
        assertEquals(UnstructuredControlFlowReason.NO_COMMON_POST_DOMINATOR, diagnostic.reason)
    }

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc

    private fun expression(branch: ExpressionStatement.Branch): ExpressionAnalysis {
        val condition = ValueId(0)
        return ExpressionAnalysis(
            values = mapOf(
                condition to ExpressionValue(
                    condition,
                    JvmValueType.of(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
            ),
            statements = listOf(branch),
        )
    }

    private fun branch(instructionIndex: Int): ExpressionStatement.Branch = ExpressionStatement.Branch(
        instructionIndex,
        BranchCondition(ComparisonOperator.NE, ValueId(0), BranchOperand.Zero),
    )

    private fun blocks(count: Int): List<BasicBlock> = List(count) { index ->
        BasicBlock(BasicBlockId(index), index, index + 1, emptyList(), emptyList())
    }

    private fun graph(blocks: List<BasicBlock>, edges: List<ControlFlowEdge>) = ControlFlowGraph(
        RawCode(null, null, null, List(blocks.size) { RawNopInstruction(JvmOpcode("nop")) }, emptyList(), emptyList(), emptyList()),
        blocks,
        edges,
        blocks.firstOrNull()?.id,
    )

    private fun edge(from: BasicBlockId, to: BasicBlockId, kind: ControlFlowEdgeKind) =
        ControlFlowEdge(from, to, kind)
}
