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
        assertEquals(b3, region.continuation)
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


    @Test
    fun `recognizes switch cases and common continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 10),
            switchEdge(b0, b2, 20),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b4, region.continuation)
        assertEquals(3, region.cases.size)
        assertEquals(listOf(10), region.cases.single { it.entry == b1 }.labels)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b3 }.exit?.kind)
        assertTrue(region.cases.single { it.entry == b3 }.isDefault)
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `groups multiple switch labels that share one body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 10),
            switchEdge(b0, b1, 11),
            switchEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(listOf(10, 11), region.cases.single { it.entry == b1 }.labels)
        assertEquals(2, region.cases.size)
    }

    @Test
    fun `recognizes switch case fallthrough into another case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        val first = region.cases.single { it.entry == b1 }
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, first.exit?.kind)
        assertEquals(b2, first.exit?.target)
        assertEquals(b3, region.continuation)
    }

    @Test
    fun `does not mistake a join inside a fallthrough chain for switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            switchEdge(b0, b1, null),
            switchEdge(b0, b2, 0),
            switchEdge(b0, b3, 1),
            switchEdge(b0, b6, 2),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                branch(3),
                ExpressionStatement.Return(1, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b0 })
        assertEquals(null, region.continuation)
        assertEquals(
            listOf(StructuredRegionTransfer(b2, b3, StructuredRegionTransferKind.CASE_FALLTHROUGH)),
            region.cases.single { it.entry == b2 }.transfers,
        )
        assertEquals(setOf(b3, b4, b5), region.cases.single { it.entry == b3 }.blocks)
        assertTrue(
            region.cases.single { it.entry == b3 }.transfers.any {
                it.kind == StructuredRegionTransferKind.CASE_FALLTHROUGH && it.target == b6
            },
        )
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    @Test
    fun `recognizes conditional break and fallthrough from the same switch case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 2),
            switchEdge(b0, b2, 3),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, branch(1)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b0 })
        val case2 = region.cases.single { it.entry == b1 }
        assertEquals(
            setOf(StructuredRegionTransferKind.BREAK_SWITCH, StructuredRegionTransferKind.CASE_FALLTHROUGH),
            case2.transfers.mapTo(linkedSetOf()) { it.kind },
        )
        assertTrue(case2.transfers.any { it.kind == StructuredRegionTransferKind.BREAK_SWITCH && it.target == b4 })
        assertTrue(case2.transfers.any { it.kind == StructuredRegionTransferKind.CASE_FALLTHROUGH && it.target == b2 })
        assertEquals(null, case2.exit)
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    @Test
    fun `recognizes switch case break from an enclosing natural infinite loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, 2),
            switchEdge(b1, b4, null),
            edge(b3, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b1, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Return(2, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        val breakingCase = region.cases.single { it.entry == b2 }
        assertEquals(
            listOf(StructuredRegionTransferKind.BREAK_LOOP),
            breakingCase.transfers.map { it.kind },
        )
        assertEquals(b2, breakingCase.transfers.single().target)
        assertEquals(b4, region.continuation)
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    @Test
    fun `preserves switch case that directly breaks an enclosing natural loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b4, null),
            switchEdge(b1, b2, 10),
            switchEdge(b1, b3, 20),
            switchEdge(b1, b5, 30),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b1, ControlFlowEdgeKind.JUMP),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Return(6, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        assertEquals(b4, region.continuation)
        assertEquals(4, region.cases.size)
        assertEquals(setOf(10, 20, 30), region.cases.flatMap { it.labels }.toSet())
        assertEquals(1, region.cases.count { it.isDefault })

        val breakingCase = region.cases.single { 30 in it.labels }
        assertEquals(b5, breakingCase.entry)
        assertTrue(breakingCase.blocks.isEmpty())
        assertEquals(
            listOf(StructuredRegionTransfer(b1, b5, StructuredRegionTransferKind.BREAK_LOOP)),
            breakingCase.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    @Test
    fun `recognizes switch case targeting canonical loop exit despite another terminal loop exit`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b4, null),
            switchEdge(b1, b5, -1),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(6), edges),
            SsaControlFlowGraph((0..5).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Throw(4, ValueId(0)), ExpressionStatement.Return(5, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        val breakingCase = region.cases.single { -1 in it.labels }
        assertTrue(breakingCase.blocks.isEmpty())
        assertEquals(
            listOf(StructuredRegionTransfer(b1, b5, StructuredRegionTransferKind.BREAK_LOOP)),
            breakingCase.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    @Test
    fun `prefers local switch join before re-entering enclosing loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b7, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b2, b3, 1),
            switchEdge(b2, b4, 2),
            switchEdge(b2, b6, null),
            edge(b3, b5, ControlFlowEdgeKind.JUMP),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
            edge(b5, b1, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(8), edges),
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(2, ExpressionStatement.Return(6, null), ExpressionStatement.Return(7, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b2 })
        assertEquals(b5, region.continuation)
        assertTrue(result.unstructured.none { it.header == b2 })
    }

    @Test
    fun `recognizes switch continuation despite a terminal case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Return(1, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b2 }.exit?.kind)
    }

    @Test
    fun `recognizes continuation that is also an empty switch case target`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b4, 3),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Return(3, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(emptySet(), region.cases.single { it.entry == b4 }.blocks)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b4 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b3 }.exit?.kind)
    }

    @Test
    fun `recognizes switch target shared with surrounding control flow as continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b4, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, 2),
            switchEdge(b1, b4, null),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(6), edges),
            SsaControlFlowGraph((0..5).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                1,
                ExpressionStatement.Return(2, null),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(5, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        assertEquals(b4, region.continuation)
        assertEquals(emptySet(), region.cases.single { it.entry == b4 }.blocks)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b4 }.exit?.kind)
    }

    @Test
    fun `recognizes terminal switch with fallthrough into terminal default`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Return(1, null),
                ExpressionStatement.Return(3, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b2 }.exit?.kind)
        assertEquals(b3, region.cases.single { it.entry == b2 }.exit?.target)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b3 }.exit?.kind)
    }

    @Test
    fun `keeps case local terminal branch inside case body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b3, 2),
            switchEdge(b0, b4, null),
            edge(b1, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Throw(2, ValueId(0)),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(4, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        val firstCase = region.cases.single { it.entry == b1 }
        assertTrue(b2 in firstCase.blocks)
        assertEquals(null, firstCase.exit)
        assertEquals(
            setOf(
                StructuredRegionTransfer(b1, b3, StructuredRegionTransferKind.CASE_FALLTHROUGH),
                StructuredRegionTransfer(b2, kind = StructuredRegionTransferKind.RETURN_OR_THROW),
            ),
            firstCase.transfers.toSet(),
        )
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `recognizes common terminal post dominator as switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b2, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b1, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Throw(4, ValueId(0))),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b3 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(region.cases.none { b4 in it.blocks })
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `recognizes jump to reachable externally shared terminal block as terminal case exit`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b6, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b6, b5, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 0),
            switchEdge(b0, b2, 1),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b4, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b6),
            switchExpression(
                0,
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(4, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(b4, region.cases.single { it.entry == b1 }.exit?.target)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `recognizes continuation reached by multiple cases despite local terminal branches`() {
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
            switchEdge(b0, b1, 0),
            switchEdge(b0, b4, 1),
            switchEdge(b0, b8, null),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b7, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b6, b7, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(9), edges),
            SsaControlFlowGraph((0..8).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Throw(2, ValueId(0)),
                ExpressionStatement.Throw(5, ValueId(0)),
                ExpressionStatement.Return(7, null),
                ExpressionStatement.Throw(8, ValueId(0)),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b7, region.continuation)
        assertTrue(b2 in region.cases.single { it.entry == b1 }.blocks)
        assertTrue(b5 in region.cases.single { it.entry == b4 }.blocks)
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `prefers local case join over later surrounding-flow join`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val b8 = BasicBlockId(8)
        val b9 = BasicBlockId(9)
        val edges = listOf(
            edge(b8, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b8, b7, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b7, b6, ControlFlowEdgeKind.JUMP),
            edge(b6, b9, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(10), edges),
            SsaControlFlowGraph((0..9).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b8),
            switchExpression(
                0,
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Raw(4, "after switch", emptyList()),
                ExpressionStatement.Return(9, null),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Switch>().single { it.header == b0 }
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    @Test
    fun `recognizes non-terminal non-entry continuation shared with surrounding flow`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b5, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b5, b4, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b4, b3, ControlFlowEdgeKind.JUMP),
            edge(b3, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b5),
            switchExpression(
                0,
                ExpressionStatement.Return(2, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b3, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertTrue(result.unstructured.isEmpty())
    }

    @Test
    fun `recognizes proven natural loop continue without using upstream join as switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b5, b0, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b0, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, null),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b5),
            switchExpression(
                1,
                ExpressionStatement.Raw(2, "work", emptyList()),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Switch>().single { it.header == b1 }
        assertEquals(null, region.continuation)
        assertEquals(
            listOf(StructuredRegionTransfer(b2, b4, StructuredRegionTransferKind.CONTINUE_LOOP)),
            region.cases.single { it.entry == b2 }.transfers,
        )
        assertEquals(
            listOf(StructuredRegionTransfer(b3, kind = StructuredRegionTransferKind.RETURN_OR_THROW)),
            region.cases.single { it.entry == b3 }.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    @Test
    fun `recognizes fully terminal switch without common continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
        )
        val expression = switchExpression(
            0,
            ExpressionStatement.Return(1, null),
            ExpressionStatement.Return(2, null),
            ExpressionStatement.Throw(3, ValueId(0)),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            expression,
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertTrue(region.cases.all { it.exit?.kind == StructuredSwitchCaseExitKind.RETURN_OR_THROW })
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

    @Test
    fun `recognizes sibling typed catches for one protected range`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b1, "java/io/IOException"),
            exceptionEdge(b0, b2, "java/lang/RuntimeException"),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 4,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(0, 1, 2, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(2, null))),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(2, region.catches.size)
        assertEquals(listOf("java/io/IOException"), region.catches.single { it.entry == b1 }.catchTypes)
        assertEquals(listOf("java/lang/RuntimeException"), region.catches.single { it.entry == b2 }.catchTypes)
        assertEquals(1, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `coalesces source try split around terminal control transfer`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, "java/io/IOException"),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, b4, "java/io/IOException"),
        )
        val graph = exceptionGraph(
            blockCount = 5,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 4, "java/io/IOException"),
                exceptionHandler(2, 3, 4, "java/io/IOException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(1, null),
                    ExpressionStatement.Return(4, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(setOf(b0, b1, b2), region.tryBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(
            listOf(
                StructuredProtectedRange(0, 1),
                StructuredProtectedRange(2, 3),
            ),
            region.protectedRanges,
        )
        assertEquals(1, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `treats nested catch body as owned by enclosing catch`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, "java/io/IOException"),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b5, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b3, b4, "java/io/IOException"),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 2, "java/io/IOException"),
                exceptionHandler(3, 4, 4, "java/io/IOException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4, b5), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(5, ValueId(0)))),
        )

        val outer = result.regions.filterIsInstance<StructuredRegion.TryCatch>()
            .single { it.header == b0 }
        assertEquals(setOf(b2, b3, b4, b5), outer.catches.single().blocks)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes catch that continues at protected loop header`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, "java/lang/Exception"),
            edge(b1, b0, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b1, b2, "java/lang/Exception"),
            edge(b2, b0, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 3,
            edges = edges,
            handlers = listOf(exceptionHandler(0, 2, 2, "java/lang/Exception")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), emptyList()),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0, b1), region.tryBlocks)
        assertEquals(setOf(b2), region.catches.single().blocks)
        assertEquals(b0, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `keeps shared post-catch join outside handler body after terminal try return`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, b4, "java/io/IOException"),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(exceptionHandler(2, 3, 4, "java/io/IOException")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4, b5), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(3, null),
                    ExpressionStatement.Return(5, null),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(setOf(b2, b3), region.tryBlocks)
        assertEquals(setOf(b4), region.catches.single().blocks)
        assertEquals(b5, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `uses common source join beyond protected normal bridge`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, "java/lang/Exception"),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 5,
            edges = edges,
            handlers = listOf(exceptionHandler(1, 2, 3, "java/lang/Exception")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(4, null))),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(setOf(b1), region.tryBlocks)
        assertEquals(setOf(b3), region.catches.single().blocks)
        assertEquals(b4, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes canonical synchronized monitor cleanup`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, null),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b3, b3, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 2, emptyList(), emptyList()),
            BasicBlock(b1, 2, 5, emptyList(), emptyList()),
            BasicBlock(b2, 5, 6, emptyList(), emptyList()),
            BasicBlock(b3, 6, 9, emptyList(), emptyList()),
            BasicBlock(b4, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(2, 9, 6, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(10, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(b3, region.handlerEntry)
        assertEquals(setOf(b3, b4), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(1, region.monitorEnterInstructionIndex)
        assertEquals(listOf(4), region.normalMonitorExitInstructionIndices)
        assertEquals(8, region.handlerMonitorExitInstructionIndex)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes synchronized monitor loaded from stored local before monitorenter`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b2, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 7, emptyList(), emptyList()),
            BasicBlock(b2, 7, 12, emptyList(), emptyList()),
        )
        val labels = List(13) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(3, 7, 7, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(11, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(2, region.monitorEnterInstructionIndex)
        assertEquals(listOf(5), region.normalMonitorExitInstructionIndices)
        assertEquals(9, region.handlerMonitorExitInstructionIndex)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `absorbs self-protecting monitor cleanup range into synchronized region`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, null),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b3, b3, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 2, emptyList(), emptyList()),
            BasicBlock(b1, 2, 5, emptyList(), emptyList()),
            BasicBlock(b2, 5, 6, emptyList(), emptyList()),
            BasicBlock(b3, 6, 9, emptyList(), emptyList()),
            BasicBlock(b4, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(2, 5, 6, null),
                    exceptionHandler(6, 9, 6, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(10, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(
            listOf(StructuredProtectedRange(6, 9)),
            region.syntheticCleanupProtectedRanges,
        )
        assertEquals(2, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `groups multi-catch table entries sharing one handler`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b1, "java/io/IOException"),
            exceptionEdge(b0, b1, "java/lang/RuntimeException"),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 3,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(0, 1, 1, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), emptyList()),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(1, region.catches.size)
        assertEquals(
            listOf("java/io/IOException", "java/lang/RuntimeException"),
            region.catches.single().catchTypes,
        )
    }

    @Test
    fun `recognizes canonical finally from duplicated normal body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(7)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawNopInstruction(JvmOpcode("nop")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 7, emptyList(), emptyList()),
            BasicBlock(b3, 7, 8, emptyList(), emptyList()),
        )
        val labels = List(9) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 3, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(6, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.TryFinally>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(b2, region.handlerEntry)
        assertEquals(4..4, region.finallyBodyInstructionIndices)
        assertEquals(listOf(1..1), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes branching finally from duplicated normal body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, null),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b7, ControlFlowEdgeKind.CONDITIONAL),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b6, b7, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(4)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(9)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 6, emptyList(), emptyList()),
            BasicBlock(b5, 6, 8, emptyList(), emptyList()),
            BasicBlock(b6, 8, 9, emptyList(), emptyList()),
            BasicBlock(b7, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 5, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(10, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6, b7), region.handlerBlocks)
        assertEquals(6..8, region.finallyBodyInstructionIndices)
        assertEquals(listOf(1..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1, b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes branching finally when exception store shares body entry block`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, null),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(5)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 6, emptyList(), emptyList()),
            BasicBlock(b4, 6, 9, emptyList(), emptyList()),
            BasicBlock(b5, 9, 10, emptyList(), emptyList()),
            BasicBlock(b6, 10, 12, emptyList(), emptyList()),
        )
        val labels = List(13) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 6, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(5, null),
                    ExpressionStatement.Throw(11, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6), region.handlerBlocks)
        assertEquals(7..9, region.finallyBodyInstructionIndices)
        assertEquals(listOf(1..4), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1, b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes legacy jsr finally shape after normalization`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, null),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(8)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 8, emptyList(), emptyList()),
            BasicBlock(b5, 8, 9, emptyList(), emptyList()),
            BasicBlock(b6, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 5, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(10, ValueId(0)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6), region.handlerBlocks)
        assertEquals(8..8, region.finallyBodyInstructionIndices)
        assertEquals(listOf(3..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `recognizes legacy jsr try catch finally shape after normalization`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, "java/lang/Exception"),
            exceptionEdge(b0, b5, null),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b4, b5, null),
            edge(b5, b6, ControlFlowEdgeKind.JUMP),
            edge(b6, b7, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 7, emptyList(), emptyList()),
            BasicBlock(b5, 7, 10, emptyList(), emptyList()),
            BasicBlock(b6, 10, 11, emptyList(), emptyList()),
            BasicBlock(b7, 11, 13, emptyList(), emptyList()),
        )
        val labels = List(14) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 5, "java/lang/Exception"),
                    exceptionHandler(0, 1, 7, null),
                    exceptionHandler(5, 7, 7, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(6, ValueId(0)),
                    ExpressionStatement.Throw(12, ValueId(1)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatchFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(listOf("java/lang/Exception"), region.catches.single().catchTypes)
        assertEquals(setOf(b4), region.catches.single().blocks)
        assertEquals(setOf(b5, b6, b7), region.handlerBlocks)
        assertEquals(10..10, region.finallyBodyInstructionIndices)
        assertEquals(listOf(3..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    @Test
    fun `keeps catch-all exception region block based with explicit diagnostic`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val edges = listOf(
            exceptionEdge(b0, b1, null),
        )
        val graph = exceptionGraph(
            blockCount = 2,
            edges = edges,
            handlers = listOf(exceptionHandler(0, 1, 1, null)),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(1, ValueId(0)))),
        )

        assertTrue(result.regions.none { it is StructuredRegion.TryCatch })
        val diagnostic = result.unstructured.single { it.kind == UnstructuredControlFlowKind.EXCEPTION }
        assertEquals(UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED, diagnostic.reason)
        assertEquals(0, diagnostic.protectedStartInstructionIndex)
        assertEquals(1, diagnostic.protectedEndInstructionIndexExclusive)
    }

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc

    private fun expression(vararg branches: ExpressionStatement.Branch): ExpressionAnalysis {
        val condition = ValueId(0)
        return ExpressionAnalysis(
            values = mapOf(
                condition to ExpressionValue(
                    condition,
                    JvmValueType.of(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
            ),
            statements = branches.toList(),
        )
    }

    private fun switchExpression(
        instructionIndex: Int,
        vararg additionalStatements: ExpressionStatement,
    ): ExpressionAnalysis {
        val selector = ValueId(0)
        return ExpressionAnalysis(
            values = mapOf(
                selector to ExpressionValue(
                    selector,
                    JvmValueType.of(JvmComputationalType.INT),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
            ),
            statements = listOf(ExpressionStatement.Switch(instructionIndex, selector)) + additionalStatements,
        )
    }

    private fun branch(instructionIndex: Int): ExpressionStatement.Branch = ExpressionStatement.Branch(
        instructionIndex,
        BranchCondition(ComparisonOperator.NE, ValueId(0), BranchOperand.Zero),
    )

    private fun unconditionalBranch(instructionIndex: Int): ExpressionStatement.Branch =
        ExpressionStatement.Branch(instructionIndex, null)

    private fun blocks(count: Int): List<BasicBlock> = List(count) { index ->
        BasicBlock(BasicBlockId(index), index, index + 1, emptyList(), emptyList())
    }

    private fun graph(blocks: List<BasicBlock>, edges: List<ControlFlowEdge>) = ControlFlowGraph(
        RawCode(null, null, null, List(blocks.size) { RawNopInstruction(JvmOpcode("nop")) }, emptyList(), emptyList(), emptyList()),
        blocks,
        edges,
        blocks.firstOrNull()?.id,
    )

    private fun exceptionGraph(
        blockCount: Int,
        edges: List<ControlFlowEdge>,
        handlers: List<RawExceptionHandler>,
    ): ControlFlowGraph {
        val blocks = blocks(blockCount)
        val labels = List(blockCount + 1) { index -> RawLabel(RawLabelId(index), index, index) }
        return ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                List(blockCount) { RawNopInstruction(JvmOpcode("nop")) },
                labels,
                handlers,
                emptyList(),
            ),
            blocks,
            edges,
            blocks.firstOrNull()?.id,
        )
    }

    private fun exceptionHandler(
        startInstruction: Int,
        endInstructionExclusive: Int,
        handlerInstruction: Int,
        catchType: String?,
    ) = RawExceptionHandler(
        tryStart = RawLabelId(startInstruction),
        tryEnd = RawLabelId(endInstructionExclusive),
        handler = RawLabelId(handlerInstruction),
        catchType = catchType,
    )

    private fun exceptionEdge(from: BasicBlockId, to: BasicBlockId, catchType: String?) =
        ControlFlowEdge(from, to, ControlFlowEdgeKind.EXCEPTION, catchType = catchType)

    private fun switchEdge(from: BasicBlockId, to: BasicBlockId, value: Int?) =
        ControlFlowEdge(from, to, ControlFlowEdgeKind.SWITCH, switchValue = value)

    private fun edge(from: BasicBlockId, to: BasicBlockId, kind: ControlFlowEdgeKind) =
        ControlFlowEdge(from, to, kind)
}
