package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import java.util.ArrayDeque

/**
 * Mutable-by-replacement control-flow view used by SSA optimization.
 *
 * The raw CFG remains unchanged and retains bytecode provenance. This view contains only the blocks
 * and edges that are still semantically relevant after branch pruning and CFG canonicalization, and
 * may contain redirected edges that never existed physically in the bytecode.
 */
data class SsaControlFlowGraph(
    val blocks: Set<BasicBlockId>,
    val edges: List<ControlFlowEdge>,
    val entryBlock: BasicBlockId?,
) {
    fun reachableBlocks(): Set<BasicBlockId> = reachableBlocks(edges)

    fun reachableBlocks(candidateEdges: List<ControlFlowEdge>): Set<BasicBlockId> {
        val entry = entryBlock ?: return emptySet()
        if (entry !in blocks) return emptySet()
        val outgoing = candidateEdges.asSequence()
            .filter { it.from in blocks && it.to in blocks }
            .groupBy { it.from }
        val reachable = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.addLast(entry)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (!reachable.add(block)) continue
            outgoing[block].orEmpty().forEach { queue.addLast(it.to) }
        }
        return reachable
    }

    fun retainReachable(candidateEdges: List<ControlFlowEdge> = edges): SsaControlFlowGraph {
        val reachable = reachableBlocks(candidateEdges)
        return copy(
            blocks = blocks.filterTo(linkedSetOf()) { it in reachable },
            edges = candidateEdges.filter { it.from in reachable && it.to in reachable },
            entryBlock = entryBlock?.takeIf { it in reachable },
        )
    }

    companion object {
        fun from(graph: ControlFlowGraph): SsaControlFlowGraph = SsaControlFlowGraph(
            blocks = graph.blocks.mapTo(linkedSetOf()) { it.id },
            edges = graph.edges,
            entryBlock = graph.entryBlock,
        )
    }
}
