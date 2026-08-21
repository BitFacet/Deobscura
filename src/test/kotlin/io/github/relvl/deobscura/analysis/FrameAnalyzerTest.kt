package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.resolution.ClassHierarchy
import io.github.relvl.deobscura.resolution.ClassResolver
import io.github.relvl.deobscura.resolution.RuntimeClassSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameAnalyzerTest {
    private val importer = ClassImporter()
    private val graphBuilder = ControlFlowGraphBuilder()
    private val analyzer = FrameAnalyzer()

    @Test
    fun `propagates and merges values through branches`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "choose" }
        val graph = graphBuilder.build(requireNotNull(method.code))

        val analysis = analyzer.analyze(rawClass.internalName, method, graph)

        assertEquals(graph.blocks.size, analysis.entryFrames.size)
        assertTrue(analysis.frameMergeCount > 0)
        assertTrue(analysis.valueMergeCount > 0)
    }

    @Test
    fun `handles array length wide values and exception handlers`() {
        val rawClass = importFixture()

        listOf("wideAndArray", "withHandler").forEach { methodName ->
            val method = rawClass.methods.single { it.name == methodName }
            val graph = graphBuilder.build(requireNotNull(method.code))
            val analysis = analyzer.analyze(rawClass.internalName, method, graph)

            assertTrue(analysis.entryFrames.isNotEmpty())
        }
    }

    @Test
    fun `merges incompatible local kinds to unavailable`() {
        val elseLabel = RawLabelId(1)
        val joinLabel = RawLabelId(2)
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
            RawBranchInstruction(JvmOpcode("ifeq"), elseLabel),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
            RawLocalInstruction(JvmOpcode("istore"), LocalOperation.STORE, JvmComputationalType.INT, 1),
            RawBranchInstruction(JvmOpcode("goto"), joinLabel),
            RawNewObjectInstruction(JvmOpcode("new"), "java/lang/Object"),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val code = RawCode(
            maxStack = 1,
            maxLocals = 2,
            bytecodeLength = null,
            instructions = instructions,
            labels = listOf(
                RawLabel(elseLabel, instructionIndex = 5, bytecodeOffset = null),
                RawLabel(joinLabel, instructionIndex = 7, bytecodeOffset = null),
            ),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )
        val method = RawMethod(
            name = "reuseLocal",
            descriptor = "(Z)V",
            type = JvmMethodDescriptor.parse("(Z)V"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = code,
        )
        val graph = graphBuilder.build(code)

        val analysis = analyzer.analyze("test/Owner", method, graph)
        val joinBlock = graph.blocks.single { it.startInstructionIndex == 7 }

        assertEquals(null, analysis.entryFrames.getValue(joinBlock.id).locals[1])
    }

    @Test
    fun `analyzes legacy jsr ret subroutines without merging caller locals`() {
        val subroutineLabel = RawLabelId(1)
        val instructions = listOf(
            RawNewObjectInstruction(JvmOpcode("new"), "java/lang/Object"),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawBranchInstruction(JvmOpcode("jsr"), subroutineLabel),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawStackInstruction(JvmOpcode("pop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
            RawLocalInstruction(JvmOpcode("istore"), LocalOperation.STORE, JvmComputationalType.INT, 1),
            RawBranchInstruction(JvmOpcode("jsr"), subroutineLabel),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 1),
            RawStackInstruction(JvmOpcode("pop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawRetInstruction(JvmOpcode("ret"), 2),
        )
        val code = RawCode(
            maxStack = 1,
            maxLocals = 3,
            bytecodeLength = null,
            instructions = instructions,
            labels = listOf(RawLabel(subroutineLabel, instructionIndex = 11, bytecodeOffset = null)),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )
        val method = RawMethod(
            name = "legacyFinally",
            descriptor = "(I)V",
            type = JvmMethodDescriptor.parse("(I)V"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = code,
        )
        val graph = graphBuilder.build(code)

        val analysis = analyzer.analyze("test/Owner", method, graph)

        val firstReturnSite = graph.blocks.single { it.startInstructionIndex == 3 }
        val secondReturnSite = graph.blocks.single { it.startInstructionIndex == 8 }
        assertEquals(FrameValueKind.REFERENCE, analysis.entryFrames.getValue(firstReturnSite.id).locals[1]?.kind)
        assertEquals(FrameValueKind.INT, analysis.entryFrames.getValue(secondReturnSite.id).locals[1]?.kind)
    }

    @Test
    fun `merges exact reference types through the class hierarchy`() {
        val elseLabel = RawLabelId(1)
        val joinLabel = RawLabelId(2)
        val code = RawCode(
            maxStack = 1,
            maxLocals = 2,
            bytecodeLength = null,
            instructions = listOf(
                RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
                RawBranchInstruction(JvmOpcode("ifeq"), elseLabel),
                RawNewObjectInstruction(JvmOpcode("new"), "java/lang/String"),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                RawBranchInstruction(JvmOpcode("goto"), joinLabel),
                RawNewObjectInstruction(JvmOpcode("new"), "java/lang/StringBuilder"),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                RawReturnInstruction(JvmOpcode("areturn"), JvmComputationalType.REFERENCE),
            ),
            labels = listOf(
                RawLabel(elseLabel, instructionIndex = 5, bytecodeOffset = null),
                RawLabel(joinLabel, instructionIndex = 7, bytecodeOffset = null),
            ),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )
        val method = RawMethod(
            name = "chooseReference",
            descriptor = "(Z)Ljava/lang/Object;",
            type = JvmMethodDescriptor.parse("(Z)Ljava/lang/Object;"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = code,
        )
        val graph = graphBuilder.build(code)

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtime ->
            val resolver = ClassResolver(emptyJarResult(), runtime)
            val analysis = FrameAnalyzer(ClassHierarchy(resolver)).analyze("test/Owner", method, graph)
            val joinBlock = graph.blocks.single { it.startInstructionIndex == 7 }
            val merged = requireNotNull(analysis.entryFrames.getValue(joinBlock.id).locals[1])

            assertEquals(
                JvmReferenceType.Exact(JvmType.ObjectType("java/lang/Object")),
                merged.referenceType,
            )
            assertTrue(analysis.referenceMergeCount > 0)
            assertEquals(0, analysis.impreciseReferenceMergeCount)
        }
    }

    private fun emptyJarResult() = JarLoadResult(
        classes = emptyMap(),
        inputClassCount = 0,
        classpathClassCount = 0,
        classpathOnlyClassCount = 0,
        shadowedClasspathClassCount = 0,
        warnings = emptyList(),
    )

    private fun importFixture(): RawClass {
        val type = FrameFixture::class.java
        val internalName = type.name.replace('.', '/')
        val bytes = requireNotNull(type.getResourceAsStream("/$internalName.class")).use { it.readAllBytes() }
        return importer.importClass(bytes)
    }
}

private class FrameFixture {
    fun choose(flag: Boolean): Int {
        val value = if (flag) 10 else 20
        return value + 1
    }

    fun wideAndArray(values: IntArray, base: Long): Long = values.size.toLong() + base

    fun withHandler(value: String?): Int = try {
        value!!.length
    } catch (_: NullPointerException) {
        -1
    }
}
