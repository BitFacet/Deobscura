package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodOverrideAnalysisTest {
    @Test
    fun `groups superclass and interface overrides into one family`() {
        val iface = rawClass("example/Contract", methods = listOf(method("work", "()Ljava/lang/Object;")), accessFlags = ACC_INTERFACE)
        val base = rawClass("example/Base", methods = listOf(method("work", "()Ljava/lang/Object;")), interfaces = listOf(iface.internalName))
        val child = rawClass("example/Child", superName = base.internalName, methods = listOf(method("work", "()Ljava/lang/String;")))
        val analysis = analyzer(returnAssignable = ::covariantStrings).analyze(rawImport(iface, base, child))

        val ifaceKey = MethodOverrideKey(iface.internalName, "work", "()Ljava/lang/Object;")
        val childKey = MethodOverrideKey(child.internalName, "work", "()Ljava/lang/String;")
        assertEquals(analysis.familyOf(ifaceKey), analysis.familyOf(childKey))
        assertTrue(analysis.overridesSuperMethod(base.internalName, "work", "()Ljava/lang/Object;"))
        assertTrue(analysis.overridesSuperMethod(child.internalName, "work", "()Ljava/lang/String;"))
    }

    @Test
    fun `external api declaration pins application override family`() {
        val external = rawClass("java/lang/RunnableLike", methods = listOf(method("run", "()V", ACC_PUBLIC)), accessFlags = ACC_INTERFACE)
        val app = rawClass(
            "example/Worker",
            interfaces = listOf(external.internalName),
            methods = listOf(method("run", "()V", ACC_PUBLIC), method("run", "()I", ACC_PUBLIC)),
        )
        val analysis = analyzer(mapOf(external.internalName to external)).analyze(rawImport(app))
        val plan = DeobfuscationPlan.build(listOf(app), enabled = true, methodOverrides = analysis)

        assertTrue(analysis.overridesSuperMethod(app.internalName, "run", "()V"))
        assertTrue(analysis.isPinned(MethodOverrideKey(app.internalName, "run", "()V")))
        assertEquals("run", plan.methodName(app.internalName, "run", "()V"))
        assertEquals("run_1", plan.methodName(app.internalName, "run", "()I"))
    }

    @Test
    fun `renames a whole application family when one declaration collides`() {
        val base = rawClass("example/Base", methods = listOf(method("get", "()Ljava/lang/Object;")))
        val child = rawClass(
            "example/Child",
            superName = base.internalName,
            methods = listOf(method("get", "()Ljava/lang/String;"), method("get", "()I")),
        )
        val analysis = analyzer(returnAssignable = ::covariantStrings).analyze(rawImport(base, child))
        val plan = DeobfuscationPlan.build(listOf(base, child), enabled = true, methodOverrides = analysis)

        assertEquals("get_1", plan.methodName(base.internalName, "get", "()Ljava/lang/Object;"))
        assertEquals("get_1", plan.methodName(child.internalName, "get", "()Ljava/lang/String;"))
        assertEquals("get_2", plan.methodName(child.internalName, "get", "()I"))
    }

    @Test
    fun `does not treat inaccessible package private method as override`() {
        val base = rawClass("first/Base", methods = listOf(method("hidden", "()V", 0)))
        val child = rawClass("second/Child", superName = base.internalName, methods = listOf(method("hidden", "()V")))
        val analysis = analyzer().analyze(rawImport(base, child))

        assertFalse(analysis.overridesSuperMethod(child.internalName, "hidden", "()V"))
        assertFalse(analysis.familyOf(MethodOverrideKey(base.internalName, "hidden", "()V")) == analysis.familyOf(MethodOverrideKey(child.internalName, "hidden", "()V")))
    }

    @Test
    fun `package private method is not inherited across an intermediate foreign package`() {
        val base = rawClass("first/Base", methods = listOf(method("hidden", "()V", 0)))
        val middle = rawClass("second/Middle", superName = base.internalName)
        val child = rawClass("first/Child", superName = middle.internalName, methods = listOf(method("hidden", "()V", ACC_PUBLIC)))
        val analysis = analyzer().analyze(rawImport(base, middle, child))

        assertFalse(analysis.overridesSuperMethod(child.internalName, "hidden", "()V"))
    }

    @Test
    fun `static and private methods never form override families`() {
        val base = rawClass(
            "example/Base",
            methods = listOf(method("staticMethod", "()V", ACC_PUBLIC or ACC_STATIC), method("privateMethod", "()V", ACC_PRIVATE)),
        )
        val child = rawClass(
            "example/Child",
            superName = base.internalName,
            methods = listOf(method("staticMethod", "()V", ACC_PUBLIC or ACC_STATIC), method("privateMethod", "()V", ACC_PUBLIC)),
        )
        val analysis = analyzer().analyze(rawImport(base, child))

        assertFalse(analysis.overridesSuperMethod(child.internalName, "staticMethod", "()V"))
        assertFalse(analysis.overridesSuperMethod(child.internalName, "privateMethod", "()V"))
    }

    private fun analyzer(
        external: Map<String, RawClass> = emptyMap(),
        returnAssignable: (JvmType, JvmType, String) -> Boolean? = { target, source, _ -> target == source },
    ) = MethodOverrideAnalyzer(external, returnAssignable)

    private fun covariantStrings(target: JvmType, source: JvmType, @Suppress("UNUSED_PARAMETER") consumer: String): Boolean =
        target == source || target == JvmType.ObjectType("java/lang/Object") && source == JvmType.ObjectType("java/lang/String")

    private fun rawImport(vararg classes: RawClass) = RawImportResult(
        classes = classes.associateBy { it.internalName },
        fieldCount = 0,
        methodCount = classes.sumOf { it.methods.size },
        methodsWithCode = 0,
        instructionCount = 0,
        unknownInstructionCount = 0,
        parseFailureCount = 0,
        warnings = emptyList(),
    )

    private fun rawClass(
        internalName: String,
        superName: String? = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        methods: List<RawMethod> = emptyList(),
        accessFlags: Int = 0,
    ) = RawClass(
        internalName = internalName,
        majorVersion = 65,
        minorVersion = 0,
        accessFlags = accessFlags,
        superName = superName,
        interfaces = interfaces,
        fields = emptyList(),
        methods = methods,
    )

    private fun method(name: String, descriptor: String, accessFlags: Int = ACC_PUBLIC) = RawMethod(
        name = name,
        descriptor = descriptor,
        type = JvmMethodDescriptor.parse(descriptor),
        accessFlags = accessFlags,
        exceptions = emptyList(),
        code = null,
    )

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
        const val ACC_STATIC = 0x0008
        const val ACC_INTERFACE = 0x0200
    }
}
