package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.*
import java.lang.constant.ConstantDescs
import kotlin.test.*

class StructuredConditionalLoopTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: if (cond) A else B; C
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
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    // Pseudocode: while (cond) { BODY }
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

    // Pseudocode: while (!exitCond) { BODY }
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

    // Pseudocode: if (!cond) { BODY }
    @Test
    fun `normalizes an empty conditional arm into a non-empty then arm`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression(branch(0)),
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single())
        assertEquals(ComparisonOperator.EQ, assertIs<StructuredCondition.Atomic>(region.condition).condition.operator)
        assertEquals(setOf(b1), region.thenBlocks)
        assertTrue(region.elseBlocks.isEmpty())
        assertEquals(b2, region.continuation)
        assertEquals(1, result.emptyArmNormalizationCount)
    }

    // Pseudocode: if (cond) return; BODY
    @Test
    fun `recognizes a return arm without a common post-dominator`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val condition = ValueId(0)
        val expression = ExpressionAnalysis(
            values = mapOf(
                condition to ExpressionValue(
                    condition,
                    JvmValueType.of(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
            ),
            statements = listOf(
                branch(0),
                ExpressionStatement.Return(1, null),
            ),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression,
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single())
        assertEquals(setOf(b1), region.thenBlocks)
        assertEquals(StructuredArmExitKind.RETURN_OR_THROW, region.thenExit?.kind)
        assertEquals(b2, region.continuation)
        assertEquals(1, result.terminalIfRegionCount)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    // Pseudocode: if (!cond) return; BODY
    @Test
    fun `inverts condition when the fallthrough arm terminates`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val condition = ValueId(0)
        val expression = ExpressionAnalysis(
            values = mapOf(
                condition to ExpressionValue(
                    condition,
                    JvmValueType.of(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
            ),
            statements = listOf(
                branch(0),
                ExpressionStatement.Throw(2, condition),
            ),
        )
        val result = analyzer.analyze(
            graph(blocks(3), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            expression,
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single())
        assertEquals(ComparisonOperator.EQ, assertIs<StructuredCondition.Atomic>(region.condition).condition.operator)
        assertEquals(setOf(b2), region.thenBlocks)
        assertEquals(StructuredArmExitKind.RETURN_OR_THROW, region.thenExit?.kind)
        assertEquals(b1, region.continuation)
    }

    // Pseudocode: while (...) { if (cond) continue; BODY }
    @Test
    fun `recognizes continue arm inside natural loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            expression(branch(0), branch(1)),
        )

        val loop = assertIs<StructuredRegion.While>(result.regions.single { it.header == b0 })
        assertEquals(setOf(b1, b2), loop.bodyBlocks)
        val ifRegion = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(StructuredArmExitKind.CONTINUE, ifRegion.thenExit?.kind)
        assertEquals(b0, ifRegion.thenExit?.target)
        assertEquals(b2, ifRegion.continuation)
        assertEquals(1, result.continueIfRegionCount)
    }

    // Pseudocode: while (...) { if (cond) continue; else continue; }
    @Test
    fun `does not classify one arm as continue when both branches continue`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b4, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b0, ControlFlowEdgeKind.JUMP),
            edge(b3, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            expression(branch(0), branch(1)),
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(b0, region.continuation)
        assertEquals(null, region.thenExit)
        assertEquals(null, region.elseExit)
        assertEquals(0, result.continueIfRegionCount)
    }

    // Pseudocode: while (...) { if (cond) break; BODY }
    @Test
    fun `recognizes break arm to canonical loop exit`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            expression(branch(0), branch(1)),
        )

        val loop = assertIs<StructuredRegion.While>(result.regions.single { it.header == b0 })
        assertEquals(setOf(b1 to b3), loop.breakEdges)
        val ifRegion = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(StructuredArmExitKind.BREAK, ifRegion.thenExit?.kind)
        assertEquals(b3, ifRegion.thenExit?.target)
        assertEquals(b2, ifRegion.continuation)
        assertEquals(1, result.breakIfRegionCount)
    }

    // Pseudocode: boolean t = cond ? true : false; if (t) BODY
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
        assertEquals(ComparisonOperator.EQ, assertIs<StructuredCondition.Atomic>(consumer.condition).condition.operator)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    // Pseudocode: if (a && b && c) BODY
    @Test
    fun `folds linear short circuit branch chain into compound condition`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            expression(branch(0), branch(1)),
        )

        val fold = result.shortCircuitConditionFolds.single()
        assertEquals(b0, fold.rootHeader)
        assertEquals(setOf(b1), fold.foldedHeaders)
        assertEquals(b3, fold.conditionalTarget)
        assertEquals(b2, fold.fallthroughTarget)
        val condition = assertIs<StructuredCondition.Or>(fold.condition)
        assertEquals(2, condition.terms.size)
        val region = assertIs<StructuredRegion.If>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b3), region.thenBlocks)
        assertEquals(setOf(b2), region.elseBlocks)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    // Pseudocode: while (...) { if (a && b) continue; BODY }
    @Test
    fun `folds short circuit chain inside loop before recognizing continue arm`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val b8 = BasicBlockId(8)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b8, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b7, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b0, ControlFlowEdgeKind.JUMP),
            edge(b6, b0, ControlFlowEdgeKind.JUMP),
            edge(b7, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(9), edges),
            SsaControlFlowGraph((0..8).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            expression(branch(0), branch(1), branch(2), branch(3), branch(4)),
        )

        val loop = assertIs<StructuredRegion.While>(result.regions.single { it.header == b0 })
        assertEquals(b8, loop.exit)
        val fold = result.shortCircuitConditionFolds.single { it.rootHeader == b1 }
        assertEquals(setOf(b2, b3), fold.foldedHeaders)
        assertEquals(b4, fold.conditionalTarget)
        assertEquals(b5, fold.fallthroughTarget)
        assertEquals(3, assertIs<StructuredCondition.Or>(fold.condition).terms.size)

        val ifRegion = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(StructuredArmExitKind.CONTINUE, ifRegion.thenExit?.kind)
        assertEquals(b0, ifRegion.thenExit?.target)
        assertEquals(b5, ifRegion.continuation)
        assertEquals(setOf(b4), ifRegion.thenBlocks)
        assertEquals(1, result.continueIfRegionCount)
        assertTrue(result.unstructured.none { it.header in setOf(b1, b2, b3) })
    }

    // Pseudocode: while (...) { if (cond) A; B; continue; }
    @Test
    fun `reconstructs sequential loop-body if around a transparent continue latch`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b7, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b4, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b6, ControlFlowEdgeKind.JUMP),
            edge(b6, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(8), edges),
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            expression(branch(0), branch(1), branch(2), branch(4), unconditionalBranch(6)),
        )

        val loop = assertIs<StructuredRegion.While>(result.regions.single { it.header == b0 })
        assertEquals(setOf(b6), loop.latches)
        val regional = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(setOf(b2, b3), regional.thenBlocks)
        assertEquals(b4, regional.continuation)
        assertTrue(regional.loopBodyRegional)
        val nestedContinue = assertIs<StructuredRegion.If>(result.regions.single { it.header == b2 })
        assertEquals(StructuredArmExitKind.CONTINUE, nestedContinue.thenExit?.kind)
        assertEquals(b0, nestedContinue.thenExit?.target)
        assertEquals(1, result.loopBodyIfRegionCount)
        assertTrue(result.unstructured.none { it.header in setOf(b1, b2) })
    }

    // Pseudocode: while (...) { if (cond) continue; A; B; }
    @Test
    fun `uses forward loop continuation spine to recover early continue`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b7, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b0, ControlFlowEdgeKind.JUMP),
            edge(b4, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b6, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b0, ControlFlowEdgeKind.JUMP),
            edge(b6, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(8), edges),
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            expression(branch(0), branch(1), branch(2), branch(4)),
        )

        val region = assertIs<StructuredRegion.If>(result.regions.single { it.header == b1 })
        assertEquals(setOf(b2), region.thenBlocks)
        assertEquals(b4, region.continuation)
        assertEquals(StructuredArmExitKind.CONTINUE, region.thenExit?.kind)
        assertTrue(region.loopContinuationSpine)
        assertEquals(1, result.loopContinuationIfRegionCount)
    }

    // Pseudocode: if/CFG shape with no provable structured region -> diagnostic only
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
}
