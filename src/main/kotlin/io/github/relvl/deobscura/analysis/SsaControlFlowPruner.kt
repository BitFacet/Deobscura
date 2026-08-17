package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import java.util.*

/**
 * Applies an analysis-only constant-branch result to SSA.
 *
 * Normal phi inputs are predecessor-addressed, so pruning removes an input exactly when that
 * predecessor no longer has a surviving normal edge into the phi block. Exception-handler phis
 * remain conservative: they still model frame origins rather than per-instruction exceptional
 * states and therefore are not predecessor-addressed yet.
 */
class SsaControlFlowPruner {
    fun prune(
        graph: ControlFlowGraph,
        analysis: SsaAnalysis,
        branches: SsaConstantBranchResult,
    ): SsaControlFlowPruningResult = prune(
        graph = graph,
        controlFlow = SsaControlFlowGraph.from(graph),
        analysis = analysis,
        branches = branches,
    )

    fun prune(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
        branches: SsaConstantBranchResult,
    ): SsaControlFlowPruningResult {
        val reachableBlocks = branches.reachableBlocks
        val eliminatedEdges = branches.newlyEliminatedEdges
        val blockByInstruction = buildBlockIndex(graph)

        var removedPhiInputCount = 0
        var conservativelyRetainedPhiCount = 0

        val reachablePhiNodes = analysis.phiNodes
            .asSequence()
            .filter { it.blockId in reachableBlocks }
            .map { phi ->
                if (!phi.isPredecessorAddressed) {
                    conservativelyRetainedPhiCount++
                    return@map phi
                }

                val survivingPredecessors = controlFlow.edges.asSequence()
                    .filter { edge ->
                        edge.to == phi.blockId &&
                            edge.kind != ControlFlowEdgeKind.EXCEPTION &&
                            edge !in eliminatedEdges &&
                            edge.from in reachableBlocks
                    }
                    .map { it.from }
                    .toSet()
                val inputs = phi.inputs.filter { it.predecessor in survivingPredecessors }
                removedPhiInputCount += phi.inputs.size - inputs.size
                if (inputs.isEmpty()) {
                    throw SsaInconsistencyException(
                        "Reachable phi ${phi.output.value} in block ${phi.blockId.value} lost all inputs after CFG pruning.",
                    )
                }
                phi.copy(inputs = inputs)
            }
            .toList()

        val operationsByOutput = analysis.operations.mapNotNull { operation -> operation.output?.let { it to operation } }.toMap()
        val phisByOutput = analysis.phiNodes.associateBy { it.output }
        val keptPhiByOutput = reachablePhiNodes.associateBy { it.output }.toMutableMap()

        val reachableOperations = analysis.operations.filter { operation ->
            blockByInstruction[operation.instructionIndex] in reachableBlocks
        }
        val keptOperationsByOutput = reachableOperations
            .mapNotNull { operation -> operation.output?.let { it to operation } }
            .toMap()
            .toMutableMap()

        // Exception-handler phis can still name frame-origin values produced in blocks that became
        // unreachable after normal-edge pruning. Preserve the minimal backwards value slice needed
        // to keep those conservative phis well-formed until exceptional SSA becomes edge-precise.
        val requiredValues = ArrayDeque<ValueId>()
        reachableOperations.forEach { operation -> operation.inputs.forEach(requiredValues::addLast) }
        reachablePhiNodes.forEach { phi -> phi.inputs.forEach { requiredValues.addLast(it.value) } }
        val visitedValues = mutableSetOf<ValueId>()
        var retainedUnreachableOperationCount = 0
        var retainedUnreachablePhiCount = 0

        while (requiredValues.isNotEmpty()) {
            val value = requiredValues.removeFirst()
            if (!visitedValues.add(value)) continue

            val operation = operationsByOutput[value]
            if (operation != null) {
                if (value !in keptOperationsByOutput) {
                    keptOperationsByOutput[value] = operation
                    if (blockByInstruction[operation.instructionIndex] !in reachableBlocks) {
                        retainedUnreachableOperationCount++
                    }
                }
                operation.inputs.forEach(requiredValues::addLast)
                continue
            }

            val phi = keptPhiByOutput[value] ?: phisByOutput[value]
            if (phi != null) {
                if (value !in keptPhiByOutput) {
                    keptPhiByOutput[value] = phi
                    if (phi.blockId !in reachableBlocks) retainedUnreachablePhiCount++
                }
                phi.inputs.forEach { requiredValues.addLast(it.value) }
            }
        }

        val keptOperations = analysis.operations.filter { operation ->
            operation.output?.let { it in keptOperationsByOutput } ?: (blockByInstruction[operation.instructionIndex] in reachableBlocks)
        }
        val keptPhiNodes = analysis.phiNodes.mapNotNull { original -> keptPhiByOutput[original.output] }

        val retainedValueIds = linkedSetOf<ValueId>()
        keptOperations.forEach { operation ->
            operation.output?.let(retainedValueIds::add)
            retainedValueIds.addAll(operation.inputs)
        }
        keptPhiNodes.forEach { phi ->
            retainedValueIds += phi.output
            phi.inputs.forEach { retainedValueIds += it.value }
        }
        analysis.values.values.filterIsInstance<SsaValueDefinition.Root>().forEach { retainedValueIds += it.id }

        val values = linkedMapOf<ValueId, SsaValueDefinition>()
        analysis.values.forEach { (id, definition) ->
            if (id !in retainedValueIds) return@forEach
            values[id] = when (definition) {
                is SsaValueDefinition.Phi -> {
                    val phi = keptPhiByOutput[id]
                        ?: throw SsaInconsistencyException("Kept SSA value ${id.value} has no retained phi node.")
                    definition.copy(inputs = phi.inputs)
                }

                else -> definition
            }
        }

        val uses = rebuildSsaUses(values, keptOperations, keptPhiNodes, "Pruned")
        val constants = analysis.constants.filterKeys { it in values }

        return SsaControlFlowPruningResult(
            analysis = analysis.copy(
                values = values,
                operations = keptOperations,
                phiNodes = keptPhiNodes,
                uses = uses,
                constants = constants,
            ),
            removedUnreachableBlockCount = controlFlow.blocks.count { it !in reachableBlocks },
            removedOperationCount = analysis.operations.size - keptOperations.size,
            removedPhiNodeCount = analysis.phiNodes.size - keptPhiNodes.size,
            removedPhiInputCount = removedPhiInputCount,
            removedValueCount = analysis.values.size - values.size,
            retainedUnreachableOperationCount = retainedUnreachableOperationCount,
            retainedUnreachablePhiCount = retainedUnreachablePhiCount,
            conservativelyRetainedPhiCount = conservativelyRetainedPhiCount,
        )
    }

    private fun buildBlockIndex(graph: ControlFlowGraph): Map<Int, BasicBlockId> {
        val result = HashMap<Int, BasicBlockId>()
        graph.blocks.forEach { block ->
            for (instructionIndex in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                result[instructionIndex] = block.id
            }
        }
        return result
    }
}

data class SsaControlFlowPruningResult(
    val analysis: SsaAnalysis,
    val removedUnreachableBlockCount: Int,
    val removedOperationCount: Int,
    val removedPhiNodeCount: Int,
    val removedPhiInputCount: Int,
    val removedValueCount: Int,
    val retainedUnreachableOperationCount: Int,
    val retainedUnreachablePhiCount: Int,
    val conservativelyRetainedPhiCount: Int,
)
