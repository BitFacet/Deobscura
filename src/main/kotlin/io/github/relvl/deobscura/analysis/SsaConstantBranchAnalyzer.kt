package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawSwitchInstruction

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
        val controlFlow = SsaControlFlowGraph.from(graph).retainReachable(
            graph.edges.filter { it !in previouslyEliminatedEdges },
        )
        return analyze(graph, controlFlow, analysis, previouslyEliminatedEdges)
    }

    fun analyze(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
        previouslyEliminatedEdges: Set<ControlFlowEdge> = emptySet(),
    ): SsaConstantBranchResult {
        val operationsByInstruction = analysis.operations.associateBy { it.instructionIndex }
        val newlyEliminated = linkedSetOf<ControlFlowEdge>()
        var resolvedConditionalBranchCount = 0
        var resolvedSwitchCount = 0

        graph.blocks.forEach { block ->
            if (block.id !in controlFlow.blocks) return@forEach
            if (block.endInstructionIndexExclusive <= block.startInstructionIndex) return@forEach
            val instructionIndex = block.endInstructionIndexExclusive - 1
            val operation = operationsByInstruction[instructionIndex] ?: return@forEach

            when (val instruction = operation.instruction) {
                is RawBranchInstruction -> {
                    val taken = evaluateConditional(instruction.opcode.mnemonic, operation.inputs, analysis.constants)
                        ?: return@forEach
                    val outgoing = controlFlow.edges.filter {
                        it.from == block.id && it.kind != ControlFlowEdgeKind.EXCEPTION
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
                    val outgoing = controlFlow.edges.filter {
                        it.from == block.id && it.kind == ControlFlowEdgeKind.SWITCH
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
        val previouslyReachable = controlFlow.reachableBlocks()
        val remainingEdges = controlFlow.edges.filter { it !in newlyEliminated }
        val effectiveReachable = controlFlow.reachableBlocks(remainingEdges)
        val newlyUnreachable = previouslyReachable - effectiveReachable
        val optimizedControlFlow = controlFlow.retainReachable(remainingEdges)

        return SsaConstantBranchResult(
            controlFlow = optimizedControlFlow,
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
}

data class SsaConstantBranchResult(
    val controlFlow: SsaControlFlowGraph,
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
