package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionMaterialization
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.expression.FieldSymbol
import io.github.relvl.deobscura.expression.InvocationKind
import io.github.relvl.deobscura.expression.MethodSymbol
import io.github.relvl.deobscura.raw.JvmMethodDescriptor
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.raw.RawField
import io.github.relvl.deobscura.raw.RawMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceExpressionRendererDeobfuscationTest {
    @Test
    fun `uses deobfuscated names for field and method references`() {
        val owner = RawClass(
            internalName = "class/Owner",
            majorVersion = 65,
            minorVersion = 0,
            accessFlags = 0,
            superName = "java/lang/Object",
            interfaces = emptyList(),
            fields = listOf(
                RawField("value", "I", JvmType.IntType, 0),
                RawField("value", "J", JvmType.LongType, 0),
            ),
            methods = listOf(
                rawMethod("get", "()I"),
                rawMethod("get", "()J"),
            ),
        )
        val plan = DeobfuscationPlan.build(listOf(owner), enabled = true)
        val renderer = SourceExpressionRenderer(plan)

        val fieldValue = ExpressionValue(
            id = ValueId(1),
            type = JvmValueType.of(JvmType.IntType),
            node = ExpressionNode.FieldRead(
                FieldSymbol("class/Owner", "value", "I", JvmType.IntType),
                receiver = null,
            ),
        )
        val methodValue = ExpressionValue(
            id = ValueId(2),
            type = JvmValueType.of(JvmType.IntType),
            node = ExpressionNode.Call(
                method = MethodSymbol(
                    ownerInternalName = "class/Owner",
                    name = "get",
                    descriptor = "()I",
                    type = JvmMethodDescriptor.parse("()I"),
                    invocationKind = InvocationKind.STATIC,
                ),
                receiver = null,
                arguments = emptyList(),
            ),
        )
        val expression = ExpressionAnalysis(
            values = mapOf(fieldValue.id to fieldValue, methodValue.id to methodValue),
            statements = emptyList(),
            materialization = ExpressionMaterialization(inlineValues = setOf(fieldValue.id, methodValue.id)),
        )

        assertEquals("package_class.Owner.value_1", renderer.renderValue(fieldValue.id, expression))
        assertEquals("package_class.Owner.get_1()", renderer.renderValue(methodValue.id, expression))
    }

    private fun rawMethod(name: String, descriptor: String) = RawMethod(
        name = name,
        descriptor = descriptor,
        type = JvmMethodDescriptor.parse(descriptor),
        accessFlags = 0,
        exceptions = emptyList(),
        code = null,
    )
}
