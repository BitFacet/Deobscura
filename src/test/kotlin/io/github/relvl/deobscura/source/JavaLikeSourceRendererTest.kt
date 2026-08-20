package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalyzer
import io.github.relvl.deobscura.resolution.MethodOverrideAnalyzer
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
        assertFalse(rendered.contains("return;"))
    }

    @Test
    fun `omits semantically empty constructor chain and trailing return`() {
        val noArg = constructor(
            descriptor = "()V",
            maxLocals = 1,
            instructions = listOf(
                loadThis(),
                constructorCall("java/lang/Object"),
                voidReturn(),
            ),
            accessFlags = ACC_PRIVATE,
        )
        val delegating = constructor(
            descriptor = "(Ljava/lang/Object;)V",
            maxLocals = 2,
            instructions = listOf(
                loadThis(),
                constructorCall("example/Sample"),
                voidReturn(),
            ),
        )
        val rawClass = sampleClass(listOf(noArg, delegating))
        val analyses = rawClass.methods.associate { method ->
            SourceMethodKey(method.name, method.descriptor) to MethodAnalyzer().analyze(rawClass.internalName, method)
        }

        val rendered = JavaLikeSourceRenderer().renderClass(rawClass, analyses)

        assertFalse(rendered.contains("super();"))
        assertFalse(rendered.contains("this();"))
        assertFalse(rendered.contains("return;"))
    }

    @Test
    fun `keeps delegation to nontrivial no arg constructor`() {
        val noArg = constructor(
            descriptor = "()V",
            maxLocals = 1,
            instructions = listOf(
                loadThis(),
                constructorCall("java/lang/Object"),
                RawInvokeInstruction(
                    JvmOpcode("invokestatic"),
                    "example/Sample",
                    "touch",
                    "()V",
                    JvmMethodDescriptor.parse("()V"),
                    false,
                ),
                voidReturn(),
            ),
            accessFlags = ACC_PRIVATE,
        )
        val delegating = constructor(
            descriptor = "(Ljava/lang/Object;)V",
            maxLocals = 2,
            instructions = listOf(
                loadThis(),
                constructorCall("example/Sample"),
                voidReturn(),
            ),
        )
        val rawClass = sampleClass(listOf(noArg, delegating))
        val analyses = rawClass.methods.associate { method ->
            SourceMethodKey(method.name, method.descriptor) to MethodAnalyzer().analyze(rawClass.internalName, method)
        }

        val rendered = JavaLikeSourceRenderer().renderClass(rawClass, analyses)

        assertFalse(rendered.contains("super();"))
        assertContains(rendered, "this();")
        assertFalse(rendered.contains("return;"))
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


    private fun constructor(
        descriptor: String,
        maxLocals: Int,
        instructions: List<RawInstruction>,
        accessFlags: Int = 0,
    ) = RawMethod(
        name = "<init>",
        descriptor = descriptor,
        type = JvmMethodDescriptor.parse(descriptor),
        accessFlags = accessFlags,
        exceptions = emptyList(),
        code = RawCode(
            maxStack = 1,
            maxLocals = maxLocals,
            bytecodeLength = instructions.size,
            instructions = instructions,
            labels = emptyList(),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        ),
    )

    private fun loadThis() = RawLocalInstruction(
        JvmOpcode("aload"),
        LocalOperation.LOAD,
        JvmComputationalType.REFERENCE,
        0,
    )

    private fun constructorCall(owner: String) = RawInvokeInstruction(
        JvmOpcode("invokespecial"),
        owner,
        "<init>",
        "()V",
        JvmMethodDescriptor.parse("()V"),
        false,
    )

    private fun voidReturn() = RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID)

    private fun sampleClass(methods: List<RawMethod>) = RawClass(
        internalName = "example/Sample",
        majorVersion = 65,
        minorVersion = 0,
        accessFlags = ACC_PUBLIC,
        superName = "java/lang/Object",
        interfaces = emptyList(),
        fields = emptyList(),
        methods = methods,
    )

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
    }
}
