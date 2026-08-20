package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.expression.FieldSymbol
import io.github.relvl.deobscura.expression.InvocationKind
import io.github.relvl.deobscura.expression.MethodSymbol
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmMethodDescriptor
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

    @Test
    fun `renders null dynamic constant as Java null`() {
        val valueId = ValueId(4)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Reference(JvmReferenceType.Null),
            node = ExpressionNode.Constant(java.lang.constant.ConstantDescs.NULL),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())

        val rendered = SourceExpressionRenderer().renderDefinition(value, expression)

        assertEquals("var v4 = null", rendered)
    }

    @Test
    fun `renders constructor invocation on this as this call`() {
        val thisId = ValueId(0)
        val expression = ExpressionAnalysis(
            values = mapOf(
                thisId to ExpressionValue(
                    id = thisId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("sample/Child"))),
                    node = ExpressionNode.Root(ValueOrigin.This("sample/Child")),
                ),
            ),
            statements = emptyList(),
        )
        val statement = ExpressionStatement.Call(
            instructionIndex = 1,
            method = MethodSymbol(
                ownerInternalName = "sample/Child",
                name = "<init>",
                descriptor = "()V",
                type = JvmMethodDescriptor(emptyList(), JvmType.VoidType),
                invocationKind = InvocationKind.SPECIAL,
            ),
            receiver = thisId,
            arguments = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("this()", rendered)
    }

    @Test
    fun `renders constructor invocation on this targeting parent as super call`() {
        val thisId = ValueId(0)
        val expression = ExpressionAnalysis(
            values = mapOf(
                thisId to ExpressionValue(
                    id = thisId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("sample/Child"))),
                    node = ExpressionNode.Root(ValueOrigin.This("sample/Child")),
                ),
            ),
            statements = emptyList(),
        )
        val statement = ExpressionStatement.Call(
            instructionIndex = 1,
            method = MethodSymbol(
                ownerInternalName = "sample/Parent",
                name = "<init>",
                descriptor = "()V",
                type = JvmMethodDescriptor(emptyList(), JvmType.VoidType),
                invocationKind = InvocationKind.SPECIAL,
            ),
            receiver = thisId,
            arguments = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("super()", rendered)
    }


    @Test
    fun `renders integer constants as booleans only in boolean field context`() {
        val valueId = ValueId(1)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Constant(constantDesc(1)),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val statement = ExpressionStatement.FieldWrite(
            instructionIndex = 1,
            field = FieldSymbol("sample/Owner", "flag", "Z", JvmType.BooleanType),
            receiver = null,
            value = valueId,
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("sample.Owner.flag = true", rendered)
    }

    @Test
    fun `renders integer constants as booleans in boolean return context`() {
        val valueId = ValueId(1)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Constant(constantDesc(0)),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val statement = ExpressionStatement.Return(instructionIndex = 1, value = valueId)

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression, JvmType.BooleanType)

        assertEquals("return false", rendered)
    }

    @Test
    fun `renders numeric boolean carrier as comparison for boolean method argument`() {
        val valueId = ValueId(3)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Root(ValueOrigin.Instruction(3)),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val statement = ExpressionStatement.Call(
            instructionIndex = 4,
            method = MethodSymbol(
                ownerInternalName = "sample/Target",
                name = "accept",
                descriptor = "(Z)V",
                type = JvmMethodDescriptor(listOf(JvmType.BooleanType), JvmType.VoidType),
                invocationKind = InvocationKind.STATIC,
            ),
            receiver = null,
            arguments = listOf(valueId),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("sample.Target.accept(v3 != 0)", rendered)
    }

    @Test
    fun `keeps zero and one numeric for byte method arguments`() {
        val valueId = ValueId(1)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Constant(constantDesc(1)),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val statement = ExpressionStatement.Call(
            instructionIndex = 2,
            method = MethodSymbol(
                ownerInternalName = "sample/Target",
                name = "put",
                descriptor = "(B)V",
                type = JvmMethodDescriptor(listOf(JvmType.ByteType), JvmType.VoidType),
                invocationKind = InvocationKind.STATIC,
            ),
            receiver = null,
            arguments = listOf(valueId),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("sample.Target.put(v1)", rendered)
    }

    @Test
    fun `renders numeric carrier as boolean for boolean array element`() {
        val arrayId = ValueId(1)
        val indexId = ValueId(2)
        val valueId = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                arrayId to ExpressionValue(
                    id = arrayId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ArrayType(JvmType.BooleanType))),
                    node = ExpressionNode.Root(ValueOrigin.Instruction(1)),
                ),
                indexId to ExpressionValue(
                    id = indexId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    node = ExpressionNode.Constant(constantDesc(0)),
                ),
                valueId to ExpressionValue(
                    id = valueId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    node = ExpressionNode.Root(ValueOrigin.Instruction(3)),
                ),
            ),
            statements = emptyList(),
        )
        val statement = ExpressionStatement.ArrayWrite(1, arrayId, indexId, valueId)

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("v1[v2] = v3 != 0", rendered)
    }

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc

}
