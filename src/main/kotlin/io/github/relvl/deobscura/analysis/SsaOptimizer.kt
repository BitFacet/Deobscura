package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowGraph

/**
 * Runs monotonic SSA simplification passes until no further structural change is possible.
 *
 * Constant propagation itself reaches a value fixed point for the current SSA graph. Branch
 * pruning can then remove CFG edges and phi inputs, which can expose aliases and new constants;
 * those changes are fed into the next iteration. RawCode and the original CFG remain unchanged.
 */
class SsaOptimizer(
    private val simplifier: SsaSimplifier = SsaSimplifier(),
    private val constantPropagator: SsaConstantPropagator = SsaConstantPropagator(),
    private val constantBranchAnalyzer: SsaConstantBranchAnalyzer = SsaConstantBranchAnalyzer(),
    private val controlFlowPruner: SsaControlFlowPruner = SsaControlFlowPruner(),
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {
    init {
        require(maxIterations > 0) { "maxIterations must be positive." }
    }

    fun optimize(graph: ControlFlowGraph, initialAnalysis: SsaAnalysis): SsaOptimizationResult {
        val initialSimplification = simplifier.simplify(initialAnalysis)
        var analysis = initialSimplification.analysis
        var eliminatedEdges = emptySet<ControlFlowEdge>()
        var iterationCount = 0

        var propagatedAliasCount = initialSimplification.propagatedAliasCount
        var removedPhiNodeCount = initialSimplification.removedPhiCount
        var resolvedConditionalBranchCount = 0
        var resolvedSwitchCount = 0
        var newlyUnreachableBlockCount = 0
        var removedOperationCount = 0
        var removedValueCount = 0
        var removedPhiInputCount = 0
        var retainedUnreachableOperationCount = 0
        var retainedUnreachablePhiCount = 0
        var conservativelyRetainedPhiCount = 0
        var newlyExposedConstantCount = 0
        var finalConstantValueCount = 0
        var finalLiteralConstantCount = 0
        var finalFoldedOperationCount = 0
        var finalConstantPhiCount = 0

        while (true) {
            if (iterationCount >= maxIterations) {
                throw SsaInconsistencyException(
                    "SSA optimization did not reach a fixed point after $maxIterations iteration(s).",
                )
            }
            iterationCount++

            val constantsBefore = analysis.constants.keys
            val constants = constantPropagator.propagate(analysis)
            if (iterationCount > 1) {
                newlyExposedConstantCount += constants.analysis.constants.keys.count { it !in constantsBefore }
            }

            val branches = constantBranchAnalyzer.analyze(graph, constants.analysis, eliminatedEdges)
            val pruning = controlFlowPruner.prune(graph, constants.analysis, branches)
            val simplification = simplifier.simplify(pruning.analysis)

            resolvedConditionalBranchCount += branches.resolvedConditionalBranchCount
            resolvedSwitchCount += branches.resolvedSwitchCount
            newlyUnreachableBlockCount += branches.newlyUnreachableBlockCount
            removedOperationCount += pruning.removedOperationCount
            removedValueCount += pruning.removedValueCount
            removedPhiNodeCount += pruning.removedPhiNodeCount + simplification.removedPhiCount
            removedPhiInputCount += pruning.removedPhiInputCount
            propagatedAliasCount += simplification.propagatedAliasCount
            retainedUnreachableOperationCount += pruning.retainedUnreachableOperationCount
            retainedUnreachablePhiCount += pruning.retainedUnreachablePhiCount
            conservativelyRetainedPhiCount += pruning.conservativelyRetainedPhiCount

            val changed = branches.newlyEliminatedEdgeCount > 0 ||
                pruning.removedOperationCount > 0 ||
                pruning.removedValueCount > 0 ||
                pruning.removedPhiNodeCount > 0 ||
                pruning.removedPhiInputCount > 0 ||
                simplification.propagatedAliasCount > 0 ||
                simplification.removedPhiCount > 0

            analysis = simplification.analysis
            eliminatedEdges = branches.eliminatedEdges
            finalConstantValueCount = constants.constantValueCount
            finalLiteralConstantCount = constants.literalConstantCount
            finalFoldedOperationCount = constants.foldedOperationCount
            finalConstantPhiCount = constants.constantPhiCount

            if (!changed) break
        }

        return SsaOptimizationResult(
            analysis = analysis,
            iterationCount = iterationCount,
            eliminatedEdges = eliminatedEdges,
            propagatedAliasCount = propagatedAliasCount,
            removedPhiNodeCount = removedPhiNodeCount,
            resolvedConditionalBranchCount = resolvedConditionalBranchCount,
            resolvedSwitchCount = resolvedSwitchCount,
            newlyUnreachableBlockCount = newlyUnreachableBlockCount,
            removedOperationCount = removedOperationCount,
            removedValueCount = removedValueCount,
            removedPhiInputCount = removedPhiInputCount,
            constantValueCount = finalConstantValueCount,
            literalConstantCount = finalLiteralConstantCount,
            foldedConstantOperationCount = finalFoldedOperationCount,
            constantPhiCount = finalConstantPhiCount,
            newlyExposedConstantCount = newlyExposedConstantCount,
            retainedUnreachableOperationCount = retainedUnreachableOperationCount,
            retainedUnreachablePhiCount = retainedUnreachablePhiCount,
            conservativelyRetainedPhiCount = conservativelyRetainedPhiCount,
        )
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 32
    }
}

data class SsaOptimizationResult(
    val analysis: SsaAnalysis,
    val iterationCount: Int,
    val eliminatedEdges: Set<ControlFlowEdge>,
    val propagatedAliasCount: Int,
    val removedPhiNodeCount: Int,
    val resolvedConditionalBranchCount: Int,
    val resolvedSwitchCount: Int,
    val newlyUnreachableBlockCount: Int,
    val removedOperationCount: Int,
    val removedValueCount: Int,
    val removedPhiInputCount: Int,
    val constantValueCount: Int,
    val literalConstantCount: Int,
    val foldedConstantOperationCount: Int,
    val constantPhiCount: Int,
    val newlyExposedConstantCount: Int,
    val retainedUnreachableOperationCount: Int,
    val retainedUnreachablePhiCount: Int,
    val conservativelyRetainedPhiCount: Int,
) {
    val eliminatedEdgeCount: Int
        get() = eliminatedEdges.size
}
