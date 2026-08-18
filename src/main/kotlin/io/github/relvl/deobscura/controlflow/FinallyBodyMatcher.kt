package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*

/** A proven normal-flow copy of the exceptional finally body. */
internal data class FinallyBodyMatch(
    val blocks: Set<BasicBlockId>,
    val continuation: BasicBlockId?,
    val instructionRanges: List<IntRange>,
    val matchedNestedHandlerCount: Int = 0,
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
        handlerExitInstructionPrefixLength: Int = 0,
        allowSplitNormalCopy: Boolean = false,
        allowEquivalentTerminalReturnTargets: Boolean = false,
        allowNestedSingleBlockHandlers: Boolean = false,
    ): FinallyBodyMatch? {
        val mapping = linkedMapOf<BasicBlockId, BasicBlockId>()
        val localSlots = linkedMapOf<Int, Int>()
        val reverseLocalSlots = linkedMapOf<Int, Int>()
        var continuation: BasicBlockId? = null
        var terminalExitBlock: BasicBlockId? = null
        val terminalContinuationTargets = linkedSetOf<BasicBlockId>()
        var matchedNestedHandlerCount = 0

        fun matchNestedHandler(leftTarget: BasicBlockId, rightTarget: BasicBlockId): Boolean {
            if (leftTarget in handlerBlocks || rightTarget in mapping.values) return false
            val leftInstructions = graph.instructions(graph.block(leftTarget))
            val rightInstructions = graph.instructions(graph.block(rightTarget))
            if (leftInstructions.size < 2 || leftInstructions.size != rightInstructions.size) return false
            val leftStore = leftInstructions.first() as? RawLocalInstruction ?: return false
            val rightStore = rightInstructions.first() as? RawLocalInstruction ?: return false
            if (leftStore.operation != LocalOperation.STORE ||
                leftStore.type != JvmComputationalType.REFERENCE ||
                rightStore.operation != LocalOperation.STORE ||
                rightStore.type != JvmComputationalType.REFERENCE
            ) return false
            if (leftInstructions.indices.any {
                    !equivalentFinallyInstruction(leftInstructions[it], rightInstructions[it], localSlots, reverseLocalSlots)
                }) return false

            val leftNormalEdges = facts.outgoing[leftTarget].orEmpty()
            val rightNormalEdges = facts.outgoing[rightTarget].orEmpty()
            if (leftNormalEdges.size != 1 || leftNormalEdges.single().to != handlerExit) return false
            if (rightNormalEdges.size != 1) return false
            val rightContinuation = rightNormalEdges.single().to
            if (terminalContinuationTargets.isNotEmpty()) return false
            if (continuation != null && continuation != rightContinuation) return false
            continuation = rightContinuation
            matchedNestedHandlerCount++
            return true
        }

        fun match(handlerBlock: BasicBlockId, normalBlock: BasicBlockId): Boolean {
            mapping[handlerBlock]?.let { return it == normalBlock }
            if (normalBlock in mapping.values) return false
            mapping[handlerBlock] = normalBlock

            val left = graph.instructions(graph.block(handlerBlock)).let { instructions ->
                val start = if (handlerBlock == handlerEntry) handlerEntryInstructionOffset else 0
                val endExclusive = if (handlerBlock == handlerExit && handlerExitInstructionPrefixLength != 0) {
                    handlerExitInstructionPrefixLength
                } else {
                    instructions.size
                }
                if (start > endExclusive) return false
                instructions.subList(start, endExclusive)
            }
            val right = graph.instructions(graph.block(normalBlock))
            val trailingGoto = right.size == left.size + 1 &&
                right.last() is RawBranchInstruction &&
                (right.last() as RawBranchInstruction).opcode.mnemonic == "goto"
            val trailingReturn = right.size == left.size + 1 && right.last() is RawReturnInstruction
            val rightBody = if (trailingGoto || trailingReturn) right.dropLast(1) else right
            if (left.size != rightBody.size || left.indices.any { !equivalentFinallyInstruction(left[it], rightBody[it], localSlots, reverseLocalSlots) }) {
                return false
            }

            val leftEdges = facts.outgoing[handlerBlock].orEmpty()
            val rightEdges = facts.outgoing[normalBlock].orEmpty()
            if (handlerBlock == handlerExit && handlerExitInstructionPrefixLength != 0) {
                if (trailingReturn && rightEdges.isEmpty()) {
                    if (terminalExitBlock != null && terminalExitBlock != normalBlock) return false
                    terminalExitBlock = normalBlock
                    return true
                }
                if (rightEdges.size != 1) return false
                val target = rightEdges.single().to
                if (continuation != null && continuation != target) return false
                continuation = target
                return true
            }
            val terminalExit = trailingReturn &&
                rightEdges.isEmpty() &&
                leftEdges.size == 1 &&
                leftEdges.single().to == handlerExit
            if (terminalExit) {
                if (terminalExitBlock != null && terminalExitBlock != normalBlock) return false
                terminalExitBlock = normalBlock
                return true
            }
            if (leftEdges.size != rightEdges.size) return false

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
                    if (handlerExitInstructionPrefixLength != 0) {
                        if (!match(handlerExit, rightTarget)) return false
                    } else if (allowEquivalentTerminalReturnTargets && isStandaloneReturnBlock(graph, rightTarget, facts)) {
                        if (continuation != null) return false
                        terminalContinuationTargets += rightTarget
                    } else {
                        if (terminalContinuationTargets.isNotEmpty()) return false
                        if (continuation != null && continuation != rightTarget) return false
                        continuation = rightTarget
                    }
                } else {
                    if (leftEdge.to !in handlerBlocks || !match(leftEdge.to, rightTarget))
                        return false
                }
            }
            if (allowNestedSingleBlockHandlers) {
                val leftNestedEdges = graph.edges.filter { edge ->
                    edge.from == handlerBlock &&
                        edge.kind == ControlFlowEdgeKind.EXCEPTION &&
                        edge.catchType != null &&
                        edge.to !in handlerBlocks &&
                        facts.outgoing[edge.to].orEmpty().singleOrNull()?.to == handlerExit
                }
                for (leftNestedEdge in leftNestedEdges) {
                    val candidates = graph.edges.filter { rightNestedEdge ->
                        rightNestedEdge.from == normalBlock &&
                            rightNestedEdge.kind == ControlFlowEdgeKind.EXCEPTION &&
                            rightNestedEdge.catchType == leftNestedEdge.catchType &&
                            facts.outgoing[rightNestedEdge.to].orEmpty().size == 1
                    }
                    if (candidates.size != 1 || !matchNestedHandler(leftNestedEdge.to, candidates.single().to)) return false
                }
            }
            return true
        }

        if (!match(handlerEntry, normalEntry)) return null
        if (continuation == null && terminalContinuationTargets.size == 1) {
            continuation = terminalContinuationTargets.single()
            terminalContinuationTargets.clear()
        }
        if (continuation == null && terminalExitBlock == null && terminalContinuationTargets.isEmpty()) return null
        if (continuation != null && (terminalExitBlock != null || terminalContinuationTargets.isNotEmpty())) return null
        if (terminalExitBlock != null && terminalContinuationTargets.isNotEmpty()) return null
        val normalBlocks = mapping.values.toSet()
        if (continuation != null && continuation in normalBlocks) return null
        if (normalBlocks.any { block -> facts.incoming[block].orEmpty().any { it.from !in normalBlocks && block != normalEntry } }) return null

        val ranges = instructionRanges(normalBlocks, graph).let { rawRanges ->
            val terminal = terminalExitBlock ?: return@let rawRanges
            val terminalInstruction = graph.block(terminal).endInstructionIndexExclusive - 1
            rawRanges.mapNotNull { range ->
                when {
                    terminalInstruction !in range -> range
                    range.first == range.last -> null
                    else -> range.first..(range.last - 1)
                }
            }
        }
        if (!allowSplitNormalCopy && ranges.size != 1) return null
        return FinallyBodyMatch(normalBlocks, continuation, ranges, matchedNestedHandlerCount)
    }

    private fun isStandaloneReturnBlock(
        graph: ControlFlowGraph,
        block: BasicBlockId,
        facts: ControlFlowFacts,
    ): Boolean {
        val instructions = graph.instructions(graph.block(block))
        return instructions.size == 1 &&
            instructions.single() is RawReturnInstruction &&
            facts.outgoing[block].orEmpty().isEmpty()
    }

    private fun equivalentFinallyInstruction(
        left: RawInstruction,
        right: RawInstruction,
        localSlots: MutableMap<Int, Int>,
        reverseLocalSlots: MutableMap<Int, Int>,
    ): Boolean = when {
        left is RawLocalInstruction && right is RawLocalInstruction ->
            left.operation == right.operation &&
                left.type == right.type &&
                equivalentLocalSlot(left.slot, right.slot, localSlots, reverseLocalSlots)

        left is RawIncrementInstruction && right is RawIncrementInstruction ->
            left.amount == right.amount &&
                equivalentLocalSlot(left.slot, right.slot, localSlots, reverseLocalSlots)

        left is RawBranchInstruction && right is RawBranchInstruction -> left.opcode == right.opcode
        else -> left == right
    }

    /** Compiler-generated cleanup copies may allocate the same temporary to different JVM locals. */
    private fun equivalentLocalSlot(
        left: Int,
        right: Int,
        localSlots: MutableMap<Int, Int>,
        reverseLocalSlots: MutableMap<Int, Int>,
    ): Boolean {
        localSlots[left]?.let { return it == right }
        reverseLocalSlots[right]?.let { return it == left }
        localSlots[left] = right
        reverseLocalSlots[right] = left
        return true
    }
}
