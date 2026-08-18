package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.raw.*
import java.lang.constant.ConstantDescs
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredLegacyFinallyTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: try { BODY } finally { try { CLEANUP } catch (...) { ... } }  // JSR-era copies
    @Test
    fun `recognizes legacy finally family with recursive exception ownership`() {
        val anchor = BasicBlockId(0)
        val nestedTry = BasicBlockId(1)
        val callFinally = BasicBlockId(2)
        val normalFinally = BasicBlockId(3)
        val typedCatch = BasicBlockId(4)
        val catchAll = BasicBlockId(5)
        val exceptionalFinally = BasicBlockId(6)
        val rethrow = BasicBlockId(7)
        val protectedBridge = BasicBlockId(8)
        val continuation = BasicBlockId(9)
        val copyCatch = BasicBlockId(10)
        val nestedCopyCatch = BasicBlockId(11)
        val edges = listOf(
            edge(anchor, nestedTry, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(anchor, catchAll, null),
            edge(nestedTry, callFinally, ControlFlowEdgeKind.JUMP),
            exceptionEdge(nestedTry, typedCatch, "java/lang/Exception"),
            exceptionEdge(nestedTry, catchAll, null),
            edge(typedCatch, callFinally, ControlFlowEdgeKind.JUMP),
            exceptionEdge(typedCatch, catchAll, null),
            edge(callFinally, normalFinally, ControlFlowEdgeKind.JUMP),
            edge(normalFinally, protectedBridge, ControlFlowEdgeKind.JUMP),
            exceptionEdge(normalFinally, copyCatch, "java/lang/Exception"),
            edge(copyCatch, protectedBridge, ControlFlowEdgeKind.JUMP),
            exceptionEdge(copyCatch, nestedCopyCatch, "java/lang/RuntimeException"),
            edge(nestedCopyCatch, protectedBridge, ControlFlowEdgeKind.JUMP),
            exceptionEdge(protectedBridge, catchAll, null),
            edge(protectedBridge, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchAll, exceptionalFinally, ControlFlowEdgeKind.JUMP),
            edge(exceptionalFinally, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(4)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(14)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
        )
        val blocks = listOf(
            BasicBlock(anchor, 0, 1, emptyList(), emptyList()),
            BasicBlock(nestedTry, 1, 2, emptyList(), emptyList()),
            BasicBlock(callFinally, 2, 4, emptyList(), emptyList()),
            BasicBlock(normalFinally, 4, 6, emptyList(), emptyList()),
            BasicBlock(typedCatch, 6, 7, emptyList(), emptyList()),
            BasicBlock(catchAll, 7, 10, emptyList(), emptyList()),
            BasicBlock(exceptionalFinally, 10, 11, emptyList(), emptyList()),
            BasicBlock(rethrow, 11, 13, emptyList(), emptyList()),
            BasicBlock(protectedBridge, 13, 14, emptyList(), emptyList()),
            BasicBlock(continuation, 14, 15, emptyList(), emptyList()),
            BasicBlock(copyCatch, 15, 16, emptyList(), emptyList()),
            BasicBlock(nestedCopyCatch, 16, 17, emptyList(), emptyList()),
        )
        val labels = List(18) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 7, null),
                    exceptionHandler(1, 2, 6, "java/lang/Exception"),
                    exceptionHandler(1, 2, 7, null),
                    exceptionHandler(6, 7, 7, null),
                    exceptionHandler(13, 14, 7, null),
                    exceptionHandler(4, 6, 15, "java/lang/Exception"),
                    exceptionHandler(15, 16, 16, "java/lang/RuntimeException"),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            anchor,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, anchor),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(12, ValueId(0)),
                    ExpressionStatement.Return(14, null),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val outer = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(anchor, outer.header)
        assertEquals(setOf(anchor, nestedTry, typedCatch, protectedBridge), outer.tryBlocks)
        assertEquals(setOf(normalFinally), outer.normalCopyBlocks)
        assertEquals(continuation, outer.continuation)
        val nested = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == nestedTry }
        assertEquals(nestedTry, nested.header)
        assertEquals(setOf(nestedTry), nested.tryBlocks)
        assertEquals(setOf(typedCatch), nested.catches.single().blocks)
        assertEquals(callFinally, nested.continuation)
        val copyCleanup = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == normalFinally }
        assertEquals(setOf(copyCatch, nestedCopyCatch), copyCleanup.catches.single().blocks)
        assertEquals(protectedBridge, copyCleanup.continuation)
        val nestedCopyCleanup = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == copyCatch }
        assertEquals(setOf(nestedCopyCatch), nestedCopyCleanup.catches.single().blocks)
        assertEquals(protectedBridge, nestedCopyCleanup.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { ... } catch (E e) { ... } finally { CLEANUP }  // copied cleanup joins via gotos
    @Test
    fun `recognizes legacy try catch finally copies through converging goto trampolines`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val b8 = BasicBlockId(8)
        val b9 = BasicBlockId(9)
        val b10 = BasicBlockId(10)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, "java/lang/Exception"),
            exceptionEdge(b0, b7, null),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b6, ControlFlowEdgeKind.JUMP),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b4, b7, null),
            edge(b5, b8, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b8, b6, ControlFlowEdgeKind.JUMP),
            edge(b7, b9, ControlFlowEdgeKind.JUMP),
            edge(b9, b10, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(9)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(8)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(9)),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 8, emptyList(), emptyList()),
            BasicBlock(b5, 8, 9, emptyList(), emptyList()),
            BasicBlock(b6, 9, 10, emptyList(), emptyList()),
            BasicBlock(b7, 10, 13, emptyList(), emptyList()),
            BasicBlock(b8, 16, 17, emptyList(), emptyList()),
            BasicBlock(b9, 13, 14, emptyList(), emptyList()),
            BasicBlock(b10, 14, 16, emptyList(), emptyList()),
        )
        val labels = List(18) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 5, "java/lang/Exception"),
                    exceptionHandler(0, 1, 10, null),
                    exceptionHandler(5, 8, 10, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..10).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(9, null),
                    ExpressionStatement.Throw(15, ValueId(0)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatchFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4), region.catches.single().blocks)
        assertEquals(setOf(b7, b9, b10), region.handlerBlocks)
        assertEquals(setOf(b2, b5), region.normalCopyBlocks)
        assertEquals(b6, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { ... } catch (E e) { ... } finally { CLEANUP }  // exception table split into peers
    @Test
    fun `recognizes legacy try catch finally split across identical mixed ranges`() {
        val b0 = BasicBlockId(0)
        val gap = BasicBlockId(1)
        val b1 = BasicBlockId(2)
        val call = BasicBlockId(3)
        val normalFinally = BasicBlockId(4)
        val typedCatch = BasicBlockId(5)
        val catchFinally = BasicBlockId(6)
        val catchAll = BasicBlockId(7)
        val exceptionalFinally = BasicBlockId(8)
        val rethrow = BasicBlockId(9)
        val continuation = BasicBlockId(10)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b0, typedCatch, "java/lang/Exception"),
            exceptionEdge(b0, catchAll, null),
            edge(b1, call, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, typedCatch, "java/lang/Exception"),
            exceptionEdge(b1, catchAll, null),
            edge(call, normalFinally, ControlFlowEdgeKind.JUMP),
            edge(normalFinally, continuation, ControlFlowEdgeKind.JUMP),
            edge(typedCatch, catchFinally, ControlFlowEdgeKind.JUMP),
            exceptionEdge(typedCatch, catchAll, null),
            edge(catchFinally, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchAll, exceptionalFinally, ControlFlowEdgeKind.JUMP),
            edge(exceptionalFinally, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(11)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(15)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(18)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(18)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(gap, 1, 2, emptyList(), emptyList()),
            BasicBlock(b1, 2, 3, emptyList(), emptyList()),
            BasicBlock(call, 3, 5, emptyList(), emptyList()),
            BasicBlock(typedCatch, 5, 8, emptyList(), emptyList()),
            BasicBlock(catchAll, 8, 11, emptyList(), emptyList()),
            BasicBlock(normalFinally, 11, 13, emptyList(), emptyList()),
            BasicBlock(catchFinally, 13, 15, emptyList(), emptyList()),
            BasicBlock(exceptionalFinally, 15, 16, emptyList(), emptyList()),
            BasicBlock(rethrow, 16, 18, emptyList(), emptyList()),
            BasicBlock(continuation, 18, 19, emptyList(), emptyList()),
        )
        val labels = List(20) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 5, "java/lang/Exception"),
                    exceptionHandler(0, 1, 8, null),
                    exceptionHandler(2, 3, 5, "java/lang/Exception"),
                    exceptionHandler(2, 3, 8, null),
                    exceptionHandler(5, 8, 8, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(17, ValueId(0)),
                    ExpressionStatement.Return(18, null),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatchFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0, b1), region.tryBlocks)
        assertEquals(setOf(typedCatch), region.catches.single().blocks)
        assertEquals(setOf(catchAll, exceptionalFinally, rethrow), region.handlerBlocks)
        assertEquals(setOf(normalFinally, catchFinally), region.normalCopyBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } finally { CLEANUP }  // jsr/ret normalized into cloned cleanup
    @Test
    fun `recognizes legacy jsr finally shape after normalization`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, null),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(8)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 8, emptyList(), emptyList()),
            BasicBlock(b5, 8, 9, emptyList(), emptyList()),
            BasicBlock(b6, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 5, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(10, ValueId(0)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6), region.handlerBlocks)
        assertEquals(listOf(8..8), region.finallyBodyInstructionRanges)
        assertEquals(listOf(3..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } finally { CLEANUP }  // normalized JSR copies span multiple physical ranges
    @Test
    fun `recognizes legacy jsr finally with physically split copies`() {
        val b0 = BasicBlockId(0)
        val call = BasicBlockId(1)
        val normalA = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val catchAll = BasicBlockId(4)
        val handlerA = BasicBlockId(5)
        val handlerB = BasicBlockId(6)
        val rethrow = BasicBlockId(7)
        val normalB = BasicBlockId(8)
        val edges = listOf(
            edge(b0, call, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, catchAll, null),
            edge(call, normalA, ControlFlowEdgeKind.JUMP),
            edge(normalA, continuation, ControlFlowEdgeKind.CONDITIONAL),
            edge(normalA, normalB, ControlFlowEdgeKind.JUMP),
            edge(normalB, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchAll, handlerA, ControlFlowEdgeKind.JUMP),
            edge(handlerA, rethrow, ControlFlowEdgeKind.CONDITIONAL),
            edge(handlerA, handlerB, ControlFlowEdgeKind.JUMP),
            edge(handlerB, rethrow, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(6)),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(15)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawConstantInstruction(JvmOpcode("aconst_null"), JvmComputationalType.REFERENCE, ConstantDescs.NULL),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(17)),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(6)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(17)),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(call, 1, 3, emptyList(), emptyList()),
            BasicBlock(normalA, 3, 6, emptyList(), emptyList()),
            BasicBlock(continuation, 6, 7, emptyList(), emptyList()),
            BasicBlock(catchAll, 7, 10, emptyList(), emptyList()),
            BasicBlock(handlerA, 10, 13, emptyList(), emptyList()),
            BasicBlock(normalB, 13, 15, emptyList(), emptyList()),
            BasicBlock(handlerB, 15, 17, emptyList(), emptyList()),
            BasicBlock(rethrow, 17, 19, emptyList(), emptyList()),
        )
        val labels = List(20) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 7, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(6, null),
                    ExpressionStatement.Throw(18, ValueId(0)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(catchAll, handlerA, handlerB, rethrow), region.handlerBlocks)
        assertEquals(listOf(10..12, 15..16), region.finallyBodyInstructionRanges)
        assertEquals(setOf(normalA, normalB), region.normalCopyBlocks)
        assertEquals(listOf(3..5, 13..14), region.normalCopyInstructionIndices)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (E e) { CATCH } finally { CLEANUP }  // normalized JSR/RET
    @Test
    fun `recognizes legacy jsr try catch finally shape after normalization`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, "java/lang/Exception"),
            exceptionEdge(b0, b5, null),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b4, b5, null),
            edge(b5, b6, ControlFlowEdgeKind.JUMP),
            edge(b6, b7, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawConstantInstruction(
                JvmOpcode("aconst_null"),
                JvmComputationalType.REFERENCE,
                ConstantDescs.NULL,
            ),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 7, emptyList(), emptyList()),
            BasicBlock(b5, 7, 10, emptyList(), emptyList()),
            BasicBlock(b6, 10, 11, emptyList(), emptyList()),
            BasicBlock(b7, 11, 13, emptyList(), emptyList()),
        )
        val labels = List(14) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 5, "java/lang/Exception"),
                    exceptionHandler(0, 1, 7, null),
                    exceptionHandler(5, 7, 7, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(6, ValueId(0)),
                    ExpressionStatement.Throw(12, ValueId(1)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatchFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(listOf("java/lang/Exception"), region.catches.single().catchTypes)
        assertEquals(setOf(b4), region.catches.single().blocks)
        assertEquals(setOf(b5, b6, b7), region.handlerBlocks)
        assertEquals(listOf(10..10), region.finallyBodyInstructionRanges)
        assertEquals(listOf(3..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }
}
