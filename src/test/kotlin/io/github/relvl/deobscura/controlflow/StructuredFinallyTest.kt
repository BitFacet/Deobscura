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
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StructuredFinallyTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: try { BODY } finally { CLEANUP }  // CLEANUP copied on normal and exceptional paths
    @Test
    fun `recognizes canonical finally from duplicated normal body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(7)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawNopInstruction(JvmOpcode("nop")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 7, emptyList(), emptyList()),
            BasicBlock(b3, 7, 8, emptyList(), emptyList()),
        )
        val labels = List(9) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 3, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(6, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.TryFinally>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(b2, region.handlerEntry)
        assertEquals(listOf(4..4), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..1), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { if (...) return; else return; } finally { CLEANUP }  // handler store/body/rethrow split
    @Test
    fun `recognizes split linear finally with terminal normal copies`() {
        val tryBlock = BasicBlockId(0)
        val normalLeft = BasicBlockId(1)
        val normalRight = BasicBlockId(2)
        val handlerEntry = BasicBlockId(3)
        val handlerBody = BasicBlockId(4)
        val edges = listOf(
            edge(tryBlock, normalRight, ControlFlowEdgeKind.CONDITIONAL),
            edge(tryBlock, normalLeft, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(handlerEntry, handlerBody, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(3)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalLeft, 1, 3, emptyList(), emptyList()),
            BasicBlock(normalRight, 3, 5, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 5, 6, emptyList(), emptyList()),
            BasicBlock(handlerBody, 6, 9, emptyList(), emptyList()),
        )
        val labels = List(10) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
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
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(2, null),
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(8, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(handlerEntry, handlerBody), region.handlerBlocks)
        assertEquals(listOf(6..6), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..1, 3..3), region.normalCopyInstructionIndices.sortedBy { it.first })
        assertEquals(setOf(normalLeft, normalRight), region.normalCopyBlocks)
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY; return; } finally { if (...) A else B }
    @Test
    fun `recognizes branching finally copied before terminal return`() {
        val tryBlock = BasicBlockId(0)
        val normalCopy = BasicBlockId(1)
        val handlerEntry = BasicBlockId(2)
        val handlerBody = BasicBlockId(3)
        val rethrow = BasicBlockId(4)
        val edges = listOf(
            edge(tryBlock, normalCopy, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(handlerEntry, handlerBody, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerBody, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalCopy, 1, 3, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 3, 4, emptyList(), emptyList()),
            BasicBlock(handlerBody, 4, 5, emptyList(), emptyList()),
            BasicBlock(rethrow, 5, 7, emptyList(), emptyList()),
        )
        val labels = List(8) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 3, null)),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(2, null),
                    ExpressionStatement.Throw(6, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(normalCopy), region.normalCopyBlocks)
        assertEquals(listOf(1..1), region.normalCopyInstructionIndices)
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } finally { if (...) A else B }  // branch graph duplicated normally
    @Test
    fun `recognizes branching finally from duplicated normal body`() {
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
            exceptionEdge(b0, b4, null),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b7, ControlFlowEdgeKind.CONDITIONAL),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b6, b7, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(4)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(9)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 4, emptyList(), emptyList()),
            BasicBlock(b3, 4, 5, emptyList(), emptyList()),
            BasicBlock(b4, 5, 6, emptyList(), emptyList()),
            BasicBlock(b5, 6, 8, emptyList(), emptyList()),
            BasicBlock(b6, 8, 9, emptyList(), emptyList()),
            BasicBlock(b7, 9, 11, emptyList(), emptyList()),
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
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(4, null),
                    ExpressionStatement.Throw(10, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6, b7), region.handlerBlocks)
        assertEquals(listOf(6..8), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..3), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1, b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } finally { BRANCHING_CLEANUP }  // astore ex shares first cleanup block
    @Test
    fun `recognizes branching finally when exception store shares body entry block`() {
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
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(5)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 1, emptyList(), emptyList()),
            BasicBlock(b1, 1, 3, emptyList(), emptyList()),
            BasicBlock(b2, 3, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 6, emptyList(), emptyList()),
            BasicBlock(b4, 6, 9, emptyList(), emptyList()),
            BasicBlock(b5, 9, 10, emptyList(), emptyList()),
            BasicBlock(b6, 10, 12, emptyList(), emptyList()),
        )
        val labels = List(13) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(0, 1, 6, null)),
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
                    ExpressionStatement.Return(5, null),
                    ExpressionStatement.Throw(11, ValueId(0)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(setOf(b4, b5, b6), region.handlerBlocks)
        assertEquals(listOf(7..9), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..4), region.normalCopyInstructionIndices)
        assertEquals(setOf(b1, b2), region.normalCopyBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } finally { CLEANUP }  // handler is CLEANUP; athrow with implicit Throwable still on stack
    @Test
    fun `recognizes finally with exception kept on operand stack`() {
        val tryBlock = BasicBlockId(0)
        val normalCopy = BasicBlockId(1)
        val handlerBlock = BasicBlockId(2)
        val edges = listOf(
            edge(tryBlock, normalCopy, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerBlock, null),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawNopInstruction(JvmOpcode("nop")),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalCopy, 1, 3, emptyList(), emptyList()),
            BasicBlock(handlerBlock, 3, 5, emptyList(), emptyList()),
        )
        val labels = List(6) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null, null, null, instructions, labels,
                listOf(exceptionHandler(0, 1, 3, null)),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(2, null),
                    ExpressionStatement.Throw(4, ValueId(0)),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(handlerBlock, region.handlerEntry)
        assertEquals(setOf(handlerBlock), region.handlerBlocks)
        assertEquals(listOf(3..3), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..1), region.normalCopyInstructionIndices)
        assertEquals(setOf(normalCopy), region.normalCopyBlocks)
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { CLEANUP; return; } finally { CLEANUP }  // normal copy is suffix inside protected block
    @Test
    fun `recognizes stack preserved finally copied before protected terminal return`() {
        val tryBlock = BasicBlockId(0)
        val handlerBlock = BasicBlockId(1)
        val edges = listOf(
            exceptionEdge(tryBlock, handlerBlock, null),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawNopInstruction(JvmOpcode("nop")),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 4, emptyList(), emptyList()),
            BasicBlock(handlerBlock, 4, 7, emptyList(), emptyList()),
        )
        val labels = List(8) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null, null, null, instructions, labels,
                listOf(exceptionHandler(0, 4, 4, null)),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(3, null),
                    ExpressionStatement.Throw(6, ValueId(0)),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(handlerBlock, region.handlerEntry)
        assertEquals(setOf(handlerBlock), region.handlerBlocks)
        assertEquals(listOf(4..5), region.finallyBodyInstructionRanges)
        assertEquals(listOf(1..2), region.normalCopyInstructionIndices)
        assertEquals(setOf(tryBlock), region.normalCopyBlocks)
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (IOException e) { CATCH } finally { if (flag) CLEANUP }
    @Test
    fun `recognizes modern try catch finally split around catch body`() {
        val tryBlock = BasicBlockId(0)
        val tryFinally = BasicBlockId(1)
        val tryFinallyTail = BasicBlockId(2)
        val catchEntry = BasicBlockId(3)
        val catchFinally = BasicBlockId(4)
        val catchFinallyTail = BasicBlockId(5)
        val handlerEntry = BasicBlockId(6)
        val handlerTail = BasicBlockId(7)
        val rethrow = BasicBlockId(8)
        val continuation = BasicBlockId(9)
        val edges = listOf(
            edge(tryBlock, tryFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/io/IOException"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(tryFinally, continuation, ControlFlowEdgeKind.CONDITIONAL),
            edge(tryFinally, tryFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryFinallyTail, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchEntry, catchFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchFinally, continuation, ControlFlowEdgeKind.CONDITIONAL),
            edge(catchFinally, catchFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchFinallyTail, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerEntry, rethrow, ControlFlowEdgeKind.CONDITIONAL),
            edge(handlerEntry, handlerTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerTail, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(14)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(14)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(12)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinally, 1, 3, emptyList(), emptyList()),
            BasicBlock(tryFinallyTail, 3, 4, emptyList(), emptyList()),
            BasicBlock(catchEntry, 4, 5, emptyList(), emptyList()),
            BasicBlock(catchFinally, 5, 7, emptyList(), emptyList()),
            BasicBlock(catchFinallyTail, 7, 8, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 8, 11, emptyList(), emptyList()),
            BasicBlock(handlerTail, 11, 12, emptyList(), emptyList()),
            BasicBlock(rethrow, 12, 14, emptyList(), emptyList()),
            BasicBlock(continuation, 14, 15, emptyList(), emptyList()),
        )
        val labels = List(16) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 4, "java/io/IOException"),
                    exceptionHandler(0, 1, 8, null),
                    exceptionHandler(4, 5, 8, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(13, ValueId(0)),
                    ExpressionStatement.Return(14, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(tryFinally, tryFinallyTail, catchFinally, catchFinallyTail), region.normalCopyBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: try { BODY } catch (IOException e) { throw WRAPPED } finally { try { CLEANUP } catch (IOException ignored) {} }
    @Test
    fun `ignores exceptional finally body covered by catch all peer range`() {
        val tryBlock = BasicBlockId(0)
        val normalCleanup = BasicBlockId(1)
        val normalCleanupCatch = BasicBlockId(2)
        val returnBlock = BasicBlockId(3)
        val catchEntry = BasicBlockId(4)
        val handlerEntry = BasicBlockId(5)
        val handlerCleanup = BasicBlockId(6)
        val handlerCleanupCatch = BasicBlockId(7)
        val rethrow = BasicBlockId(8)
        val edges = listOf(
            edge(tryBlock, normalCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/io/IOException"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(normalCleanup, returnBlock, ControlFlowEdgeKind.JUMP),
            exceptionEdge(normalCleanup, normalCleanupCatch, "java/io/IOException"),
            edge(normalCleanupCatch, returnBlock, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(handlerEntry, handlerCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerCleanup, rethrow, ControlFlowEdgeKind.JUMP),
            exceptionEdge(handlerCleanup, handlerCleanupCatch, "java/io/IOException"),
            edge(handlerCleanupCatch, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalCleanup, 1, 2, emptyList(), emptyList()),
            BasicBlock(normalCleanupCatch, 2, 3, emptyList(), emptyList()),
            BasicBlock(returnBlock, 3, 4, emptyList(), emptyList()),
            BasicBlock(catchEntry, 4, 5, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 5, 6, emptyList(), emptyList()),
            BasicBlock(handlerCleanup, 6, 7, emptyList(), emptyList()),
            BasicBlock(handlerCleanupCatch, 7, 8, emptyList(), emptyList()),
            BasicBlock(rethrow, 8, 10, emptyList(), emptyList()),
        )
        val labels = List(11) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 4, "java/io/IOException"),
                    exceptionHandler(0, 1, 5, null),
                    exceptionHandler(4, 6, 5, null),
                    exceptionHandler(1, 2, 2, "java/io/IOException"),
                    exceptionHandler(6, 7, 7, "java/io/IOException"),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(3, null),
                    ExpressionStatement.Throw(4, ValueId(0)),
                    ExpressionStatement.Throw(9, ValueId(1)),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatchFinally>().single()
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(normalCleanup), region.normalCopyBlocks)
        assertEquals(returnBlock, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: try { BODY; return } catch (Exception e) { CATCH; return } finally { CLEANUP }
    @Test
    fun `recognizes modern try catch finally with terminal linear cleanup copies`() {
        val tryBlock = BasicBlockId(0)
        val tryFinallyReturn = BasicBlockId(1)
        val catchEntry = BasicBlockId(2)
        val catchFinallyReturn = BasicBlockId(3)
        val handlerEntry = BasicBlockId(4)
        val edges = listOf(
            edge(tryBlock, tryFinallyReturn, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/lang/Exception"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(catchEntry, catchFinallyReturn, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinallyReturn, 1, 3, emptyList(), emptyList()),
            BasicBlock(catchEntry, 3, 4, emptyList(), emptyList()),
            BasicBlock(catchFinallyReturn, 4, 6, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 6, 10, emptyList(), emptyList()),
        )
        val labels = List(11) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 3, "java/lang/Exception"),
                    exceptionHandler(0, 1, 6, null),
                    exceptionHandler(3, 4, 6, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(2, null),
                    ExpressionStatement.Return(5, null),
                    ExpressionStatement.Throw(9, ValueId(0)),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(tryFinallyReturn, catchFinallyReturn), region.normalCopyBlocks)
        assertEquals(listOf(1..1, 4..4), region.normalCopyInstructionIndices.sortedBy { it.first })
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (Exception e) { CATCH } finally { if (flag) A; CLEANUP }
    @Test
    fun `recognizes branching modern try catch finally with cleanup before rethrow in same block`() {
        val tryBlock = BasicBlockId(0)
        val tryFinally = BasicBlockId(1)
        val tryFinallyBranch = BasicBlockId(2)
        val tryFinallyTail = BasicBlockId(3)
        val catchEntry = BasicBlockId(4)
        val catchFinally = BasicBlockId(5)
        val catchFinallyBranch = BasicBlockId(6)
        val catchFinallyTail = BasicBlockId(7)
        val handlerEntry = BasicBlockId(8)
        val handlerFinally = BasicBlockId(9)
        val handlerFinallyBranch = BasicBlockId(10)
        val rethrow = BasicBlockId(11)
        val continuation = BasicBlockId(12)
        val edges = listOf(
            edge(tryBlock, tryFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/lang/Exception"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(tryFinally, tryFinallyTail, ControlFlowEdgeKind.CONDITIONAL),
            edge(tryFinally, tryFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryFinallyBranch, tryFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryFinallyTail, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchEntry, catchFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchFinally, catchFinallyTail, ControlFlowEdgeKind.CONDITIONAL),
            edge(catchFinally, catchFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchFinallyBranch, catchFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchFinallyTail, continuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerFinally, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerFinally, rethrow, ControlFlowEdgeKind.CONDITIONAL),
            edge(handlerFinally, handlerFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerFinallyBranch, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(4)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(17)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(17)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(16)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinally, 1, 3, emptyList(), emptyList()),
            BasicBlock(tryFinallyBranch, 3, 4, emptyList(), emptyList()),
            BasicBlock(tryFinallyTail, 4, 6, emptyList(), emptyList()),
            BasicBlock(catchEntry, 6, 7, emptyList(), emptyList()),
            BasicBlock(catchFinally, 7, 9, emptyList(), emptyList()),
            BasicBlock(catchFinallyBranch, 9, 10, emptyList(), emptyList()),
            BasicBlock(catchFinallyTail, 10, 12, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 12, 13, emptyList(), emptyList()),
            BasicBlock(handlerFinally, 13, 15, emptyList(), emptyList()),
            BasicBlock(handlerFinallyBranch, 15, 16, emptyList(), emptyList()),
            BasicBlock(rethrow, 16, 19, emptyList(), emptyList()),
            BasicBlock(continuation, 19, 20, emptyList(), emptyList()),
        )
        val labels = List(21) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 6, "java/lang/Exception"),
                    exceptionHandler(0, 1, 12, null),
                    exceptionHandler(6, 7, 12, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(18, ValueId(0)),
                    ExpressionStatement.Return(19, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(handlerEntry, handlerFinally, handlerFinallyBranch, rethrow), region.handlerBlocks)
        assertEquals(listOf(13..16), region.finallyBodyInstructionRanges)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (Exception e) { CATCH } finally { CLEANUP }
    @Test
    fun `recognizes modern try catch finally with split store and merged cleanup rethrow`() {
        val tryBlock = BasicBlockId(0)
        val tryFinally = BasicBlockId(1)
        val catchEntry = BasicBlockId(2)
        val catchFinally = BasicBlockId(3)
        val handlerEntry = BasicBlockId(4)
        val handlerCleanupRethrow = BasicBlockId(5)
        val continuation = BasicBlockId(6)
        val edges = listOf(
            edge(tryBlock, tryFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/lang/Exception"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(tryFinally, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchEntry, catchFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchFinally, continuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerCleanupRethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(10)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinally, 1, 3, emptyList(), emptyList()),
            BasicBlock(catchEntry, 3, 4, emptyList(), emptyList()),
            BasicBlock(catchFinally, 4, 6, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 6, 7, emptyList(), emptyList()),
            BasicBlock(handlerCleanupRethrow, 7, 10, emptyList(), emptyList()),
            BasicBlock(continuation, 10, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 3, "java/lang/Exception"),
                    exceptionHandler(0, 1, 6, null),
                    exceptionHandler(3, 4, 6, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(9, ValueId(0)),
                    ExpressionStatement.Return(10, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(handlerEntry, handlerCleanupRethrow), region.handlerBlocks)
        assertEquals(listOf(7..7), region.finallyBodyInstructionRanges)
        assertEquals(setOf(tryFinally, catchFinally), region.normalCopyBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (Exception e) { CATCH } finally { CLEANUP }
    @Test
    fun `recognizes modern try catch finally with linear cleanup handler`() {
        val tryBlock = BasicBlockId(0)
        val tryFinally = BasicBlockId(1)
        val catchEntry = BasicBlockId(2)
        val catchFinally = BasicBlockId(3)
        val handlerEntry = BasicBlockId(4)
        val continuation = BasicBlockId(5)
        val edges = listOf(
            edge(tryBlock, tryFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/lang/Exception"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(tryFinally, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchEntry, catchFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchFinally, continuation, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinally, 1, 2, emptyList(), emptyList()),
            BasicBlock(catchEntry, 2, 3, emptyList(), emptyList()),
            BasicBlock(catchFinally, 3, 4, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 4, 8, emptyList(), emptyList()),
            BasicBlock(continuation, 8, 9, emptyList(), emptyList()),
        )
        val labels = List(10) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 2, "java/lang/Exception"),
                    exceptionHandler(0, 1, 4, null),
                    exceptionHandler(2, 3, 4, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(7, ValueId(0)),
                    ExpressionStatement.Return(8, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(handlerEntry), region.handlerBlocks)
        assertEquals(listOf(5..5), region.finallyBodyInstructionRanges)
        assertEquals(setOf(tryFinally, catchFinally), region.normalCopyBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: try { CLEANUP } catch (IOException e) { CATCH; if (flag) CLEANUP; return } finally { if (flag) CLEANUP }
    @Test
    fun `recognizes mixed finally with guard elided on proven normal path`() {
        val tryBlock = BasicBlockId(0)
        val specializedTryCopy = BasicBlockId(1)
        val catchEntry = BasicBlockId(2)
        val catchGuard = BasicBlockId(3)
        val catchCleanup = BasicBlockId(4)
        val catchReturnDirect = BasicBlockId(5)
        val catchReturnAfterCleanup = BasicBlockId(6)
        val handlerEntry = BasicBlockId(7)
        val handlerCleanup = BasicBlockId(8)
        val rethrow = BasicBlockId(9)
        val continuation = BasicBlockId(10)
        val edges = listOf(
            edge(tryBlock, specializedTryCopy, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/io/IOException"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(specializedTryCopy, continuation, ControlFlowEdgeKind.JUMP),
            edge(catchEntry, catchGuard, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchGuard, catchReturnDirect, ControlFlowEdgeKind.CONDITIONAL),
            edge(catchGuard, catchCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchCleanup, catchReturnAfterCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerEntry, rethrow, ControlFlowEdgeKind.CONDITIONAL),
            edge(handlerEntry, handlerCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerCleanup, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawBranchInstruction(JvmOpcode("ifnull"), RawLabelId(6)),
            RawNopInstruction(JvmOpcode("nop")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawBranchInstruction(JvmOpcode("ifnull"), RawLabelId(12)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(specializedTryCopy, 1, 2, emptyList(), emptyList()),
            BasicBlock(catchEntry, 2, 3, emptyList(), emptyList()),
            BasicBlock(catchGuard, 3, 5, emptyList(), emptyList()),
            BasicBlock(catchCleanup, 5, 6, emptyList(), emptyList()),
            BasicBlock(catchReturnDirect, 6, 7, emptyList(), emptyList()),
            BasicBlock(catchReturnAfterCleanup, 7, 8, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 8, 11, emptyList(), emptyList()),
            BasicBlock(handlerCleanup, 11, 12, emptyList(), emptyList()),
            BasicBlock(rethrow, 12, 14, emptyList(), emptyList()),
            BasicBlock(continuation, 14, 15, emptyList(), emptyList()),
        )
        val labels = List(16) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 2, "java/io/IOException"),
                    exceptionHandler(0, 1, 8, null),
                    exceptionHandler(2, 3, 8, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(6, null),
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(13, ValueId(0)),
                    ExpressionStatement.Return(14, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(handlerEntry, region.handlerEntry)
        assertEquals(setOf(specializedTryCopy, catchGuard, catchCleanup), region.normalCopyBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: finally { try { CLOSE } catch (Throwable suppressed) { RECORD } ; AFTER }
    @Test
    fun `recognizes split finally around nested typed handler without claiming it`() {
        val tryBlock = BasicBlockId(0)
        val normalCleanup = BasicBlockId(1)
        val normalNestedCatch = BasicBlockId(2)
        val normalAfter = BasicBlockId(3)
        val continuation = BasicBlockId(4)
        val handlerEntry = BasicBlockId(5)
        val handlerCleanup = BasicBlockId(6)
        val handlerNestedCatch = BasicBlockId(7)
        val handlerAfter = BasicBlockId(8)
        val rethrow = BasicBlockId(9)
        val edges = listOf(
            edge(tryBlock, normalCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(normalCleanup, normalAfter, ControlFlowEdgeKind.JUMP),
            exceptionEdge(normalCleanup, normalNestedCatch, "java/lang/Throwable"),
            edge(normalNestedCatch, continuation, ControlFlowEdgeKind.JUMP),
            edge(normalAfter, continuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerCleanup, handlerAfter, ControlFlowEdgeKind.JUMP),
            exceptionEdge(handlerCleanup, handlerNestedCatch, "java/lang/Throwable"),
            edge(handlerNestedCatch, rethrow, ControlFlowEdgeKind.JUMP),
            edge(handlerAfter, rethrow, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(6)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 7),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(8)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(8)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(15)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 8),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(17)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(17)),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalCleanup, 1, 3, emptyList(), emptyList()),
            BasicBlock(normalNestedCatch, 3, 6, emptyList(), emptyList()),
            BasicBlock(normalAfter, 6, 8, emptyList(), emptyList()),
            BasicBlock(continuation, 8, 9, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 9, 10, emptyList(), emptyList()),
            BasicBlock(handlerCleanup, 10, 12, emptyList(), emptyList()),
            BasicBlock(handlerNestedCatch, 12, 15, emptyList(), emptyList()),
            BasicBlock(handlerAfter, 15, 17, emptyList(), emptyList()),
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
                listOf(
                    exceptionHandler(0, 1, 9, null),
                    exceptionHandler(1, 3, 3, "java/lang/Throwable"),
                    exceptionHandler(10, 12, 12, "java/lang/Throwable"),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(8, null),
                    ExpressionStatement.Throw(18, ValueId(0)),
                ),
            ),
        )

        val finallyRegion = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(setOf(tryBlock), finallyRegion.tryBlocks)
        assertEquals(setOf(handlerEntry, handlerCleanup, handlerAfter, rethrow), finallyRegion.handlerBlocks)
        assertEquals(listOf(10..11, 15..16), finallyRegion.finallyBodyInstructionRanges)
        assertEquals(setOf(normalCleanup, normalAfter), finallyRegion.normalCopyBlocks)
        assertEquals(listOf(1..2, 6..7), finallyRegion.normalCopyInstructionIndices)
        assertEquals(continuation, finallyRegion.continuation)
        assertTrue(result.regions.filterIsInstance<StructuredRegion.TryCatch>().any { region ->
            normalNestedCatch in region.catches.flatMapTo(linkedSetOf()) { it.blocks } ||
                handlerNestedCatch in region.catches.flatMapTo(linkedSetOf()) { it.blocks }
        })
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: finally { try { CLOSE } catch (IOException e) { RECORD } ; AFTER }
    // The exceptional rethrow block contains AFTER before reloading and throwing the saved exception.
    @Test
    fun `recognizes nested catch rejoining cleanup prefix in rethrow block`() {
        val tryBlock = BasicBlockId(0)
        val normalCleanup = BasicBlockId(1)
        val normalNestedCatch = BasicBlockId(2)
        val normalAfter = BasicBlockId(3)
        val continuation = BasicBlockId(4)
        val handlerEntry = BasicBlockId(5)
        val handlerCleanup = BasicBlockId(6)
        val handlerNestedCatch = BasicBlockId(7)
        val rethrow = BasicBlockId(8)
        val edges = listOf(
            edge(tryBlock, normalCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(normalCleanup, normalAfter, ControlFlowEdgeKind.JUMP),
            exceptionEdge(normalCleanup, normalNestedCatch, "java/io/IOException"),
            edge(normalNestedCatch, normalAfter, ControlFlowEdgeKind.JUMP),
            edge(normalAfter, continuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerCleanup, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerCleanup, rethrow, ControlFlowEdgeKind.JUMP),
            exceptionEdge(handlerCleanup, handlerNestedCatch, "java/io/IOException"),
            edge(handlerNestedCatch, rethrow, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 7),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(7)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 8),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalCleanup, 1, 3, emptyList(), emptyList()),
            BasicBlock(normalNestedCatch, 3, 5, emptyList(), emptyList()),
            BasicBlock(normalAfter, 5, 7, emptyList(), emptyList()),
            BasicBlock(continuation, 7, 8, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 8, 9, emptyList(), emptyList()),
            BasicBlock(handlerCleanup, 9, 11, emptyList(), emptyList()),
            BasicBlock(handlerNestedCatch, 11, 13, emptyList(), emptyList()),
            BasicBlock(rethrow, 13, 16, emptyList(), emptyList()),
        )
        val labels = List(17) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 8, null),
                    exceptionHandler(1, 3, 3, "java/io/IOException"),
                    exceptionHandler(9, 11, 11, "java/io/IOException"),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(15, ValueId(0)),
                ),
            ),
        )

        val finallyRegion = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(setOf(tryBlock), finallyRegion.tryBlocks)
        assertEquals(setOf(handlerEntry, handlerCleanup, rethrow), finallyRegion.handlerBlocks)
        assertEquals(listOf(9..10, 13..13), finallyRegion.finallyBodyInstructionRanges)
        assertEquals(setOf(normalCleanup, normalAfter), finallyRegion.normalCopyBlocks)
        assertEquals(listOf(1..2, 5..6), finallyRegion.normalCopyInstructionIndices)
        assertEquals(continuation, finallyRegion.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


    // Pseudocode: finally { BEFORE; AFTER }, with nested catch-all handlers physically emitted
    // between the copied cleanup blocks on both normal and exceptional paths.
    @Test
    fun `recognizes finally copies split by exception-only handler islands`() {
        val tryBlock = BasicBlockId(0)
        val normalBefore = BasicBlockId(1)
        val normalGapHandler = BasicBlockId(2)
        val normalAfter = BasicBlockId(3)
        val continuation = BasicBlockId(4)
        val handlerEntry = BasicBlockId(5)
        val handlerBefore = BasicBlockId(6)
        val handlerGapHandler = BasicBlockId(7)
        val handlerAfter = BasicBlockId(8)
        val rethrow = BasicBlockId(9)
        val edges = listOf(
            edge(tryBlock, normalBefore, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(normalBefore, normalAfter, ControlFlowEdgeKind.JUMP),
            exceptionEdge(normalBefore, normalGapHandler, null),
            edge(normalAfter, continuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerBefore, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerBefore, handlerAfter, ControlFlowEdgeKind.JUMP),
            exceptionEdge(handlerBefore, handlerGapHandler, null),
            edge(handlerAfter, rethrow, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 7),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(7)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(13)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 8),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(15)),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(normalBefore, 1, 3, emptyList(), emptyList()),
            BasicBlock(normalGapHandler, 3, 5, emptyList(), emptyList()),
            BasicBlock(normalAfter, 5, 7, emptyList(), emptyList()),
            BasicBlock(continuation, 7, 8, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 8, 9, emptyList(), emptyList()),
            BasicBlock(handlerBefore, 9, 11, emptyList(), emptyList()),
            BasicBlock(handlerGapHandler, 11, 13, emptyList(), emptyList()),
            BasicBlock(handlerAfter, 13, 15, emptyList(), emptyList()),
            BasicBlock(rethrow, 15, 17, emptyList(), emptyList()),
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
                    exceptionHandler(0, 1, 8, null),
                    exceptionHandler(1, 3, 3, null),
                    exceptionHandler(9, 11, 11, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(4, ValueId(1)),
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(12, ValueId(2)),
                    ExpressionStatement.Throw(16, ValueId(0)),
                ),
            ),
        )

        val finallyRegion = result.regions.filterIsInstance<StructuredRegion.TryFinally>().single()
        assertEquals(setOf(tryBlock), finallyRegion.tryBlocks)
        assertEquals(setOf(handlerEntry, handlerBefore, handlerAfter, rethrow), finallyRegion.handlerBlocks)
        assertEquals(listOf(9..10, 13..14), finallyRegion.finallyBodyInstructionRanges)
        assertEquals(setOf(normalBefore, normalAfter), finallyRegion.normalCopyBlocks)
        assertEquals(listOf(1..2, 5..6), finallyRegion.normalCopyInstructionIndices)
        assertEquals(continuation, finallyRegion.continuation)

        // The two physical gap handlers remain owned by their own exception-table groups.
        assertEquals(2, result.unstructuredExceptionRegionCount)
    }


    // One source try/catch/finally may resume different normal flows after the same cleanup copy.
    @Test
    fun `recognizes branching modern try catch finally with distinct normal continuations`() {
        val tryBlock = BasicBlockId(0)
        val tryFinally = BasicBlockId(1)
        val tryFinallyBranch = BasicBlockId(2)
        val tryFinallyTail = BasicBlockId(3)
        val catchEntry = BasicBlockId(4)
        val catchFinally = BasicBlockId(5)
        val catchFinallyBranch = BasicBlockId(6)
        val catchFinallyTail = BasicBlockId(7)
        val handlerEntry = BasicBlockId(8)
        val handlerFinally = BasicBlockId(9)
        val handlerFinallyBranch = BasicBlockId(10)
        val rethrow = BasicBlockId(11)
        val tryContinuation = BasicBlockId(12)
        val catchContinuation = BasicBlockId(13)
        val exit = BasicBlockId(14)
        val edges = listOf(
            edge(tryBlock, tryFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/lang/Exception"),
            exceptionEdge(tryBlock, handlerEntry, null),
            edge(tryFinally, tryFinallyTail, ControlFlowEdgeKind.CONDITIONAL),
            edge(tryFinally, tryFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryFinallyBranch, tryFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryFinallyTail, tryContinuation, ControlFlowEdgeKind.JUMP),
            edge(catchEntry, catchFinally, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(catchEntry, handlerEntry, null),
            edge(catchFinally, catchFinallyTail, ControlFlowEdgeKind.CONDITIONAL),
            edge(catchFinally, catchFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchFinallyBranch, catchFinallyTail, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchFinallyTail, catchContinuation, ControlFlowEdgeKind.JUMP),
            edge(handlerEntry, handlerFinally, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerFinally, rethrow, ControlFlowEdgeKind.CONDITIONAL),
            edge(handlerFinally, handlerFinallyBranch, ControlFlowEdgeKind.FALLTHROUGH),
            edge(handlerFinallyBranch, rethrow, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryContinuation, exit, ControlFlowEdgeKind.JUMP),
            edge(catchContinuation, exit, ControlFlowEdgeKind.JUMP),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(4)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(19)),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(10)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(21)),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 1),
            RawLocalInstruction(JvmOpcode("iload"), LocalOperation.LOAD, JvmComputationalType.INT, 2),
            RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(16)),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(23)),
            RawNopInstruction(JvmOpcode("nop")),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(23)),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
        )
        val blocks = listOf(
            BasicBlock(tryBlock, 0, 1, emptyList(), emptyList()),
            BasicBlock(tryFinally, 1, 3, emptyList(), emptyList()),
            BasicBlock(tryFinallyBranch, 3, 4, emptyList(), emptyList()),
            BasicBlock(tryFinallyTail, 4, 6, emptyList(), emptyList()),
            BasicBlock(catchEntry, 6, 7, emptyList(), emptyList()),
            BasicBlock(catchFinally, 7, 9, emptyList(), emptyList()),
            BasicBlock(catchFinallyBranch, 9, 10, emptyList(), emptyList()),
            BasicBlock(catchFinallyTail, 10, 12, emptyList(), emptyList()),
            BasicBlock(handlerEntry, 12, 13, emptyList(), emptyList()),
            BasicBlock(handlerFinally, 13, 15, emptyList(), emptyList()),
            BasicBlock(handlerFinallyBranch, 15, 16, emptyList(), emptyList()),
            BasicBlock(rethrow, 16, 19, emptyList(), emptyList()),
            BasicBlock(tryContinuation, 19, 21, emptyList(), emptyList()),
            BasicBlock(catchContinuation, 21, 23, emptyList(), emptyList()),
            BasicBlock(exit, 23, 24, emptyList(), emptyList()),
        )
        val labels = List(25) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(0, 1, 6, "java/lang/Exception"),
                    exceptionHandler(0, 1, 12, null),
                    exceptionHandler(6, 7, 12, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            tryBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, tryBlock),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Throw(18, ValueId(0)),
                    ExpressionStatement.Return(23, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatchFinally>(result.regions.single())
        assertEquals(setOf(tryBlock), region.tryBlocks)
        assertEquals(setOf(catchEntry), region.catches.single().blocks)
        assertEquals(setOf(tryFinally, tryFinallyBranch, tryFinallyTail, catchFinally, catchFinallyBranch, catchFinallyTail), region.normalCopyBlocks)
        assertEquals(null, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }


}
