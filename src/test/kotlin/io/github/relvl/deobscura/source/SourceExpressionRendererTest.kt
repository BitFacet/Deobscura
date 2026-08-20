package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceExpressionRendererTest {
    @Test
    fun `renders exception handler root through lexical catch binding`() {
        val handlerInstructionIndex = 17
        val valueId = ValueId(3)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/Exception"))),
            node = ExpressionNode.Root(ValueOrigin.ExceptionHandler(handlerInstructionIndex, "java/lang/Exception")),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val bindings = SourceValueBindings.EMPTY.withExceptionParameter(handlerInstructionIndex, "e0")

        val rendered = SourceExpressionRenderer(bindings = bindings).renderValue(valueId, expression)

        assertEquals("e0", rendered)
    }

    @Test
    fun `keeps unmatched exception handler root explicit`() {
        val valueId = ValueId(3)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/Exception"))),
            node = ExpressionNode.Root(ValueOrigin.ExceptionHandler(17, "java/lang/Exception")),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())

        val rendered = SourceExpressionRenderer().renderValue(valueId, expression)

        assertEquals("caught", rendered)
    }
}
