package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalyzer
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.resolution.MethodOverrideAnalyzer
import java.lang.constant.ConstantDescs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `renders unused value returning call as statement expression`() {
        val method = RawMethod(
            name = "run",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 0,
                bytecodeLength = 3,
                instructions = listOf(
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "make",
                        "()Ljava/lang/Object;",
                        JvmMethodDescriptor.parse("()Ljava/lang/Object;"),
                        false,
                    ),
                    RawStackInstruction(JvmOpcode("pop")),
                    voidReturn(),
                ),
                labels = emptyList(),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "example.Factory.make();")
        assertFalse(Regex("""var v\d+ = example\.Factory\.make\(\);""").containsMatchIn(rendered), rendered)
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

    @Test
    fun `hoists value defined in try when it is used after catch`() {
        val tryStart = RawLabelId(0)
        val tryEnd = RawLabelId(1)
        val handler = RawLabelId(2)
        val continuation = RawLabelId(3)
        val exit = RawLabelId(4)
        val method = RawMethod(
            name = "read",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 2,
                bytecodeLength = 8,
                instructions = listOf(
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "make",
                        "()Ljava/lang/Object;",
                        JvmMethodDescriptor.parse("()Ljava/lang/Object;"),
                        false,
                    ),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 0),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "use",
                        "(Ljava/lang/Object;)V",
                        JvmMethodDescriptor.parse("(Ljava/lang/Object;)V"),
                        false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), exit),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                    voidReturn(),
                    voidReturn(),
                ),
                labels = listOf(
                    RawLabel(tryStart, 0, 0),
                    RawLabel(tryEnd, 2, 2),
                    RawLabel(continuation, 2, 2),
                    RawLabel(handler, 5, 5),
                    RawLabel(exit, 7, 7),
                ),
                exceptionHandlers = listOf(RawExceptionHandler(tryStart, tryEnd, handler, "java/lang/Exception")),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "java.lang.Object v0;\n        try {")
        assertContains(rendered, "v0 = example.Factory.make();")
        assertFalse(rendered.contains("var v0 = example.Factory.make();"))
        assertContains(rendered, "example.Factory.use(v0);")
    }

    @Test
    fun `inlines effectful single use value evaluated by loop header into condition`() {
        val header = RawLabelId(0)
        val exit = RawLabelId(1)
        val method = RawMethod(
            name = "scan",
            descriptor = "(Ljava/util/Iterator;)V",
            type = JvmMethodDescriptor.parse("(Ljava/util/Iterator;)V"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 1,
                bytecodeLength = 6,
                instructions = listOf(
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawInvokeInstruction(
                        JvmOpcode("invokeinterface"),
                        "java/util/Iterator",
                        "hasNext",
                        "()Z",
                        JvmMethodDescriptor.parse("()Z"),
                        true,
                    ),
                    RawBranchInstruction(JvmOpcode("ifeq"), exit),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "touch",
                        "()V",
                        JvmMethodDescriptor.parse("()V"),
                        false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), header),
                    voidReturn(),
                ),
                labels = listOf(
                    RawLabel(header, 0, 0),
                    RawLabel(exit, 5, 5),
                ),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "while (arg0.hasNext()) {")
        assertFalse(rendered.contains("= arg0.hasNext();"))
    }

    @Test
    fun `keeps effectful condition value materialized when it is defined before loop`() {
        val header = RawLabelId(0)
        val exit = RawLabelId(1)
        val method = RawMethod(
            name = "scan",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 1,
                bytecodeLength = 7,
                instructions = listOf(
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "check",
                        "()Z",
                        JvmMethodDescriptor.parse("()Z"),
                        false,
                    ),
                    RawLocalInstruction(JvmOpcode("istore"), LocalOperation.STORE, JvmComputationalType.INT, 0),
                    RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
                    RawBranchInstruction(JvmOpcode("ifeq"), exit),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "touch",
                        "()V",
                        JvmMethodDescriptor.parse("()V"),
                        false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), header),
                    voidReturn(),
                ),
                labels = listOf(
                    RawLabel(header, 2, 2),
                    RawLabel(exit, 6, 6),
                ),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "= example.Factory.check();")
        assertFalse(rendered.contains("while (example.Factory.check())"))
    }

    @Test
    fun `renders diagnostic fallback with explicit exception flow and boundary labels`() {
        val tryStart = RawLabelId(0)
        val tryEnd = RawLabelId(1)
        val handler = RawLabelId(2)
        val continuation = RawLabelId(3)
        val method = RawMethod(
            name = "fallback",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 1,
                bytecodeLength = 7,
                instructions = listOf(
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"),
                        "example/Factory",
                        "risk",
                        "()V",
                        JvmMethodDescriptor.parse("()V"),
                        false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), continuation),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 0),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawInvokeInstruction(
                        JvmOpcode("invokevirtual"),
                        "java/lang/Exception",
                        "printStackTrace",
                        "()V",
                        JvmMethodDescriptor.parse("()V"),
                        false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), continuation),
                    voidReturn(),
                ),
                labels = listOf(
                    RawLabel(tryStart, 0, 0),
                    RawLabel(tryEnd, 2, 2),
                    RawLabel(handler, 2, 2),
                    RawLabel(continuation, 6, 6),
                ),
                exceptionHandlers = listOf(RawExceptionHandler(tryStart, tryEnd, handler, "java/lang/Exception")),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analyzed = MethodAnalyzer().analyze(rawClass.internalName, method)
        val tryBlock = analyzed.graph.blocks.single { it.startInstructionIndex == 0 }
        val handlerBlock = analyzed.graph.blocks.single { it.startInstructionIndex == 2 }
        val continuationBlock = analyzed.graph.blocks.single { it.startInstructionIndex == 6 }
        val fallbackBlocks = setOf(tryBlock.id, handlerBlock.id)
        val analysis = analyzed.copy(
            sourceStructure = SourceStructureAnalysis(
                root = SourceBlock(
                    ownedBlocks = fallbackBlocks + continuationBlock.id,
                    nodes = listOf(
                        SourceNode.ProjectionFallback(
                            tryBlock.id,
                            SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK,
                            SourceProvenance(setOf(tryBlock.id)),
                        ),
                        SourceNode.ProjectionFallback(
                            handlerBlock.id,
                            SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK,
                            SourceProvenance(setOf(handlerBlock.id)),
                        ),
                        SourceNode.BasicBlock(
                            continuationBlock.id,
                            SourceProvenance(setOf(continuationBlock.id)),
                        ),
                    ),
                ),
                accountedBlocks = fallbackBlocks + continuationBlock.id,
                consumptions = emptyList(),
                issues = listOf(SourceProjectionIssue(SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK, fallbackBlocks)),
            ),
        )

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "/* exception java.lang.Exception -> B${handlerBlock.id.value} */")
        assertContains(rendered, "java.lang.Exception v")
        assertContains(rendered, "= /* caught exception */;")
        assertContains(rendered, "B${continuationBlock.id.value}:")
        assertFalse(rendered.contains("caught.printStackTrace()"), rendered)
    }

    @Test
    fun `renders loop local assignment on phi predecessor instead of value definition`() {
        val outerLoop = RawLabelId(0)
        val innerLoop = RawLabelId(1)
        val join = RawLabelId(2)
        val exit = RawLabelId(3)
        val method = RawMethod(
            name = "find",
            descriptor = "(Ljava/util/Iterator;)Ljava/lang/Object;",
            type = JvmMethodDescriptor.parse("(Ljava/util/Iterator;)Ljava/lang/Object;"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 2,
                maxLocals = 5,
                bytecodeLength = 25,
                instructions = listOf(
                    RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawInvokeInstruction(JvmOpcode("invokeinterface"), "java/util/Iterator", "hasNext", "()Z", JvmMethodDescriptor.parse("()Z"), true),
                    RawBranchInstruction(JvmOpcode("ifeq"), exit),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawInvokeInstruction(JvmOpcode("invokeinterface"), "java/util/Iterator", "next", "()Ljava/lang/Object;", JvmMethodDescriptor.parse("()Ljava/lang/Object;"), true),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
                    RawInvokeInstruction(JvmOpcode("invokestatic"), "example/Factory", "children", "(Ljava/lang/Object;)Ljava/util/Iterator;", JvmMethodDescriptor.parse("(Ljava/lang/Object;)Ljava/util/Iterator;"), false),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
                    RawInvokeInstruction(JvmOpcode("invokeinterface"), "java/util/Iterator", "hasNext", "()Z", JvmMethodDescriptor.parse("()Z"), true),
                    RawBranchInstruction(JvmOpcode("ifeq"), outerLoop),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
                    RawInvokeInstruction(JvmOpcode("invokeinterface"), "java/util/Iterator", "next", "()Ljava/lang/Object;", JvmMethodDescriptor.parse("()Ljava/lang/Object;"), true),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 4),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 4),
                    RawInvokeInstruction(JvmOpcode("invokestatic"), "example/Factory", "matches", "(Ljava/lang/Object;)Z", JvmMethodDescriptor.parse("(Ljava/lang/Object;)Z"), false),
                    RawBranchInstruction(JvmOpcode("ifeq"), join),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                    RawBranchInstruction(JvmOpcode("goto"), innerLoop),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                    RawReturnInstruction(JvmOpcode("areturn"), JvmComputationalType.REFERENCE),
                ),
                labels = listOf(
                    RawLabel(outerLoop, 2, 2),
                    RawLabel(innerLoop, 11, 11),
                    RawLabel(join, 22, 22),
                    RawLabel(exit, 23, 23),
                ),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)
        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertContains(rendered, "example.Factory.matches")
        val conditionalAssignment = Regex(
            """if \([^)]*\) \{\s*v\d+ = v\d+;\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(conditionalAssignment.containsMatchIn(rendered), rendered)
        val matchesIndex = rendered.indexOf("example.Factory.matches")
        val assignmentIndex = conditionalAssignment.find(rendered)!!.range.first
        assertTrue(assignmentIndex > matchesIndex, rendered)
    }

    @Test
    fun `generic source variable lowering keeps multi-use phi inputs at their JVM store positions`() {
        val elseLabel = RawLabelId(0)
        val joinLabel = RawLabelId(1)
        val method = RawMethod(
            name = "choose",
            descriptor = "(Z)Ljava/lang/Object;",
            type = JvmMethodDescriptor.parse("(Z)Ljava/lang/Object;"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 1,
                maxLocals = 2,
                bytecodeLength = 13,
                instructions = listOf(
                    RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
                    RawBranchInstruction(JvmOpcode("ifeq"), elseLabel),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"), "example/Factory", "a", "()Ljava/lang/Object;",
                        JvmMethodDescriptor.parse("()Ljava/lang/Object;"), false,
                    ),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"), "example/Factory", "consume", "(Ljava/lang/Object;)V",
                        JvmMethodDescriptor.parse("(Ljava/lang/Object;)V"), false,
                    ),
                    RawBranchInstruction(JvmOpcode("goto"), joinLabel),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"), "example/Factory", "b", "()Ljava/lang/Object;",
                        JvmMethodDescriptor.parse("()Ljava/lang/Object;"), false,
                    ),
                    RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                    RawInvokeInstruction(
                        JvmOpcode("invokestatic"), "example/Factory", "consume", "(Ljava/lang/Object;)V",
                        JvmMethodDescriptor.parse("(Ljava/lang/Object;)V"), false,
                    ),
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                    RawReturnInstruction(JvmOpcode("areturn"), JvmComputationalType.REFERENCE),
                ),
                labels = listOf(
                    RawLabel(elseLabel, 7, 7),
                    RawLabel(joinLabel, 11, 11),
                ),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analysis = MethodAnalyzer().analyze(rawClass.internalName, method)
        val variable = analysis.sourceVariables.variables.values.single()
        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertTrue(analysis.sourceVariables.unresolvedNormalPhiValues.isEmpty(), analysis.sourceVariables.toString())
        assertTrue(analysis.sourceVariables.assignmentsAtInstruction.keys.containsAll(listOf(3, 8)))
        assertContains(rendered, "java.lang.Object v${variable.id.value};")
        assertContains(rendered, "return v${variable.id.value};")
        assertFalse(rendered.contains("/* phi */"), rendered)

        val firstFactory = rendered.indexOf("example.Factory.a()")
        val firstAssignment = rendered.indexOf("v${variable.id.value} =", firstFactory)
        val firstConsume = rendered.indexOf("example.Factory.consume", firstFactory)
        assertTrue(firstFactory >= 0 && firstFactory < firstAssignment && firstAssignment < firstConsume, rendered)
    }

    @Test
    fun `renders source variable edge copy only inside matching fallback branch`() {
        val joinLabel = RawLabelId(0)
        val method = RawMethod(
            name = "chooseEdge",
            descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;",
            type = JvmMethodDescriptor.parse("(Ljava/lang/Object;)Ljava/lang/Object;"),
            accessFlags = ACC_PUBLIC or ACC_STATIC,
            exceptions = emptyList(),
            code = RawCode(
                maxStack = 2,
                maxLocals = 1,
                bytecodeLength = 6,
                instructions = listOf(
                    RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 0),
                    RawStackInstruction(JvmOpcode("dup")),
                    RawBranchInstruction(JvmOpcode("ifnonnull"), joinLabel),
                    RawStackInstruction(JvmOpcode("pop")),
                    RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
                    RawReturnInstruction(JvmOpcode("areturn"), JvmComputationalType.REFERENCE),
                ),
                labels = listOf(RawLabel(joinLabel, 5, 5)),
                exceptionHandlers = emptyList(),
                lineNumbers = emptyList(),
            ),
        )
        val rawClass = sampleClass(listOf(method))
        val analyzed = MethodAnalyzer().analyze(rawClass.internalName, method)
        val fallbackStructure = SourceStructureAnalysis(
            root = SourceBlock(
                ownedBlocks = analyzed.optimization.controlFlow.blocks,
                nodes = analyzed.graph.blocks
                    .filter { it.id in analyzed.optimization.controlFlow.blocks }
                    .map { block ->
                        SourceNode.ProjectionFallback(
                            block.id,
                            SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK,
                            SourceProvenance(setOf(block.id)),
                        )
                    },
            ),
            accountedBlocks = analyzed.optimization.controlFlow.blocks,
            consumptions = emptyList(),
        )
        val rewrites = SourceRewriteAnalysis()
        val sourceVariables = SourceVariableAnalyzer().analyze(
            graph = analyzed.graph,
            ssa = analyzed.ssa,
            expression = analyzed.expression,
            controlFlow = analyzed.optimization.controlFlow,
            sourceStructure = fallbackStructure,
            preferredRewrites = rewrites,
        )
        val edgeAssignment = sourceVariables.assignments.single { it.site is SourceVariableAssignmentSite.Edge }
        val edgeSite = edgeAssignment.site as SourceVariableAssignmentSite.Edge
        val analysis = analyzed.copy(
            sourceStructure = fallbackStructure,
            sourceRewrites = rewrites,
            sourceVariables = sourceVariables,
        )

        val rendered = JavaLikeSourceRenderer().renderClass(
            rawClass,
            mapOf(SourceMethodKey(method.name, method.descriptor) to analysis),
        )

        assertTrue(sourceVariables.unresolvedNormalPhiValues.isEmpty(), sourceVariables.toString())
        assertFalse(rendered.contains("/* phi */"), rendered)
        assertContains(rendered, "v${edgeAssignment.variable.value} = arg0;")
        val branchWithCopy = Regex(
            """if \([^)]*\) \{\s*v${edgeAssignment.variable.value} = arg0;\s*goto B${edgeSite.to.value};\s*}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(branchWithCopy.containsMatchIn(rendered), rendered)
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
        const val ACC_STATIC = 0x0008
    }
}
