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
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StructuredSynchronizedTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: synchronized (m) { BODY }  // astore ex; aload m; monitorexit; aload ex; athrow
    @Test
    fun `recognizes canonical synchronized monitor cleanup`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, null),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b3, b3, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 2, emptyList(), emptyList()),
            BasicBlock(b1, 2, 5, emptyList(), emptyList()),
            BasicBlock(b2, 5, 6, emptyList(), emptyList()),
            BasicBlock(b3, 6, 9, emptyList(), emptyList()),
            BasicBlock(b4, 9, 11, emptyList(), emptyList()),
        )
        val labels = List(12) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(2, 9, 6, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(10, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(b3, region.handlerEntry)
        assertEquals(setOf(b3, b4), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(1, region.monitorEnterInstructionIndex)
        assertEquals(listOf(4), region.normalMonitorExitInstructionIndices)
        assertEquals(8, region.handlerMonitorExitInstructionIndex)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: m = expr; synchronized (m) { BODY }  // astore m; aload m; monitorenter
    @Test
    fun `recognizes synchronized monitor loaded from stored local before monitorenter`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b2, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 7, emptyList(), emptyList()),
            BasicBlock(b2, 7, 12, emptyList(), emptyList()),
        )
        val labels = List(13) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(exceptionHandler(3, 7, 7, null)),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(11, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(2, region.monitorEnterInstructionIndex)
        assertEquals(listOf(5), region.normalMonitorExitInstructionIndices)
        assertEquals(9, region.handlerMonitorExitInstructionIndex)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: synchronized (m) { BODY }  // cleanup handler is itself covered by companion catch-all range
    @Test
    fun `absorbs self-protecting monitor cleanup range into synchronized region`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, null),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b3, b3, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 2, emptyList(), emptyList()),
            BasicBlock(b1, 2, 5, emptyList(), emptyList()),
            BasicBlock(b2, 5, 6, emptyList(), emptyList()),
            BasicBlock(b3, 6, 9, emptyList(), emptyList()),
            BasicBlock(b4, 9, 11, emptyList(), emptyList()),
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
                    exceptionHandler(2, 5, 6, null),
                    exceptionHandler(6, 9, 6, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(10, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(setOf(b1), region.bodyBlocks)
        assertEquals(
            listOf(StructuredProtectedRange(6, 9)),
            region.syntheticCleanupProtectedRanges,
        )
        assertEquals(2, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: synchronized (m) { A; B; }  // A and B are separate protected fragments with cleanup copies
    @Test
    fun `recognizes synchronized body fragmented across catch-all handler copies`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val h1 = BasicBlockId(4)
        val h2 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, h1, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, h2, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 4),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 4),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 4, emptyList(), emptyList()),
            BasicBlock(b2, 4, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 8, emptyList(), emptyList()),
            BasicBlock(h1, 8, 13, emptyList(), emptyList()),
            BasicBlock(h2, 13, 18, emptyList(), emptyList()),
        )
        val labels = List(19) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null,
                null,
                null,
                instructions,
                labels,
                listOf(
                    exceptionHandler(3, 4, 8, null),
                    exceptionHandler(4, 5, 13, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, h1, h2), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(12, ValueId(0)),
                    ExpressionStatement.Throw(17, ValueId(1)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1, b2, b3), region.bodyBlocks)
        assertEquals(setOf(h1, h2), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(2, region.monitorEnterInstructionIndex)
        assertEquals(listOf(6), region.normalMonitorExitInstructionIndices)
        assertEquals(
            listOf(
                StructuredProtectedRange(3, 4),
                StructuredProtectedRange(4, 5),
            ),
            region.protectedRanges,
        )
        assertEquals(2, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: synchronized (m) { try { A } catch (E e) { H } B }  // H rejoins B; fragments have monitor cleanup
    @Test
    fun `recognizes fragmented synchronized body containing a rejoining typed catch`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val nestedHandler = BasicBlockId(4)
        val h1 = BasicBlockId(5)
        val h2 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, nestedHandler, "java/lang/Exception"),
            exceptionEdge(b1, h1, null),
            edge(nestedHandler, b2, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, h2, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 5),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 4),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 4),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 4, emptyList(), emptyList()),
            BasicBlock(b2, 4, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 8, emptyList(), emptyList()),
            BasicBlock(nestedHandler, 8, 10, emptyList(), emptyList()),
            BasicBlock(h1, 10, 15, emptyList(), emptyList()),
            BasicBlock(h2, 15, 20, emptyList(), emptyList()),
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
                    exceptionHandler(3, 4, 8, "java/lang/Exception"),
                    exceptionHandler(3, 4, 10, null),
                    exceptionHandler(4, 5, 15, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, nestedHandler, h1, h2), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(14, ValueId(0)),
                    ExpressionStatement.Throw(19, ValueId(1)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1, b2, b3, nestedHandler), region.bodyBlocks)
        assertEquals(setOf(h1, h2), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(2, region.monitorEnterInstructionIndex)
        assertEquals(listOf(6), region.normalMonitorExitInstructionIndices)
        assertEquals(2, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: synchronized (m) { try { A } catch (E e) { throw e } B }
    @Test
    fun `recognizes fragmented synchronized body containing a terminal throwing typed catch`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val nestedHandler = BasicBlockId(4)
        val h1 = BasicBlockId(5)
        val h2 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, nestedHandler, "java/lang/Exception"),
            exceptionEdge(b1, h1, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, h2, null),
            exceptionEdge(nestedHandler, h1, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 4),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 4),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 4, emptyList(), emptyList()),
            BasicBlock(b2, 4, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 8, emptyList(), emptyList()),
            BasicBlock(nestedHandler, 8, 9, emptyList(), emptyList()),
            BasicBlock(h1, 9, 14, emptyList(), emptyList()),
            BasicBlock(h2, 14, 19, emptyList(), emptyList()),
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
                    exceptionHandler(3, 4, 8, "java/lang/Exception"),
                    exceptionHandler(3, 4, 9, null),
                    exceptionHandler(4, 5, 14, null), // The nested throwing handler is itself protected by the already-proven monitor cleanup.
                    // A later typed entry is physically present but shadowed by the leading catch-all.
                    exceptionHandler(8, 9, 9, null),
                    exceptionHandler(8, 9, 14, "java/lang/Exception"),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, nestedHandler, h1, h2), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(8, ValueId(0)),
                    ExpressionStatement.Throw(13, ValueId(1)),
                    ExpressionStatement.Throw(18, ValueId(2)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1, b2, b3, nestedHandler), region.bodyBlocks)
        assertEquals(setOf(h1, h2), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(2, region.monitorEnterInstructionIndex)
        assertEquals(listOf(6), region.normalMonitorExitInstructionIndices)
        assertEquals(3, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { synchronized (m) { A; B; } } catch (E e) { return; }
    // The enclosing catch shares the first physical synchronized fragment after exception-table splitting.
    @Test
    fun `ignores enclosing typed handler sharing a fragmented synchronized range`() {
        val pre = BasicBlockId(0)
        val enter = BasicBlockId(1)
        val b1 = BasicBlockId(2)
        val b2 = BasicBlockId(3)
        val b3 = BasicBlockId(4)
        val outerHandler = BasicBlockId(5)
        val h1 = BasicBlockId(6)
        val h2 = BasicBlockId(7)
        val edges = listOf(
            edge(pre, enter, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(pre, outerHandler, "java/lang/Exception"),
            edge(enter, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, outerHandler, "java/lang/Exception"),
            exceptionEdge(b1, h1, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, h2, null),
        )
        val instructions = listOf(
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 4),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 4),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(pre, 0, 1, emptyList(), emptyList()),
            BasicBlock(enter, 1, 4, emptyList(), emptyList()),
            BasicBlock(b1, 4, 5, emptyList(), emptyList()),
            BasicBlock(b2, 5, 6, emptyList(), emptyList()),
            BasicBlock(b3, 6, 9, emptyList(), emptyList()),
            BasicBlock(outerHandler, 9, 10, emptyList(), emptyList()),
            BasicBlock(h1, 10, 15, emptyList(), emptyList()),
            BasicBlock(h2, 15, 20, emptyList(), emptyList()),
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
                    exceptionHandler(0, 1, 9, "java/lang/Exception"),
                    exceptionHandler(4, 5, 10, null),
                    exceptionHandler(4, 5, 9, "java/lang/Exception"),
                    exceptionHandler(5, 6, 15, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            pre,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(pre, enter, b1, b2, b3, outerHandler, h1, h2), edges, pre),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(8, null),
                    ExpressionStatement.Return(9, null),
                    ExpressionStatement.Throw(14, ValueId(0)),
                    ExpressionStatement.Throw(19, ValueId(1)),
                ),
            ),
            legacySubroutineNormalized = true,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Synchronized>().single()
        assertEquals(enter, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1, b2, b3), region.bodyBlocks)
        assertEquals(setOf(h1, h2), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(3, region.monitorEnterInstructionIndex)
        assertEquals(listOf(7), region.normalMonitorExitInstructionIndices)
        assertFalse(outerHandler in region.bodyBlocks)
    }

    // Pseudocode: synchronized (m) { try { A } catch (E e) { H } B }  // one catch-all plus self-protected cleanup
    @Test
    fun `recognizes canonical synchronized body with rejoining nested catch and cleanup companion`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val nestedHandler = BasicBlockId(4)
        val cleanup = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, cleanup, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, nestedHandler, "java/lang/Exception"),
            exceptionEdge(b2, cleanup, null),
            exceptionEdge(b3, cleanup, null),
            edge(nestedHandler, b3, ControlFlowEdgeKind.JUMP),
            exceptionEdge(cleanup, cleanup, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 5),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 3),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 3),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(b0, 0, 3, emptyList(), emptyList()),
            BasicBlock(b1, 3, 4, emptyList(), emptyList()),
            BasicBlock(b2, 4, 5, emptyList(), emptyList()),
            BasicBlock(b3, 5, 8, emptyList(), emptyList()),
            BasicBlock(nestedHandler, 8, 10, emptyList(), emptyList()),
            BasicBlock(cleanup, 10, 15, emptyList(), emptyList()),
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
                    exceptionHandler(3, 8, 10, null),
                    exceptionHandler(4, 5, 8, "java/lang/Exception"),
                    exceptionHandler(10, 13, 10, null),
                ),
                emptyList(),
            ),
            blocks,
            edges,
            b0,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, nestedHandler, cleanup), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(7, null),
                    ExpressionStatement.Throw(14, ValueId(0)),
                ),
            ),
            legacySubroutineNormalized = false,
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Synchronized>().single()
        assertEquals(b0, region.header)
        assertEquals(b1, region.bodyEntry)
        assertEquals(setOf(b1, b2, b3, nestedHandler), region.bodyBlocks)
        assertEquals(setOf(cleanup), region.handlerBlocks)
        assertEquals(listOf(6), region.normalMonitorExitInstructionIndices)
        assertEquals(listOf(StructuredProtectedRange(10, 13)), region.syntheticCleanupProtectedRanges)
        assertEquals(3, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: synchronized (m) { BODY }  // handler keeps Throwable on stack: aload m; monitorexit; athrow
    @Test
    fun `recognizes synchronized cleanup with exception kept on operand stack`() {
        val enterBlock = BasicBlockId(0)
        val bodyBlock = BasicBlockId(1)
        val returnBlock = BasicBlockId(2)
        val handlerBlock = BasicBlockId(3)
        val edges = listOf(
            edge(enterBlock, bodyBlock, ControlFlowEdgeKind.FALLTHROUGH),
            edge(bodyBlock, returnBlock, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(bodyBlock, handlerBlock, null),
        )
        val instructions = listOf(
            RawLocalInstruction(JvmOpcode("astore"), LocalOperation.STORE, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorenter")),
            RawNopInstruction(JvmOpcode("nop")),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID),
            RawLocalInstruction(JvmOpcode("aload"), LocalOperation.LOAD, JvmComputationalType.REFERENCE, 2),
            RawMonitorInstruction(JvmOpcode("monitorexit")),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val blocks = listOf(
            BasicBlock(enterBlock, 0, 2, emptyList(), emptyList()),
            BasicBlock(bodyBlock, 2, 5, emptyList(), emptyList()),
            BasicBlock(returnBlock, 5, 6, emptyList(), emptyList()),
            BasicBlock(handlerBlock, 6, 9, emptyList(), emptyList()),
        )
        val labels = List(10) { index -> RawLabel(RawLabelId(index), index, index.coerceAtMost(instructions.size)) }
        val graph = ControlFlowGraph(
            RawCode(
                null, null, null, instructions, labels,
                listOf(exceptionHandler(2, 5, 6, null)),
                emptyList(),
            ),
            blocks,
            edges,
            enterBlock,
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(blocks.mapTo(linkedSetOf()) { it.id }, edges, enterBlock),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(8, ValueId(0)))),
        )

        val region = assertIs<StructuredRegion.Synchronized>(result.regions.single())
        assertEquals(setOf(bodyBlock), region.bodyBlocks)
        assertEquals(handlerBlock, region.handlerEntry)
        assertEquals(setOf(handlerBlock), region.handlerBlocks)
        assertEquals(2, region.monitorSlot)
        assertEquals(1, region.monitorEnterInstructionIndex)
        assertEquals(listOf(4), region.normalMonitorExitInstructionIndices)
        assertEquals(7, region.handlerMonitorExitInstructionIndex)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }
}
