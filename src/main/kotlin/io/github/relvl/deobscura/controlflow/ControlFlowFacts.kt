package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionStatement

/** Immutable method-wide CFG facts shared by structured-control-flow recognizers. */
internal data class ControlFlowFacts(
    val blocks: Set<BasicBlockId>,
    val normalEdges: List<ControlFlowEdge>,
    val outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    val incoming: Map<BasicBlockId, List<ControlFlowEdge>>,
    val predecessors: Map<BasicBlockId, List<BasicBlockId>>,
    val instructionToBlock: Array<BasicBlockId?>,
    val originalBranches: Map<BasicBlockId, ExpressionStatement.Branch>,
    val switches: Map<BasicBlockId, ExpressionStatement.Switch>,
    val explicitTerminalBlocks: Set<BasicBlockId>,
    val dominators: Map<BasicBlockId, Set<BasicBlockId>>,
    val postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
) {
    companion object {
        fun build(
            graph: ControlFlowGraph,
            flow: SsaControlFlowGraph,
            expression: ExpressionAnalysis,
        ): ControlFlowFacts? {
            val blocks = flow.reachableBlocks()
            if (blocks.isEmpty()) return null

            val normalEdges = flow.edges.filter {
                it.kind != ControlFlowEdgeKind.EXCEPTION && it.from in blocks && it.to in blocks
            }
            val outgoing = normalEdges.groupBy { it.from }
            val incoming = normalEdges.groupBy { it.to }
            val predecessors = incoming.mapValues { (_, edges) -> edges.map { it.from }.distinct() }
            val instructionToBlock = instructionToBlock(graph)
            val originalBranches = branchesByBlock(expression, blocks, instructionToBlock)
            val switches = switchesByBlock(expression, blocks, instructionToBlock)
            val explicitTerminalBlocks = explicitTerminalBlocks(expression, blocks, instructionToBlock, outgoing)
            val entry = requireNotNull(flow.entryBlock)
            return ControlFlowFacts(
                blocks = blocks,
                normalEdges = normalEdges,
                outgoing = outgoing,
                incoming = incoming,
                predecessors = predecessors,
                instructionToBlock = instructionToBlock,
                originalBranches = originalBranches,
                switches = switches,
                explicitTerminalBlocks = explicitTerminalBlocks,
                dominators = dominators(blocks, entry, predecessors),
                postDominators = postDominators(blocks, outgoing),
            )
        }
    }
}

private fun instructionToBlock(graph: ControlFlowGraph): Array<BasicBlockId?> =
    arrayOfNulls<BasicBlockId>(graph.code.instructions.size).also { result ->
        graph.blocks.forEach { block ->
            for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                result[index] = block.id
            }
        }
    }

private fun branchesByBlock(
    expression: ExpressionAnalysis,
    blocks: Set<BasicBlockId>,
    instructionToBlock: Array<BasicBlockId?>,
): Map<BasicBlockId, ExpressionStatement.Branch> = expression.statements.asSequence()
    .filterIsInstance<ExpressionStatement.Branch>()
    .filter { it.condition != null }
    .mapNotNull { statement ->
        val block = instructionToBlock.getOrNull(statement.instructionIndex) ?: return@mapNotNull null
        block.takeIf { it in blocks }?.let { it to statement }
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (block, statements) ->
        if (statements.size != 1) {
            throw StructuredControlFlowInconsistencyException(
                "Basic block B${block.value} contains ${statements.size} conditional branch statements.",
            )
        }
        statements.single()
    }

private fun switchesByBlock(
    expression: ExpressionAnalysis,
    blocks: Set<BasicBlockId>,
    instructionToBlock: Array<BasicBlockId?>,
): Map<BasicBlockId, ExpressionStatement.Switch> = expression.statements.asSequence()
    .filterIsInstance<ExpressionStatement.Switch>()
    .mapNotNull { statement ->
        val block = instructionToBlock.getOrNull(statement.instructionIndex) ?: return@mapNotNull null
        block.takeIf { it in blocks }?.let { it to statement }
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (block, statements) ->
        if (statements.size != 1) {
            throw StructuredControlFlowInconsistencyException(
                "Basic block B${block.value} contains ${statements.size} switch statements.",
            )
        }
        statements.single()
    }

private fun explicitTerminalBlocks(
    expression: ExpressionAnalysis,
    blocks: Set<BasicBlockId>,
    instructionToBlock: Array<BasicBlockId?>,
    outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
): Set<BasicBlockId> = expression.statements.asSequence()
    .filter { it is ExpressionStatement.Return || it is ExpressionStatement.Throw }
    .mapNotNull { statement -> instructionToBlock.getOrNull(statement.instructionIndex) }
    .filter { it in blocks && outgoing[it].orEmpty().isEmpty() }
    .toCollection(linkedSetOf())

internal fun dominators(
    blocks: Set<BasicBlockId>,
    entry: BasicBlockId,
    predecessors: Map<BasicBlockId, List<BasicBlockId>>,
): Map<BasicBlockId, Set<BasicBlockId>> {
    val all = blocks.toSet()
    val result = blocks.associateWithTo(linkedMapOf()) { block -> if (block == entry) setOf(entry) else all }
    var changed: Boolean
    do {
        changed = false
        for (block in blocks) {
            if (block == entry) continue
            val preds = predecessors[block].orEmpty().filter { it in blocks }
            val intersection = if (preds.isEmpty()) emptySet() else preds.map { result.getValue(it) }.reduce(Set<BasicBlockId>::intersect)
            val next = linkedSetOf(block).apply { addAll(intersection) }
            if (next != result[block]) {
                result[block] = next
                changed = true
            }
        }
    } while (changed)
    return result
}

internal fun postDominators(
    blocks: Set<BasicBlockId>,
    outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
): Map<BasicBlockId, Set<BasicBlockId>> {
    val all = blocks.toSet()
    val successors = blocks.associateWith { block -> outgoing[block].orEmpty().distinctTargets().map { it.to } }
    val exits = blocks.filterTo(hashSetOf()) { successors[it].orEmpty().isEmpty() }
    if (exits.isEmpty()) return blocks.associateWith { setOf(it) }
    val result = blocks.associateWithTo(linkedMapOf()) { block -> if (block in exits) setOf(block) else all }
    var changed: Boolean
    do {
        changed = false
        for (block in blocks) {
            if (block in exits) continue
            val succs = successors[block].orEmpty()
            if (succs.isEmpty()) continue
            val intersection = succs.map { result.getValue(it) }.reduce(Set<BasicBlockId>::intersect)
            val next = linkedSetOf(block).apply { addAll(intersection) }
            if (next != result[block]) {
                result[block] = next
                changed = true
            }
        }
    } while (changed)
    return result
}

internal fun immediatePostDominator(
    block: BasicBlockId,
    postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
): BasicBlockId? {
    val strict = postDominators[block].orEmpty() - block
    return strict.firstOrNull { candidate ->
        strict.none { other -> other != candidate && candidate in postDominators[other].orEmpty() }
    }
}

internal fun List<ControlFlowEdge>.distinctTargets(): List<ControlFlowEdge> = distinctBy { it.to }
