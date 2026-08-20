package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalyzer
import io.github.relvl.deobscura.resolution.MethodOverrideAnalyzer
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertContains

class JavaLikeSourceRendererTest {
    @Test
    fun `renders analyzed method through source structure`() {
        val method = RawMethod(
            name = "run",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 0,
                maxLocals = 1,
                bytecodeLength = 1,
                instructions = listOf(RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID)),
                labels = emptyList(),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = RawClass(
            internalName = "example/Sample",
            majorVersion = 65,
            minorVersion = 0,
            accessFlags = ACC_PUBLIC,
            superName = "java/lang/Object",
            interfaces = emptyList(),
            fields = emptyList(),
            methods = listOf(method),
        )
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "package example;")
        assertContains(rendered, "public class Sample {")
        assertContains(rendered, "public void run() {")
        assertContains(rendered, "return;")
    }

    @Test
    fun `renders override annotation from hierarchy facts`() {
        val baseMethod = RawMethod(
            name = "run",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC,
            exceptions = emptyList(),
            code = null,
        )
        val base = RawClass(
            internalName = "example/Base",
            majorVersion = 65,
            minorVersion = 0,
            accessFlags = ACC_PUBLIC,
            superName = "java/lang/Object",
            interfaces = emptyList(),
            fields = emptyList(),
            methods = listOf(baseMethod),
        )
        val child = base.copy(internalName = "example/Child", superName = base.internalName)
        val rawImport = RawImportResult(
            classes = listOf(base, child).associateBy { it.internalName },
            fieldCount = 0,
            methodCount = 2,
            methodsWithCode = 0,
            instructionCount = 0,
            unknownInstructionCount = 0,
            parseFailureCount = 0,
            warnings = emptyList(),
        )
        val overrides = MethodOverrideAnalyzer(emptyMap()).analyze(rawImport)

        val rendered = JavaLikeSourceRenderer(methodOverrides = overrides).renderClass(child, emptyMap())

        assertContains(rendered, "@Override\n    public void run();")
    }

    private companion object {
        const val ACC_PUBLIC = 0x0001
    }
}
