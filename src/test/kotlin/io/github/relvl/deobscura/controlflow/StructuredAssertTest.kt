package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredAssertTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    @Test
    fun `recognizes javac assertion guard and AssertionError arm`() {
        val result = analyzeAssertion(message = null)

        val region = assertIs<StructuredRegion.Assert>(result.regions.single())
        assertEquals(BasicBlockId(0), region.header)
        assertEquals(BasicBlockId(1), region.checkHeader)
        assertEquals(setOf(BasicBlockId(2)), region.failureBlocks)
        assertEquals(BasicBlockId(3), region.continuation)
        assertEquals(ComparisonOperator.EQ, assertIs<StructuredCondition.Atomic>(region.condition).condition.operator)
        assertNull(region.message)
        assertEquals(0, result.unstructuredConditionalCount)
    }

    @Test
    fun `preserves assertion message value`() {
        val message = ValueId(4)
        val result = analyzeAssertion(message)

        val region = assertIs<StructuredRegion.Assert>(result.regions.single())
        assertEquals(message, region.message)
        assertTrue(result.unstructured.isEmpty())
    }

    private fun analyzeAssertion(message: ValueId?): StructuredControlFlowAnalysis {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val assertionsDisabled = ValueId(0)
        val checked = ValueId(1)
        val error = ValueId(2)
        val values = linkedMapOf<ValueId, ExpressionValue>(
            assertionsDisabled to ExpressionValue(
                assertionsDisabled,
                JvmValueType.of(JvmType.BooleanType),
                ExpressionNode.FieldRead(
                    FieldSymbol("example/Sample", "\$assertionsDisabled", "Z", JvmType.BooleanType),
                    receiver = null,
                ),
                instructionIndices = listOf(0),
            ),
            checked to ExpressionValue(
                checked,
                JvmValueType.of(JvmType.ObjectType("java/lang/Object")),
                ExpressionNode.Root(io.github.relvl.deobscura.analysis.ValueOrigin.Parameter(0)),
            ),
        )
        message?.let {
            values[it] = ExpressionValue(
                it,
                JvmValueType.of(JvmType.ObjectType("java/lang/String")),
                ExpressionNode.Root(io.github.relvl.deobscura.analysis.ValueOrigin.Parameter(1)),
            )
        }
        val constructorArguments = message?.let { listOf(it) }.orEmpty()
        values[error] = ExpressionValue(
            error,
            JvmValueType.of(JvmType.ObjectType("java/lang/AssertionError")),
            ExpressionNode.ConstructObject(
                internalName = "java/lang/AssertionError",
                constructor = MethodSymbol(
                    ownerInternalName = "java/lang/AssertionError",
                    name = "<init>",
                    descriptor = if (message == null) "()V" else "(Ljava/lang/Object;)V",
                    type = JvmMethodDescriptor(
                        parameterTypes = if (message == null) emptyList() else listOf(JvmType.ObjectType("java/lang/Object")),
                        returnType = JvmType.VoidType,
                    ),
                    invocationKind = InvocationKind.SPECIAL,
                ),
                arguments = constructorArguments,
            ),
            instructionIndices = listOf(2),
        )
        val expression = ExpressionAnalysis(
            values = values,
            statements = listOf(
                ExpressionStatement.Branch(0, BranchCondition(ComparisonOperator.NE, assertionsDisabled, BranchOperand.Zero)),
                ExpressionStatement.Branch(1, BranchCondition(ComparisonOperator.EQ, checked, BranchOperand.Null)),
                ExpressionStatement.Throw(2, error),
            ),
        )
        return analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            expression,
        )
    }
}
