package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.raw.ClassImporter
import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawLocalInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SsaAnalyzerTest {
    private val importer = ClassImporter()
    private val graphBuilder = ControlFlowGraphBuilder()
    private val frameAnalyzer = FrameAnalyzer()
    private val valueFlowAnalyzer = ValueFlowAnalyzer()
    private val analyzer = SsaAnalyzer()

    @Test
    fun `turns local merge into phi and removes local load-store operations`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "chooseThenUse" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)
        val valueFlow = valueFlowAnalyzer.analyze(graph, frames)

        val analysis = analyzer.analyze(graph, valueFlow)

        assertTrue(analysis.localPhiCount > 0)
        assertTrue(analysis.phiNodes.any { it.location is SsaPhiLocation.Local && it.inputs.size > 1 })
        assertTrue(analysis.phiBlockCount > 0)
        assertEquals(0, analysis.trivialPhiCount)
        assertTrue(analysis.phiNodes.all { graph.block(it.blockId).predecessors.size > 1 })
        assertTrue(analysis.operations.none { it.instruction is RawLocalInstruction })
        assertTrue(analysis.eliminatedLocalInstructionCount > 0)
    }

    @Test
    fun `builds def-use edges for operation and phi inputs`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "chooseThenUse" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)
        val valueFlow = valueFlowAnalyzer.analyze(graph, frames)

        val analysis = analyzer.analyze(graph, valueFlow)

        val expectedUses = analysis.operations.sumOf { it.inputs.size } + analysis.phiNodes.sumOf { it.inputs.size }
        assertEquals(expectedUses, analysis.useEdgeCount)
        assertTrue(analysis.uses.keys.all(analysis.values::containsKey))
    }

    @Test
    fun `places every merge at one concrete block entry location`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "chooseThenUse" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)
        val valueFlow = valueFlowAnalyzer.analyze(graph, frames)

        val analysis = analyzer.analyze(graph, valueFlow)
        val sites = analysis.phiNodes.map { it.blockId to it.location }

        assertEquals(sites.size, sites.distinct().size)
        assertTrue(analysis.phiNodes.size <= valueFlow.mergeValueCount)
    }

    @Test
    fun `reuses phi through single predecessor passthrough block`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val graph = ControlFlowGraph(
            code = RawCode(0, 1, 0, emptyList(), emptyList(), emptyList(), emptyList()),
            blocks = listOf(
                BasicBlock(b0, 0, 0, emptyList(), listOf(b1, b2)),
                BasicBlock(b1, 0, 0, listOf(b0), listOf(b3)),
                BasicBlock(b2, 0, 0, listOf(b0), listOf(b3)),
                BasicBlock(b3, 0, 0, listOf(b1, b2), listOf(BasicBlockId(4))),
                BasicBlock(BasicBlockId(4), 0, 0, listOf(b3), emptyList()),
            ),
            edges = listOf(
                ControlFlowEdge(b0, b1, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(b1, b3, ControlFlowEdgeKind.JUMP),
                ControlFlowEdge(b2, b3, ControlFlowEdgeKind.JUMP),
                ControlFlowEdge(b3, BasicBlockId(4), ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = b0,
        )
        val left = ValueId(0)
        val right = ValueId(1)
        val join = ValueId(2)
        val propagated = ValueId(3)
        val valueFlow = ValueFlowAnalysis(
            values = linkedMapOf(
                left to ValueDefinition.Root(left, FrameValueKind.INT, ValueOrigin.Parameter(0)),
                right to ValueDefinition.Root(right, FrameValueKind.INT, ValueOrigin.Parameter(1)),
                join to ValueDefinition.Merge(join, FrameValueKind.INT, ValueMergeSite.Local(b3, 0), listOf(left, right)),
                propagated to ValueDefinition.Merge(
                    propagated,
                    FrameValueKind.INT,
                    ValueMergeSite.Local(BasicBlockId(4), 0),
                    listOf(left, right),
                ),
            ),
            operations = emptyList(),
            blockEntryLocals = emptyMap(),
            blockEntryStacks = emptyMap(),
            blockExitLocals = mapOf(b1 to listOf(left), b2 to listOf(right), b3 to listOf(join)),
            blockExitStacks = emptyMap(),
            mergeValueCount = 2,
            eliminatedStackInstructionCount = 0,
            unanalyzedBlockCount = 0,
        )

        val analysis = analyzer.analyze(graph, valueFlow)

        assertEquals(1, analysis.phiNodes.size)
        assertEquals(listOf(left, right), analysis.phiNodes.single().inputs)
    }

    private fun importFixture(): io.github.relvl.deobscura.raw.RawClass {
        val type = SsaFixture::class.java
        val internalName = type.name.replace('.', '/')
        val bytes = requireNotNull(type.getResourceAsStream("/$internalName.class")).use { it.readAllBytes() }
        return importer.importClass(bytes)
    }
}

private class SsaFixture {
    fun chooseThenUse(flag: Boolean): Int {
        val value: Int
        if (flag) {
            value = 10
        } else {
            value = 20
        }
        return value + 1
    }
}
