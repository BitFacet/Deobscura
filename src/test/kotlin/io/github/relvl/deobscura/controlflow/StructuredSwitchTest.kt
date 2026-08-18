package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.ExpressionStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructuredSwitchTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: switch (x) { case A: ...; break; case B: ...; break; } AFTER
    @Test
    fun `recognizes switch cases and common continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 10),
            switchEdge(b0, b2, 20),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(b4, region.continuation)
        assertEquals(3, region.cases.size)
        assertEquals(listOf(10), region.cases.single { it.entry == b1 }.labels)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b3 }.exit?.kind)
        assertTrue(region.cases.single { it.entry == b3 }.isDefault)
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: switch (x) { case A: case B: BODY; break; }
    @Test
    fun `groups multiple switch labels that share one body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 10),
            switchEdge(b0, b1, 11),
            switchEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(listOf(10, 11), region.cases.single { it.entry == b1 }.labels)
        assertEquals(2, region.cases.size)
    }

    // Pseudocode: switch (x) { case A: A; case B: B; break; }
    @Test
    fun `recognizes switch case fallthrough into another case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        val first = region.cases.single { it.entry == b1 }
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, first.exit?.kind)
        assertEquals(b2, first.exit?.target)
        assertEquals(b3, region.continuation)
    }

    // Pseudocode: switch (x) { case A: A; case B: B; } AFTER  // A joins B first
    @Test
    fun `does not mistake a join inside a fallthrough chain for switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            switchEdge(b0, b1, null),
            switchEdge(b0, b2, 0),
            switchEdge(b0, b3, 1),
            switchEdge(b0, b6, 2),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                branch(3),
                ExpressionStatement.Return(1, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b0 })
        assertEquals(null, region.continuation)
        assertEquals(
            listOf(StructuredRegionTransfer(b2, b3, StructuredRegionTransferKind.CASE_FALLTHROUGH)),
            region.cases.single { it.entry == b2 }.transfers,
        )
        assertEquals(setOf(b3, b4, b5), region.cases.single { it.entry == b3 }.blocks)
        assertTrue(
            region.cases.single { it.entry == b3 }.transfers.any {
                it.kind == StructuredRegionTransferKind.CASE_FALLTHROUGH && it.target == b6
            },
        )
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    // Pseudocode: case A: if (cond) break; FALLTHROUGH_BODY
    @Test
    fun `recognizes conditional break and fallthrough from the same switch case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 2),
            switchEdge(b0, b2, 3),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, branch(1)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b0 })
        val case2 = region.cases.single { it.entry == b1 }
        assertEquals(
            setOf(StructuredRegionTransferKind.BREAK_SWITCH, StructuredRegionTransferKind.CASE_FALLTHROUGH),
            case2.transfers.mapTo(linkedSetOf()) { it.kind },
        )
        assertTrue(case2.transfers.any { it.kind == StructuredRegionTransferKind.BREAK_SWITCH && it.target == b4 })
        assertTrue(case2.transfers.any { it.kind == StructuredRegionTransferKind.CASE_FALLTHROUGH && it.target == b2 })
        assertEquals(null, case2.exit)
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    // Pseudocode: outer: for (;;) { switch (x) { case A: break outer; } }
    @Test
    fun `recognizes switch case break from an enclosing natural infinite loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, 2),
            switchEdge(b1, b4, null),
            edge(b3, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b1, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Return(2, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        val breakingCase = region.cases.single { it.entry == b2 }
        assertEquals(
            listOf(StructuredRegionTransferKind.BREAK_LOOP),
            breakingCase.transfers.map { it.kind },
        )
        assertEquals(b2, breakingCase.transfers.single().target)
        assertEquals(b4, region.continuation)
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    // Pseudocode: while (...) { switch (x) { case A: break; /* loop */ } }
    @Test
    fun `preserves switch case that directly breaks an enclosing natural loop`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b4, null),
            switchEdge(b1, b2, 10),
            switchEdge(b1, b3, 20),
            switchEdge(b1, b5, 30),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b4, b1, ControlFlowEdgeKind.JUMP),
            edge(b5, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Return(6, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        assertEquals(b4, region.continuation)
        assertEquals(4, region.cases.size)
        assertEquals(setOf(10, 20, 30), region.cases.flatMap { it.labels }.toSet())
        assertEquals(1, region.cases.count { it.isDefault })

        val breakingCase = region.cases.single { 30 in it.labels }
        assertEquals(b5, breakingCase.entry)
        assertTrue(breakingCase.blocks.isEmpty())
        assertEquals(
            listOf(StructuredRegionTransfer(b1, b5, StructuredRegionTransferKind.BREAK_LOOP)),
            breakingCase.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    // Pseudocode: while (...) { switch (x) { case A: break; /* loop */ } if (...) return; }
    @Test
    fun `recognizes switch case targeting canonical loop exit despite another terminal loop exit`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b4, null),
            switchEdge(b1, b5, -1),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(6), edges),
            SsaControlFlowGraph((0..5).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(1, ExpressionStatement.Throw(4, ValueId(0)), ExpressionStatement.Return(5, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        val breakingCase = region.cases.single { -1 in it.labels }
        assertTrue(breakingCase.blocks.isEmpty())
        assertEquals(
            listOf(StructuredRegionTransfer(b1, b5, StructuredRegionTransferKind.BREAK_LOOP)),
            breakingCase.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    // Pseudocode: while (...) { switch (x) { ... } LOCAL_JOIN; }
    @Test
    fun `prefers local switch join before re-entering enclosing loop`() {
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
            edge(b1, b7, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b2, b3, 1),
            switchEdge(b2, b4, 2),
            switchEdge(b2, b6, null),
            edge(b3, b5, ControlFlowEdgeKind.JUMP),
            edge(b4, b5, ControlFlowEdgeKind.JUMP),
            edge(b5, b1, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(8), edges),
            SsaControlFlowGraph((0..7).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(2, ExpressionStatement.Return(6, null), ExpressionStatement.Return(7, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b2 })
        assertEquals(b5, region.continuation)
        assertTrue(result.unstructured.none { it.header == b2 })
    }

    // Pseudocode: switch (x) { case A: return; case B: B; break; } AFTER
    @Test
    fun `recognizes switch continuation despite a terminal case`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Return(1, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b2 }.exit?.kind)
    }

    // Pseudocode: switch (x) { case A: BODY; break; case B: } AFTER
    @Test
    fun `recognizes continuation that is also an empty switch case target`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b4, 3),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Return(3, null)),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(emptySet(), region.cases.single { it.entry == b4 }.blocks)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b4 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b3 }.exit?.kind)
    }

    // Pseudocode: if (...) goto AFTER; switch (x) { ... break; } AFTER
    @Test
    fun `recognizes switch target shared with surrounding control flow as continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b4, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, 2),
            switchEdge(b1, b4, null),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(6), edges),
            SsaControlFlowGraph((0..5).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                1,
                ExpressionStatement.Return(2, null),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(5, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single { it.header == b1 })
        assertEquals(b4, region.continuation)
        assertEquals(emptySet(), region.cases.single { it.entry == b4 }.blocks)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b4 }.exit?.kind)
    }

    // Pseudocode: switch (x) { case A: A; default: return; }
    @Test
    fun `recognizes terminal switch with fallthrough into terminal default`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Return(1, null),
                ExpressionStatement.Return(3, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b2 }.exit?.kind)
        assertEquals(b3, region.cases.single { it.entry == b2 }.exit?.target)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b3 }.exit?.kind)
    }

    // Pseudocode: case A: if (cond) return; A; break;
    @Test
    fun `keeps case local terminal branch inside case body`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b3, 2),
            switchEdge(b0, b4, null),
            edge(b1, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b3, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Throw(2, ValueId(0)),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(4, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        val firstCase = region.cases.single { it.entry == b1 }
        assertTrue(b2 in firstCase.blocks)
        assertEquals(null, firstCase.exit)
        assertEquals(
            setOf(
                StructuredRegionTransfer(b1, b3, StructuredRegionTransferKind.CASE_FALLTHROUGH),
                StructuredRegionTransfer(b2, kind = StructuredRegionTransferKind.RETURN_OR_THROW),
            ),
            firstCase.transfers.toSet(),
        )
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: switch (x) { case A: ...; case B: ...; } return;
    @Test
    fun `recognizes common terminal post dominator as switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b2, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b3, b1, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(5), edges),
            SsaControlFlowGraph((0..4).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(0, ExpressionStatement.Throw(4, ValueId(0))),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b3 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.NORMAL, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(region.cases.none { b4 in it.blocks })
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: case A: goto RETURN; ...; RETURN: return;
    @Test
    fun `recognizes jump to reachable externally shared terminal block as terminal case exit`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b6, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b6, b5, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 0),
            switchEdge(b0, b2, 1),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b5, b4, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b6),
            switchExpression(
                0,
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(4, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.RETURN_OR_THROW, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(b4, region.cases.single { it.entry == b1 }.exit?.target)
        assertEquals(StructuredSwitchCaseExitKind.FALLTHROUGH, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: case A: if (...) return; break; case B: break; AFTER
    @Test
    fun `recognizes continuation reached by multiple cases despite local terminal branches`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val b8 = BasicBlockId(8)
        val edges = listOf(
            switchEdge(b0, b1, 0),
            switchEdge(b0, b4, 1),
            switchEdge(b0, b8, null),
            edge(b1, b3, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b7, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b6, b7, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(9), edges),
            SsaControlFlowGraph((0..8).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            switchExpression(
                0,
                ExpressionStatement.Throw(2, ValueId(0)),
                ExpressionStatement.Throw(5, ValueId(0)),
                ExpressionStatement.Return(7, null),
                ExpressionStatement.Throw(8, ValueId(0)),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b7, region.continuation)
        assertTrue(b2 in region.cases.single { it.entry == b1 }.blocks)
        assertTrue(b5 in region.cases.single { it.entry == b4 }.blocks)
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: case A: if (...) X; LOCAL_JOIN; break; ... OUTER_JOIN
    @Test
    fun `prefers local case join over later surrounding-flow join`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val b7 = BasicBlockId(7)
        val b8 = BasicBlockId(8)
        val b9 = BasicBlockId(9)
        val edges = listOf(
            edge(b8, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b8, b7, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
            edge(b1, b4, ControlFlowEdgeKind.JUMP),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b6, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b7, b6, ControlFlowEdgeKind.JUMP),
            edge(b6, b9, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(10), edges),
            SsaControlFlowGraph((0..9).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b8),
            switchExpression(
                0,
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Raw(4, "after switch", emptyList()),
                ExpressionStatement.Return(9, null),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Switch>().single { it.header == b0 }
        assertEquals(b4, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b2 }.exit?.kind)
        assertTrue(result.unstructured.none { it.header == b0 })
    }

    // Pseudocode: switch (...) { ... break; } SHARED; NEXT
    @Test
    fun `recognizes non-terminal non-entry continuation shared with surrounding flow`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b5, b0, ControlFlowEdgeKind.CONDITIONAL),
            edge(b5, b4, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, null),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
            edge(b4, b3, ControlFlowEdgeKind.JUMP),
            edge(b3, b6, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b5),
            switchExpression(
                0,
                ExpressionStatement.Return(2, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(b3, region.continuation)
        assertEquals(StructuredSwitchCaseExitKind.BREAK, region.cases.single { it.entry == b1 }.exit?.kind)
        assertTrue(result.unstructured.isEmpty())
    }

    // Pseudocode: while (...) { switch (x) { case A: continue; } UPSTREAM_JOIN; }
    @Test
    fun `recognizes proven natural loop continue without using upstream join as switch continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val b6 = BasicBlockId(6)
        val edges = listOf(
            edge(b5, b0, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b0, b6, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            switchEdge(b1, b2, 1),
            switchEdge(b1, b3, null),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b4, b0, ControlFlowEdgeKind.JUMP),
        )
        val result = analyzer.analyze(
            graph(blocks(7), edges),
            SsaControlFlowGraph((0..6).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b5),
            switchExpression(
                1,
                ExpressionStatement.Raw(2, "work", emptyList()),
                ExpressionStatement.Return(3, null),
                ExpressionStatement.Return(6, null),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.Switch>().single { it.header == b1 }
        assertEquals(null, region.continuation)
        assertEquals(
            listOf(StructuredRegionTransfer(b2, b4, StructuredRegionTransferKind.CONTINUE_LOOP)),
            region.cases.single { it.entry == b2 }.transfers,
        )
        assertEquals(
            listOf(StructuredRegionTransfer(b3, kind = StructuredRegionTransferKind.RETURN_OR_THROW)),
            region.cases.single { it.entry == b3 }.transfers,
        )
        assertTrue(result.unstructured.none { it.header == b1 })
    }

    // Pseudocode: switch (x) { case A: return; default: throw; }
    @Test
    fun `recognizes fully terminal switch without common continuation`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            switchEdge(b0, b1, 1),
            switchEdge(b0, b2, 2),
            switchEdge(b0, b3, null),
        )
        val expression = switchExpression(
            0,
            ExpressionStatement.Return(1, null),
            ExpressionStatement.Return(2, null),
            ExpressionStatement.Throw(3, ValueId(0)),
        )
        val result = analyzer.analyze(
            graph(blocks(4), edges),
            SsaControlFlowGraph((0..3).mapTo(linkedSetOf()) { BasicBlockId(it) }, edges, b0),
            expression,
        )

        val region = assertIs<StructuredRegion.Switch>(result.regions.single())
        assertEquals(null, region.continuation)
        assertTrue(region.cases.all { it.exit?.kind == StructuredSwitchCaseExitKind.RETURN_OR_THROW })
    }
}
