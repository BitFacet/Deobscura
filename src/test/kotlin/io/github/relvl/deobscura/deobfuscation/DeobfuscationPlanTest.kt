package io.github.relvl.deobscura.deobfuscation

import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DeobfuscationPlanTest {
    @Test
    fun `renames illegal package segments consistently`() {
        val plan = DeobfuscationPlan.build(listOf(rawClass("class/example/Sample")), enabled = true)

        assertEquals("package_class/example/Sample", plan.classInternalName("class/example/Sample"))
        assertEquals("package_class/example/Other", plan.classInternalName("class/example/Other"))
        assertEquals(1, plan.stats.renamedPackageSegments)
    }

    @Test
    fun `does not steal an existing legal package name`() {
        val plan = DeobfuscationPlan.build(
            listOf(rawClass("class/First"), rawClass("package_class/Second")),
            enabled = true,
        )

        assertEquals("package_class_1/First", plan.classInternalName("class/First"))
        assertEquals("package_class/Second", plan.classInternalName("package_class/Second"))
    }

    @Test
    fun `renames duplicate fields by exact JVM symbol`() {
        val owner = rawClass(
            "example/Owner",
            fields = listOf(
                RawField("value", "I", JvmType.IntType, 0),
                RawField("value", "Ljava/lang/String;", JvmType.ObjectType("java/lang/String"), 0),
            ),
        )
        val plan = DeobfuscationPlan.build(listOf(owner), enabled = true)

        assertEquals("value_1", plan.fieldName("example/Owner", "value", "I"))
        assertEquals("value_2", plan.fieldName("example/Owner", "value", "Ljava/lang/String;"))
        assertEquals(2, plan.stats.renamedFields)
    }

    @Test
    fun `renames methods that collide as Java signatures`() {
        val owner = rawClass(
            "example/Owner",
            methods = listOf(
                rawMethod("get", "()I"),
                rawMethod("get", "()Ljava/lang/String;"),
                rawMethod("get", "(I)I"),
            ),
        )
        val plan = DeobfuscationPlan.build(listOf(owner), enabled = true)

        assertEquals("get_1", plan.methodName("example/Owner", "get", "()I"))
        assertEquals("get_2", plan.methodName("example/Owner", "get", "()Ljava/lang/String;"))
        assertEquals("get", plan.methodName("example/Owner", "get", "(I)I"))
        assertEquals(2, plan.stats.renamedMethods)
    }

    @Test
    fun `disabled plan preserves every name`() {
        val owner = rawClass(
            "class/Owner",
            fields = listOf(
                RawField("value", "I", JvmType.IntType, 0),
                RawField("value", "J", JvmType.LongType, 0),
            ),
        )
        val plan = DeobfuscationPlan.build(listOf(owner), enabled = false)

        assertEquals("class/Owner", plan.classInternalName("class/Owner"))
        assertEquals("value", plan.fieldName("class/Owner", "value", "I"))
        assertEquals(DeobfuscationStats(), plan.stats)
    }

    private fun rawClass(
        internalName: String,
        fields: List<RawField> = emptyList(),
        methods: List<RawMethod> = emptyList(),
    ) = RawClass(
        internalName = internalName,
        majorVersion = 65,
        minorVersion = 0,
        accessFlags = 0,
        superName = "java/lang/Object",
        interfaces = emptyList(),
        fields = fields,
        methods = methods,
    )

    private fun rawMethod(name: String, descriptor: String) = RawMethod(
        name = name,
        descriptor = descriptor,
        type = JvmMethodDescriptor.parse(descriptor),
        accessFlags = 0,
        exceptions = emptyList(),
        code = null,
    )
}
