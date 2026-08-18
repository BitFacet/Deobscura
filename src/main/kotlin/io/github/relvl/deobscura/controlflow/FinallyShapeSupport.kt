package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.LocalOperation
import io.github.relvl.deobscura.raw.RawLocalInstruction
import io.github.relvl.deobscura.raw.RawThrowInstruction
import java.util.*

/** Collects a normal-flow region only when it is acyclic from the chosen entry. */
internal fun collectAcyclicNormalRegion(entry: BasicBlockId, facts: ControlFlowFacts): Set<BasicBlockId>? {
    val result = linkedSetOf<BasicBlockId>()
    val visiting = mutableSetOf<BasicBlockId>()

    fun visit(block: BasicBlockId): Boolean {
        if (block in result) return true
        if (!visiting.add(block)) return false
        for (edge in facts.outgoing[block].orEmpty()) {
            if (!visit(edge.to)) return false
        }
        visiting.remove(block)
        result += block
        return true
    }

    return result.takeIf { visit(entry) }
}

/** Collects normal-flow blocks up to, but not including, a known stop block. */
internal fun collectUntil(entry: BasicBlockId, stop: BasicBlockId, facts: ControlFlowFacts): Set<BasicBlockId> {
    val result = linkedSetOf<BasicBlockId>()
    val queue = ArrayDeque<BasicBlockId>()
    queue += entry
    while (queue.isNotEmpty()) {
        val block = queue.removeFirst()
        if (block == stop || !result.add(block)) continue
        for (edge in facts.outgoing[block].orEmpty()) {
            if (edge.to != stop) queue += edge.to
        }
    }
    return result
}

/** Coalesces physical CFG blocks into contiguous instruction ranges. */
internal fun instructionRanges(blocks: Set<BasicBlockId>, graph: ControlFlowGraph): List<IntRange> {
    val physical = blocks.map(graph::block).sortedBy { it.startInstructionIndex }
    if (physical.isEmpty()) return emptyList()

    val ranges = mutableListOf<IntRange>()
    var rangeStart = physical.first().startInstructionIndex
    var rangeEndExclusive = physical.first().endInstructionIndexExclusive
    for (block in physical.drop(1)) {
        if (block.startInstructionIndex == rangeEndExclusive) {
            rangeEndExclusive = block.endInstructionIndexExclusive
        } else {
            ranges += rangeStart until rangeEndExclusive
            rangeStart = block.startInstructionIndex
            rangeEndExclusive = block.endInstructionIndexExclusive
        }
    }
    ranges += rangeStart until rangeEndExclusive
    return ranges
}

/** Returns the cleanup-prefix size before the canonical exception reload + `athrow` tail. */
internal fun canonicalRethrowPrefixSize(
    graph: ControlFlowGraph,
    block: BasicBlockId,
    exceptionSlot: Int,
): Int? {
    val instructions = graph.instructions(graph.block(block))
    if (instructions.size < 2) return null
    val reload = instructions[instructions.lastIndex - 1] as? RawLocalInstruction ?: return null
    if (reload.operation != LocalOperation.LOAD ||
        reload.type != JvmComputationalType.REFERENCE ||
        reload.slot != exceptionSlot ||
        instructions.last() !is RawThrowInstruction
    ) {
        return null
    }
    return instructions.size - 2
}

/** Matches a standalone exception reload + `athrow` block. */
internal fun isCanonicalRethrowBlock(graph: ControlFlowGraph, block: BasicBlockId, exceptionSlot: Int): Boolean =
    canonicalRethrowPrefixSize(graph, block, exceptionSlot) == 0

/**
 * Collects a typed catch body that is followed by a proven normal finally copy. The copy is a stop
 * boundary rather than source catch ownership; walking back into the try or a sibling handler
 * rejects the candidate.
 */
internal fun collectCatchBodyBeforeFinally(
    typedCatchRecognizer: TypedCatchRecognizer,
    entry: BasicBlockId,
    protectedBlocks: Set<BasicBlockId>,
    otherHandlerEntries: Set<BasicBlockId>,
    finallyCopyBlocks: Set<BasicBlockId>,
    continuation: BasicBlockId?,
    facts: ControlFlowFacts,
): Set<BasicBlockId>? {
    val collection = typedCatchRecognizer.collectCatchBodyRegion(
        entry = entry,
        protectedBlocks = protectedBlocks,
        handlerEntries = otherHandlerEntries,
        continuation = continuation,
        stopBlocks = finallyCopyBlocks,
        rejectTargets = protectedBlocks + otherHandlerEntries,
        facts = facts,
    ) ?: return null
    return collection.blocks.takeUnless { it.isEmpty() }
}
