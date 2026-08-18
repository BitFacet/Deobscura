package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawInstruction

/** A proven normal-flow copy of the exceptional finally body. */
internal data class FinallyBodyMatch(
    val blocks: Set<BasicBlockId>,
    val continuation: BasicBlockId,
    val instructionRanges: List<IntRange>,
)

/** Compares exceptional and normal cleanup CFGs without assigning source-level ownership. */
internal object FinallyBodyMatcher {
    /** Proves CFG and instruction equivalence between exceptional cleanup and one normal copy. */
    fun match(
        graph: ControlFlowGraph,
        handlerEntry: BasicBlockId,
        handlerBlocks: Set<BasicBlockId>,
        handlerExit: BasicBlockId,
        handlerEntryInstructionOffset: Int,
        normalEntry: BasicBlockId,
        facts: ControlFlowFacts,
        allowSplitNormalCopy: Boolean = false
    ): FinallyBodyMatch? {
        val mapping = linkedMapOf<BasicBlockId, BasicBlockId>()
        var continuation: BasicBlockId? = null

        fun match(handlerBlock: BasicBlockId, normalBlock: BasicBlockId): Boolean {
            mapping[handlerBlock]?.let { return it == normalBlock }
            if (normalBlock in mapping.values) return false
            mapping[handlerBlock] = normalBlock

            val left = graph.instructions(graph.block(handlerBlock)).let { instructions ->
                if (handlerBlock == handlerEntry && handlerEntryInstructionOffset != 0) {
                    instructions.drop(handlerEntryInstructionOffset)
                } else {
                    instructions
                }
            }
            val right = graph.instructions(graph.block(normalBlock))
            val rightBody = if (right.size == left.size + 1 && right.last() is RawBranchInstruction && (right.last() as RawBranchInstruction).opcode.mnemonic == "goto") {
                right.dropLast(1)
            } else {
                right
            }
            if (left.size != rightBody.size || left.indices.any { !equivalentFinallyInstruction(left[it], rightBody[it]) }) {
                return false
            }

            val leftEdges = facts.outgoing[handlerBlock].orEmpty()
            val rightEdges = facts.outgoing[normalBlock].orEmpty()
            if (leftEdges.size != rightEdges.size)
                return false

            for (leftEdge in leftEdges) {
                val sameKind = rightEdges.filter { it.kind == leftEdge.kind }
                val candidates = if (leftEdge.to == handlerExit && sameKind.isEmpty() && rightEdges.size == 1) {
                    rightEdges
                } else {
                    sameKind
                }
                if (candidates.size != 1)
                    return false
                val rightTarget = candidates.single().to
                if (leftEdge.to == handlerExit) {
                    if (continuation != null && continuation != rightTarget)
                        return false
                    continuation = rightTarget
                } else {
                    if (leftEdge.to !in handlerBlocks || !match(leftEdge.to, rightTarget))
                        return false
                }
            }
            return true
        }

        if (!match(handlerEntry, normalEntry)) return null
        val resolvedContinuation = continuation ?: return null
        val normalBlocks = mapping.values.toSet()
        if (resolvedContinuation in normalBlocks) return null
        if (normalBlocks.any { block -> facts.incoming[block].orEmpty().any { it.from !in normalBlocks && block != normalEntry } }) return null

        val ranges = instructionRanges(normalBlocks, graph)
        if (!allowSplitNormalCopy && ranges.size != 1) return null
        return FinallyBodyMatch(normalBlocks, resolvedContinuation, ranges)
    }

    private fun equivalentFinallyInstruction(left: RawInstruction, right: RawInstruction): Boolean = when {
        left is RawBranchInstruction && right is RawBranchInstruction -> left.opcode == right.opcode
        else -> left == right
    }
}
