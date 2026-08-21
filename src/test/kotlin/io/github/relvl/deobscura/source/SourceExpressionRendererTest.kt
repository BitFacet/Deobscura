package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaPhiLocation
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmMethodDescriptor
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import java.lang.constant.ClassDesc
import java.lang.constant.DirectMethodHandleDesc
import java.lang.constant.MethodHandleDesc
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
    fun `casts int carrier to byte method argument`() {
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

        assertEquals("sample.Target.put((byte) v1)", rendered)
    }

    @Test
    fun `casts inline integer constant to byte method argument`() {
        val receiverId = ValueId(0)
        val valueId = ValueId(1)
        val receiver = ExpressionValue(
            id = receiverId,
            type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/nio/ByteBuffer"))),
            node = ExpressionNode.Root(ValueOrigin.Instruction(0)),
        )
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Constant(constantDesc(-91)),
        )
        val expression = ExpressionAnalysis(
            values = mapOf(receiverId to receiver, valueId to value),
            statements = emptyList(),
            materialization = ExpressionMaterialization(inlineValues = setOf(valueId)),
        )
        val statement = ExpressionStatement.Call(
            instructionIndex = 2,
            method = MethodSymbol(
                ownerInternalName = "java/nio/ByteBuffer",
                name = "put",
                descriptor = "(B)Ljava/nio/ByteBuffer;",
                type = JvmMethodDescriptor(listOf(JvmType.ByteType), JvmType.ObjectType("java/nio/ByteBuffer")),
                invocationKind = InvocationKind.VIRTUAL,
            ),
            receiver = receiverId,
            arguments = listOf(valueId),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("v0.put((byte) -91)", rendered)
    }

    @Test
    fun `casts byte carrier to int to preserve invocation descriptor`() {
        val valueId = ValueId(1)
        val value = ExpressionValue(
            id = valueId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.BYTE),
            node = ExpressionNode.Root(ValueOrigin.Instruction(1)),
        )
        val expression = ExpressionAnalysis(values = mapOf(valueId to value), statements = emptyList())
        val statement = ExpressionStatement.Call(
            instructionIndex = 2,
            method = MethodSymbol(
                ownerInternalName = "sample/Target",
                name = "accept",
                descriptor = "(I)V",
                type = JvmMethodDescriptor(listOf(JvmType.IntType), JvmType.VoidType),
                invocationKind = InvocationKind.STATIC,
            ),
            receiver = null,
            arguments = listOf(valueId),
        )

        val rendered = SourceExpressionRenderer().renderStatement(statement, expression)

        assertEquals("sample.Target.accept((int) v1)", rendered)
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

    @Test
    fun `renders integer three-way comparison as direct Java comparison`() {
        assertEquals("v1 <= v2", renderThreeWayCondition(nanResult = null, ComparisonOperator.LE))
    }

    @Test
    fun `preserves fcmpl NaN semantics with negated opposite comparison`() {
        assertEquals("!(v1 > v2)", renderThreeWayCondition(nanResult = -1, ComparisonOperator.LE))
    }

    @Test
    fun `preserves fcmpg NaN semantics with negated opposite comparison`() {
        assertEquals("!(v1 < v2)", renderThreeWayCondition(nanResult = 1, ComparisonOperator.GE))
    }

    @Test
    fun `uses direct floating comparison when NaN bias already matches Java`() {
        assertEquals("v1 >= v2", renderThreeWayCondition(nanResult = -1, ComparisonOperator.GE))
        assertEquals("v1 <= v2", renderThreeWayCondition(nanResult = 1, ComparisonOperator.LE))
        assertEquals("v1 == v2", renderThreeWayCondition(nanResult = -1, ComparisonOperator.EQ))
        assertEquals("v1 != v2", renderThreeWayCondition(nanResult = 1, ComparisonOperator.NE))
    }

    @Test
    fun `renders string concat recipe with literals and dynamic arguments`() {
        val firstId = ValueId(1)
        val secondId = ValueId(2)
        val resultId = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                firstId to rootValue(firstId, JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)),
                secondId to rootValue(secondId, JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String")))),
                resultId to ExpressionValue(
                    id = resultId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
                    node = ExpressionNode.DynamicCall(
                        stringConcatCallSite("value=\u0001, text=\u0001", listOf(JvmType.IntType, JvmType.ObjectType("java/lang/String"))),
                        listOf(firstId, secondId),
                    ),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderDefinition(expression.values.getValue(resultId), expression)

        assertEquals("var v3 = \"value=\" + v1 + \", text=\" + v2", rendered)
    }

    @Test
    fun `prefixes empty string when concat starts with numeric arguments`() {
        val firstId = ValueId(1)
        val secondId = ValueId(2)
        val resultId = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                firstId to rootValue(firstId, JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)),
                secondId to rootValue(secondId, JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)),
                resultId to ExpressionValue(
                    id = resultId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
                    node = ExpressionNode.DynamicCall(
                        stringConcatCallSite("\u0001\u0001", listOf(JvmType.IntType, JvmType.IntType)),
                        listOf(firstId, secondId),
                    ),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderDefinition(expression.values.getValue(resultId), expression)

        assertEquals("var v3 = \"\" + v1 + v2", rendered)
    }

    @Test
    fun `parenthesizes lower precedence expression inside concat`() {
        val leftId = ValueId(1)
        val rightId = ValueId(2)
        val shiftId = ValueId(3)
        val resultId = ValueId(4)
        val expression = ExpressionAnalysis(
            values = mapOf(
                leftId to rootValue(leftId, JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)),
                rightId to rootValue(rightId, JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)),
                shiftId to ExpressionValue(
                    id = shiftId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    node = ExpressionNode.Binary(BinaryOperator.SHIFT_LEFT, leftId, rightId),
                ),
                resultId to ExpressionValue(
                    id = resultId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
                    node = ExpressionNode.DynamicCall(
                        stringConcatCallSite("\u0001 NULL bytes", listOf(JvmType.IntType)),
                        listOf(shiftId),
                    ),
                ),
            ),
            statements = emptyList(),
            materialization = ExpressionMaterialization(inlineValues = setOf(shiftId)),
        )

        val rendered = SourceExpressionRenderer().renderDefinition(expression.values.getValue(resultId), expression)

        assertEquals("var v4 = \"\" + (v1 << v2) + \" NULL bytes\"", rendered)
    }

    @Test
    fun `renders source conditional definition from constant phi inputs`() {
        val thenId = ValueId(1)
        val elseId = ValueId(2)
        val phiId = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                thenId to ExpressionValue(
                    thenId,
                    JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(1)),
                ),
                elseId to ExpressionValue(
                    elseId,
                    JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(0)),
                ),
                phiId to ExpressionValue(
                    phiId,
                    JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    ExpressionNode.Phi(
                        BasicBlockId(3),
                        SsaPhiLocation.Local(1),
                        emptyList(),
                    ),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderConditionalDefinition(
            expression.values.getValue(phiId),
            "arg0",
            thenId,
            elseId,
            expression,
        )

        assertEquals("var v3 = arg0 ? 1 : 0", rendered)
    }

    @Test
    fun `renders boolean conditional definition with boolean operands`() {
        val thenId = ValueId(1)
        val elseId = ValueId(2)
        val phiId = ValueId(3)
        val intType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                thenId to ExpressionValue(thenId, intType, ExpressionNode.Constant(constantDesc(1))),
                elseId to ExpressionValue(elseId, intType, ExpressionNode.Constant(constantDesc(0))),
                phiId to ExpressionValue(
                    phiId,
                    intType,
                    ExpressionNode.Phi(BasicBlockId(3), SsaPhiLocation.Stack(0), emptyList()),
                ),
            ),
            statements = emptyList(),
            materialization = ExpressionMaterialization(booleanValues = setOf(phiId)),
        )

        val rendered = SourceExpressionRenderer().renderConditionalDefinition(
            expression.values.getValue(phiId),
            "arg0",
            thenId,
            elseId,
            expression,
        )

        assertEquals("var v3 = arg0 ? true : false", rendered)
    }

    @Test
    fun `renders reconstructed conditional value directly into source local assignment`() {
        val thenId = ValueId(1)
        val elseId = ValueId(2)
        val conditionalPhiId = ValueId(3)
        val targetId = ValueId(4)
        val intType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                thenId to ExpressionValue(thenId, intType, ExpressionNode.Constant(constantDesc(0))),
                elseId to ExpressionValue(elseId, intType, ExpressionNode.Constant(constantDesc(1))),
                conditionalPhiId to ExpressionValue(
                    conditionalPhiId,
                    intType,
                    ExpressionNode.Phi(BasicBlockId(3), SsaPhiLocation.Stack(0), emptyList()),
                ),
                targetId to ExpressionValue(
                    targetId,
                    intType,
                    ExpressionNode.Phi(BasicBlockId(4), SsaPhiLocation.Local(1), emptyList()),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderConditionalAssignment(
            expression.values.getValue(targetId),
            "arg0 != 2",
            thenId,
            elseId,
            expression,
        )

        assertEquals("v4 = arg0 != 2 ? 0 : 1", rendered)
    }

    @Test
    fun `renders reconstructed boolean carrier directly into boolean source local`() {
        val zero = ValueId(1)
        val one = ValueId(2)
        val target = ValueId(3)
        val intType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                zero to ExpressionValue(zero, intType, ExpressionNode.Constant(constantDesc(0))),
                one to ExpressionValue(one, intType, ExpressionNode.Constant(constantDesc(1))),
                target to ExpressionValue(target, intType, ExpressionNode.Phi(BasicBlockId(3), SsaPhiLocation.Local(1), emptyList())),
            ),
            statements = emptyList(),
        )
        assertEquals("v3 = arg0 != 2 ? false : true", SourceExpressionRenderer().renderConditionalAssignment(expression.values.getValue(target), "arg0 != 2", zero, one, expression, true))
    }

    @Test
    fun `renders bootstrap constants from concat recipe`() {
        val argumentId = ValueId(1)
        val resultId = ValueId(2)
        val expression = ExpressionAnalysis(
            values = mapOf(
                argumentId to rootValue(argumentId, JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String")))),
                resultId to ExpressionValue(
                    id = resultId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
                    node = ExpressionNode.DynamicCall(
                        stringConcatCallSite(
                            "prefix=\u0002, value=\u0001",
                            listOf(JvmType.ObjectType("java/lang/String")),
                            constantDesc("fixed"),
                        ),
                        listOf(argumentId),
                    ),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderDefinition(expression.values.getValue(resultId), expression)

        assertEquals("var v2 = \"prefix=fixed, value=\" + v1", rendered)
    }

    @Test
    fun `uses concat parameter type for boolean source rendering`() {
        val argumentId = ValueId(1)
        val resultId = ValueId(2)
        val argument = ExpressionValue(
            id = argumentId,
            type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
            node = ExpressionNode.Constant(constantDesc(1)),
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                argumentId to argument,
                resultId to ExpressionValue(
                    id = resultId,
                    type = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
                    node = ExpressionNode.DynamicCall(
                        stringConcatCallSite("enabled=\u0001", listOf(JvmType.BooleanType)),
                        listOf(argumentId),
                    ),
                ),
            ),
            statements = emptyList(),
        )

        val rendered = SourceExpressionRenderer().renderDefinition(expression.values.getValue(resultId), expression)

        assertEquals("var v2 = \"enabled=\" + true", rendered)
    }

    private fun rootValue(id: ValueId, type: JvmValueType): ExpressionValue = ExpressionValue(
        id = id,
        type = type,
        node = ExpressionNode.Root(ValueOrigin.Instruction(id.value)),
    )

    private fun stringConcatCallSite(
        recipe: String,
        parameterTypes: List<JvmType>,
        vararg constants: java.lang.constant.ConstantDesc,
    ): DynamicCallSite = DynamicCallSite(
        name = "makeConcatWithConstants",
        descriptor = "",
        type = JvmMethodDescriptor(parameterTypes, JvmType.ObjectType("java/lang/String")),
        bootstrapMethod = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.StringConcatFactory"),
            "makeConcatWithConstants",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
        ),
        bootstrapArguments = listOf(constantDesc(recipe)) + constants,
    )

    @Test
    fun `renders reconstructed local with explicit phi type`() {
        val initializer = ValueId(1)
        val target = ValueId(2)
        val longType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.LONG)
        val expression = ExpressionAnalysis(
            values = mapOf(
                initializer to ExpressionValue(
                    initializer,
                    longType,
                    ExpressionNode.Constant(constantDesc(0L)),
                ),
                target to ExpressionValue(
                    target,
                    longType,
                    ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
                ),
            ),
            statements = emptyList(),
        )

        val renderer = SourceExpressionRenderer()
        assertEquals("long v2 = 0", renderer.renderLocalDeclaration(expression.values.getValue(target), initializer, expression))
        assertEquals("v2 = 0", renderer.renderLocalAssignment(target, initializer, longType, expression))
    }

    @Test
    fun `uses materialized value when declaring copied source local`() {
        val initializer = ValueId(1)
        val target = ValueId(2)
        val type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                initializer to ExpressionValue(
                    initializer,
                    type,
                    ExpressionNode.Constant(constantDesc(7)),
                    listOf(0),
                ),
                target to ExpressionValue(
                    target,
                    type,
                    ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
                ),
            ),
            statements = emptyList(),
        )

        assertEquals(
            "int v2 = v1",
            SourceExpressionRenderer().renderMaterializedLocalDeclaration(expression.values.getValue(target), initializer, expression),
        )
    }

    @Test
    fun `inlines constant when declaring source local from inline initializer`() {
        val initializer = ValueId(1)
        val target = ValueId(2)
        val type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                initializer to ExpressionValue(
                    initializer,
                    type,
                    ExpressionNode.Constant(constantDesc(0)),
                    listOf(0),
                ),
                target to ExpressionValue(
                    target,
                    type,
                    ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
                ),
            ),
            statements = emptyList(),
            materialization = ExpressionMaterialization(inlineValues = setOf(initializer)),
        )

        assertEquals(
            "int v2 = 0",
            SourceExpressionRenderer().renderMaterializedLocalDeclaration(
                expression.values.getValue(target),
                initializer,
                expression,
            ),
        )
    }

    @Test
    fun `renders uninitialized reconstructed local with explicit phi type`() {
        val target = ValueId(2)
        val longType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.LONG)
        val value = ExpressionValue(
            target,
            longType,
            ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
        )

        assertEquals("long v2", SourceExpressionRenderer().renderLocalDeclaration(value))
    }

    @Test
    fun `renders boolean reconstructed local using boolean context`() {
        val initializer = ValueId(1)
        val target = ValueId(2)
        val booleanType = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.BOOLEAN)
        val expression = ExpressionAnalysis(
            values = mapOf(
                initializer to ExpressionValue(
                    initializer,
                    JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(0)),
                ),
                target to ExpressionValue(
                    target,
                    booleanType,
                    ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
                ),
            ),
            statements = emptyList(),
        )

        assertEquals(
            "boolean v2 = false",
            SourceExpressionRenderer().renderLocalDeclaration(expression.values.getValue(target), initializer, expression),
        )
    }

    private fun renderThreeWayCondition(nanResult: Int?, operator: ComparisonOperator): String {
        val leftId = ValueId(1)
        val rightId = ValueId(2)
        val compareId = ValueId(3)
        val expression = ExpressionAnalysis(
            values = mapOf(
                leftId to ExpressionValue(
                    id = leftId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.FLOAT),
                    node = ExpressionNode.Root(ValueOrigin.Instruction(1)),
                ),
                rightId to ExpressionValue(
                    id = rightId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.FLOAT),
                    node = ExpressionNode.Root(ValueOrigin.Instruction(2)),
                ),
                compareId to ExpressionValue(
                    id = compareId,
                    type = JvmValueType.Computational(io.github.relvl.deobscura.raw.JvmComputationalType.INT),
                    node = ExpressionNode.ThreeWayCompare(leftId, rightId, nanResult),
                ),
            ),
            statements = emptyList(),
        )
        return SourceExpressionRenderer().renderCondition(
            BranchCondition(operator, compareId, BranchOperand.Zero),
            expression,
        )
    }

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc

}
