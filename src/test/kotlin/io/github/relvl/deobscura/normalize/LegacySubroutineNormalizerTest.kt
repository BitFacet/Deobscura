package io.github.relvl.deobscura.normalize

import io.github.relvl.deobscura.analysis.FrameAnalyzer
import io.github.relvl.deobscura.analysis.FrameValueKind
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.raw.*
import kotlin.test.*

class LegacySubroutineNormalizerTest {
    private val normalizer = LegacySubroutineNormalizer()
    private val graphBuilder = ControlFlowGraphBuilder()
    private val frameAnalyzer = FrameAnalyzer()

    @Test
    fun `returns original code when method has no legacy subroutines`() {
        val code = RawCode(
            maxStack = 0,
            maxLocals = 0,
            bytecodeLength = null,
            instructions = listOf(RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID)),
            labels = emptyList(),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )

        val result = normalizer.normalize(code)

        assertSame(code, result.code)
        assertFalse(result.changed)
    }

    @Test
    fun `expands shared jsr subroutine per call site and preserves caller locals`() {
        val subroutine = RawLabelId(1)
        val code = RawCode(
            maxStack = 1,
            maxLocals = 3,
            bytecodeLength = null,
            instructions = listOf(
                RawNewObjectInstruction(JvmOpcode("new"), "java/lang/Object"),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                RawBranchInstruction(JvmOpcode("jsr"), subroutine),
                RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
                RawStackInstruction(JvmOpcode("pop")),
                RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 0),
                RawLocalInstruction(JvmOpcode("istore"), LocalOperation.STORE, JvmComputationalType.INT, 1),
                RawBranchInstruction(JvmOpcode("jsr"), subroutine),
                RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 1),
                RawStackInstruction(JvmOpcode("pop")),
                RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
                RawRetInstruction(JvmOpcode("ret"), 2),
            ),
            labels = listOf(RawLabel(subroutine, instructionIndex = 11, bytecodeOffset = null)),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )

        val result = normalizer.normalize(code)

        assertTrue(result.changed)
        assertEquals(2, result.jsrCallSiteCount)
        assertTrue(result.clonedBlockCount > 0)
        val provenance = assertNotNull(result.provenance)
        assertEquals(result.code.instructions.size, provenance.instructionOrigins.size)
        assertEquals(2, provenance.instructionOrigins.count { it.syntheticKind == LegacySyntheticInstructionKind.JSR_GOTO })
        assertEquals(2, provenance.instructionOrigins.count { it.syntheticKind == LegacySyntheticInstructionKind.JSR_NULL_SEED })
        assertTrue(provenance.instructionOrigins.any { it.syntheticKind == LegacySyntheticInstructionKind.RET_GOTO })
        assertTrue(provenance.instructionOrigins.any { it.context.frames.isNotEmpty() })
        assertTrue(result.code.instructions.none { it is RawRetInstruction })
        assertTrue(
            result.code.instructions.none {
                it is RawBranchInstruction && it.opcode.mnemonic in setOf("jsr", "jsr_w")
            },
        )

        val method = RawMethod(
            name = "legacyFinally",
            descriptor = "(I)V",
            type = JvmMethodDescriptor.parse("(I)V"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = result.code,
        )
        val graph = graphBuilder.build(result.code)
        val analysis = frameAnalyzer.analyze("test/Owner", method, graph)

        val referenceLoadBlock = graph.blocks.single { block ->
            result.code.instructions[block.startInstructionIndex] is RawLocalInstruction && (result.code.instructions[block.startInstructionIndex] as RawLocalInstruction).opcode.mnemonic == "aload"
        }
        val intLoadBlocks = graph.blocks.filter { block ->
            result.code.instructions[block.startInstructionIndex] is RawLocalInstruction && (result.code.instructions[block.startInstructionIndex] as RawLocalInstruction).opcode.mnemonic == "iload"
        }
        val secondReturnSite = intLoadBlocks.last()

        assertEquals(FrameValueKind.REFERENCE, analysis.entryFrames.getValue(referenceLoadBlock.id).locals[1]?.kind)
        assertEquals(FrameValueKind.INT, analysis.entryFrames.getValue(secondReturnSite.id).locals[1]?.kind)
    }

    @Test
    fun `allows jsr target to reach return-address capture through control flow`() {
        val entry = RawLabelId(1)
        val capture = RawLabelId(2)
        val code = RawCode(
            maxStack = 1,
            maxLocals = 1,
            bytecodeLength = null,
            instructions = listOf(
                RawBranchInstruction(JvmOpcode("jsr"), entry),
                RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
                RawBranchInstruction(JvmOpcode("goto"), capture),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 0),
                RawRetInstruction(JvmOpcode("ret"), 0),
            ),
            labels = listOf(
                RawLabel(entry, instructionIndex = 2, bytecodeOffset = null),
                RawLabel(capture, instructionIndex = 3, bytecodeOffset = null),
            ),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )

        val result = normalizer.normalize(code)

        assertEquals(1, result.jsrCallSiteCount)
        assertTrue(result.code.instructions.none { it is RawRetInstruction })
        assertTrue(
            result.code.instructions.none {
                it is RawBranchInstruction && it.opcode.mnemonic in setOf("jsr", "jsr_w")
            },
        )

        val method = RawMethod(
            name = "delayedCapture",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = result.code,
        )
        val graph = graphBuilder.build(result.code)
        frameAnalyzer.analyze("test/Owner", method, graph)
    }

    @Test
    fun `normalizes nested legacy subroutines`() {
        val outer = RawLabelId(1)
        val inner = RawLabelId(2)
        val code = RawCode(
            maxStack = 1,
            maxLocals = 3,
            bytecodeLength = null,
            instructions = listOf(
                RawBranchInstruction(JvmOpcode("jsr"), outer),
                RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
                RawBranchInstruction(JvmOpcode("jsr"), inner),
                RawRetInstruction(JvmOpcode("ret"), 1),
                RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
                RawRetInstruction(JvmOpcode("ret"), 2),
            ),
            labels = listOf(
                RawLabel(outer, instructionIndex = 2, bytecodeOffset = null),
                RawLabel(inner, instructionIndex = 5, bytecodeOffset = null),
            ),
            exceptionHandlers = emptyList(),
            lineNumbers = emptyList(),
        )

        val result = normalizer.normalize(code)

        assertEquals(2, result.jsrCallSiteCount)
        assertTrue(result.code.instructions.none { it is RawRetInstruction })
        assertTrue(
            result.code.instructions.none {
                it is RawBranchInstruction && it.opcode.mnemonic in setOf("jsr", "jsr_w")
            },
        )

        val method = RawMethod(
            name = "nestedFinally",
            descriptor = "()V",
            type = JvmMethodDescriptor.parse("()V"),
            accessFlags = 0x0008,
            exceptions = emptyList(),
            code = result.code,
        )
        val graph = graphBuilder.build(result.code)
        frameAnalyzer.analyze("test/Owner", method, graph)
    }

}
