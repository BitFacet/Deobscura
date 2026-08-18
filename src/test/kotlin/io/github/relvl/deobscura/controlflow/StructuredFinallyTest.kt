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

}
