package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawSwitchInstruction
import java.util.*

/**
 * Resolves conditional branches and switches whose operands are known numeric SSA constants.
 *
 * This pass does not mutate RawCode or the original CFG. It produces an analysis-only edge view:
 * impossible normal-control-flow edges are marked eliminated while exception edges are preserved.
 */
class SsaConstantBranchAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        analysis: SsaAnalysis,
        previouslyEliminatedEdges: Set<ControlFlowEdge> = emptySet(),
    ): SsaConstantBranchResult {
        val operationsByInstruction = analysis.operations.associateBy { it.instructionIndex }
        val newlyEliminated = linkedSetOf<ControlFlowEdge>()
        var resolvedConditionalBranchCount = 0
        var resolvedSwitchCount = 0

        graph.blocks.forEach { block ->
            if (block.endInstructionIndexExclusive <= block.startInstructionIndex) return@forEach
            val instructionIndex = block.endInstructionIndexExclusive - 1
            val operation = operationsByInstruction[instructionIndex] ?: return@forEach

            when (val instruction = operation.instruction) {
                is RawBranchInstruction -> {
                    val taken = evaluateConditional(instruction.opcode.mnemonic, operation.inputs, analysis.constants)
                        ?: return@forEach
                    val outgoing = graph.edges.filter {
                        it.from == block.id &&
                                it.kind != ControlFlowEdgeKind.EXCEPTION &&
                                it !in previouslyEliminatedEdges
                    }
                    val retainedKind = if (taken) ControlFlowEdgeKind.CONDITIONAL else ControlFlowEdgeKind.FALLTHROUGH
                    if (outgoing.none { it.kind == retainedKind }) return@forEach
                    val removed = outgoing.filter { it.kind != retainedKind }
                    if (removed.isNotEmpty()) {
                        newlyEliminated += removed
                        resolvedConditionalBranchCount++
                    }
                }

                is RawSwitchInstruction -> {
                    val selector = operation.inputs.singleOrNull()?.let(analysis.constants::get) as? SsaConstant.IntValue
                        ?: return@forEach
                    val outgoing = graph.edges.filter {
                        it.from == block.id &&
                                it.kind == ControlFlowEdgeKind.SWITCH &&
                                it !in previouslyEliminatedEdges
                    }
                    val matchingCase = outgoing.firstOrNull { it.switchValue == selector.value }
                    val retained = matchingCase ?: outgoing.firstOrNull { it.switchValue == null } ?: return@forEach
                    val removed = outgoing.filter { it != retained }
                    if (removed.isNotEmpty()) {
                        newlyEliminated += removed
                        resolvedSwitchCount++
                    }
                }

                else -> Unit
            }
        }

        val eliminated = previouslyEliminatedEdges + newlyEliminated
        val previouslyReachable = reachableBlocks(graph, previouslyEliminatedEdges)
        val effectiveReachable = reachableBlocks(graph, eliminated)
        val newlyUnreachable = previouslyReachable - effectiveReachable

        return SsaConstantBranchResult(
            eliminatedEdges = eliminated,
            newlyEliminatedEdges = newlyEliminated,
            reachableBlocks = effectiveReachable,
            resolvedConditionalBranchCount = resolvedConditionalBranchCount,
            resolvedSwitchCount = resolvedSwitchCount,
            newlyUnreachableBlockCount = newlyUnreachable.size,
        )
    }

    private fun evaluateConditional(
        mnemonic: String,
        inputs: List<ValueId>,
        constants: Map<ValueId, SsaConstant>,
    ): Boolean? {
        fun int(index: Int): Int? = (inputs.getOrNull(index)?.let(constants::get) as? SsaConstant.IntValue)?.value

        return when (mnemonic) {
            "ifeq" -> int(0)?.let { it == 0 }
            "ifne" -> int(0)?.let { it != 0 }
            "iflt" -> int(0)?.let { it < 0 }
            "ifge" -> int(0)?.let { it >= 0 }
            "ifgt" -> int(0)?.let { it > 0 }
            "ifle" -> int(0)?.let { it <= 0 }
            "if_icmpeq" -> compareInts(inputs, constants) { left, right -> left == right }
            "if_icmpne" -> compareInts(inputs, constants) { left, right -> left != right }
            "if_icmplt" -> compareInts(inputs, constants) { left, right -> left < right }
            "if_icmpge" -> compareInts(inputs, constants) { left, right -> left >= right }
            "if_icmpgt" -> compareInts(inputs, constants) { left, right -> left > right }
            "if_icmple" -> compareInts(inputs, constants) { left, right -> left <= right }
            else -> null
        }
    }

    private fun compareInts(
        inputs: List<ValueId>,
        constants: Map<ValueId, SsaConstant>,
        predicate: (Int, Int) -> Boolean,
    ): Boolean? {
        val left = (inputs.getOrNull(0)?.let(constants::get) as? SsaConstant.IntValue)?.value ?: return null
        val right = (inputs.getOrNull(1)?.let(constants::get) as? SsaConstant.IntValue)?.value ?: return null
        return predicate(left, right)
    }

    private fun reachableBlocks(graph: ControlFlowGraph, eliminated: Set<ControlFlowEdge>): Set<BasicBlockId> {
        val entry = graph.entryBlock ?: return emptySet()
        val outgoing = graph.edges.asSequence().filter { it !in eliminated }.groupBy { it.from }
        val reachable = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(entry)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (!reachable.add(block)) continue
            outgoing[block].orEmpty().forEach { queue.addLast(it.to) }
        }
        return reachable
    }
}

data class SsaConstantBranchResult(
    val eliminatedEdges: Set<ControlFlowEdge>,
    val newlyEliminatedEdges: Set<ControlFlowEdge> = eliminatedEdges,
    val reachableBlocks: Set<BasicBlockId>,
    val resolvedConditionalBranchCount: Int,
    val resolvedSwitchCount: Int,
    val newlyUnreachableBlockCount: Int,
) {
    val eliminatedEdgeCount: Int
        get() = eliminatedEdges.size

    val newlyEliminatedEdgeCount: Int
        get() = newlyEliminatedEdges.size
}
