package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import org.slf4j.Logger

/** Internal accumulator used only while producing the SSA diagnostic log. */
internal class SsaDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var valueCount = 0L
    private var operationCount = 0L
    private var phiCount = 0L
    private var localPhiCount = 0L
    private var stackPhiCount = 0L
    private var phiBlockCount = 0L
    private var trivialPhiCount = 0L
    private var singlePredecessorPhiCount = 0L
    private var singlePredecessorPhiBlockCount = 0L
    private var singlePredecessorExceptionPhiCount = 0L
    private var singlePredecessorExceptionPhiBlockCount = 0L
    private var singlePredecessorNonExceptionPhiCount = 0L
    private var singlePredecessorNonExceptionPhiBlockCount = 0L
    private var zeroPredecessorPhiCount = 0L
    private var maxPhiNodesPerBlock = 0
    private var useEdgeCount = 0L
    private var eliminatedLocalInstructionCount = 0L
    private var maxOptimizationIterationCount = 0
    private var multiIterationMethodCount = 0
    private var propagatedAliasCount = 0L
    private var constantValueCount = 0L
    private var literalConstantCount = 0L
    private var foldedConstantOperationCount = 0L
    private var constantPhiCount = 0L
    private var newlyExposedConstantCount = 0L
    private var resolvedConstantBranchCount = 0L
    private var resolvedConstantSwitchCount = 0L
    private var eliminatedConstantEdgeCount = 0L
    private var constantNewlyUnreachableBlockCount = 0L
    private var prunedOperationCount = 0L
    private var prunedPhiNodeCount = 0L
    private var prunedPhiInputCount = 0L
    private var prunedValueCount = 0L
    private var deadOperationCount = 0L
    private var deadValueCount = 0L
    private var deadPhiNodeCount = 0L
    private var canonicalizedPassthroughBlockCount = 0L
    private var removedControlFlowOperationCount = 0L
    private var removedGotoOperationCount = 0L
    private var collapsedControlFlowOperationCount = 0L
    private var collapsedControlFlowEdgeCount = 0L
    private var redirectedControlFlowEdgeCount = 0L
    private var canonicalizedMethodCount = 0L
    private var deadCodeMethodCount = 0L
    private var maxDeadOperationCountPerMethod = 0
    private var retainedExceptionalProvenanceOperationCount = 0L
    private var retainedExceptionalProvenancePhiCount = 0L
    private var conservativelyRetainedPhiCount = 0L
    var inconsistencyCount = 0
    var failureCount = 0
    private val nonExceptionSinglePredecessorPhiDetails = mutableListOf<String>()

    fun record(
        methodName: String,
        graph: ControlFlowGraph,
        initial: SsaAnalysis,
        optimization: SsaOptimizationResult,
    ) {
        analyzedMethodCount++
        recordInitialAnalysis(methodName, graph, initial)
        recordOptimization(optimization)
    }

    private fun recordInitialAnalysis(methodName: String, graph: ControlFlowGraph, initial: SsaAnalysis) {
        valueCount += initial.values.size
        operationCount += initial.operations.size
        phiCount += initial.phiNodes.size
        localPhiCount += initial.localPhiCount
        stackPhiCount += initial.stackPhiCount
        phiBlockCount += initial.phiBlockCount
        trivialPhiCount += initial.trivialPhiCount
        useEdgeCount += initial.useEdgeCount
        eliminatedLocalInstructionCount += initial.eliminatedLocalInstructionCount

        val phiNodesByBlock = initial.phiNodes.groupBy { it.blockId }
        maxPhiNodesPerBlock = maxOf(maxPhiNodesPerBlock, phiNodesByBlock.values.maxOfOrNull { it.size } ?: 0)
        phiNodesByBlock.forEach { (blockId, phiNodes) ->
            val predecessorCount = graph.block(blockId).predecessors.size
            if (predecessorCount > 1) return@forEach

            val incomingEdges = graph.edges.filter { it.to == blockId }
            val hasExceptionEdge = incomingEdges.any { it.kind == ControlFlowEdgeKind.EXCEPTION }
            singlePredecessorPhiCount += phiNodes.size
            singlePredecessorPhiBlockCount++
            if (predecessorCount == 0) zeroPredecessorPhiCount += phiNodes.size

            if (hasExceptionEdge) {
                singlePredecessorExceptionPhiCount += phiNodes.size
                singlePredecessorExceptionPhiBlockCount++
            } else {
                singlePredecessorNonExceptionPhiCount += phiNodes.size
                singlePredecessorNonExceptionPhiBlockCount++
                recordUnexpectedPhiDetails(methodName, blockId.value, predecessorCount, incomingEdges, phiNodes)
            }
        }
    }

    private fun recordOptimization(optimization: SsaOptimizationResult) {
        val stats = optimization.stats
        maxOptimizationIterationCount = maxOf(maxOptimizationIterationCount, stats.iterationCount)
        if (stats.iterationCount > 1) multiIterationMethodCount++
        propagatedAliasCount += stats.propagatedAliasCount
        constantValueCount += stats.constantValueCount
        literalConstantCount += stats.literalConstantCount
        foldedConstantOperationCount += stats.foldedConstantOperationCount
        constantPhiCount += stats.constantPhiCount
        newlyExposedConstantCount += stats.newlyExposedConstantCount
        resolvedConstantBranchCount += stats.resolvedConditionalBranchCount
        resolvedConstantSwitchCount += stats.resolvedSwitchCount
        eliminatedConstantEdgeCount += optimization.eliminatedEdges.size
        constantNewlyUnreachableBlockCount += stats.newlyUnreachableBlockCount
        prunedOperationCount += stats.removedOperationCount
        prunedPhiNodeCount += stats.removedPhiNodeCount
        prunedPhiInputCount += stats.removedPhiInputCount
        prunedValueCount += stats.removedValueCount
        deadOperationCount += stats.deadOperationCount
        deadValueCount += stats.deadValueCount
        deadPhiNodeCount += stats.deadPhiNodeCount
        canonicalizedPassthroughBlockCount += stats.canonicalizedPassthroughBlockCount
        removedControlFlowOperationCount += stats.removedControlFlowOperationCount
        removedGotoOperationCount += stats.removedGotoOperationCount
        collapsedControlFlowOperationCount += stats.collapsedControlFlowOperationCount
        collapsedControlFlowEdgeCount += stats.collapsedControlFlowEdgeCount
        redirectedControlFlowEdgeCount += stats.redirectedControlFlowEdgeCount
        if (stats.canonicalizedPassthroughBlockCount > 0 || stats.removedControlFlowOperationCount > 0) {
            canonicalizedMethodCount++
        }
        if (stats.deadOperationCount > 0) deadCodeMethodCount++
        maxDeadOperationCountPerMethod = maxOf(maxDeadOperationCountPerMethod, stats.deadOperationCount)
        retainedExceptionalProvenanceOperationCount += stats.retainedUnreachableOperationCount
        retainedExceptionalProvenancePhiCount += stats.retainedUnreachablePhiCount
        conservativelyRetainedPhiCount += stats.conservativelyRetainedPhiCount
    }

    private fun recordUnexpectedPhiDetails(
        methodName: String,
        blockId: Int,
        predecessorCount: Int,
        incomingEdges: List<ControlFlowEdge>,
        phiNodes: List<SsaPhiNode>,
    ) {
        if (nonExceptionSinglePredecessorPhiDetails.size >= MAX_PHI_PLACEMENT_DETAILS) return
        val incoming = incomingEdges.joinToString(",") { "${it.from.value}:${it.kind}" }.ifEmpty { "none" }
        val locations = phiNodes.joinToString(",") { it.location.toDiagnosticString() }
        nonExceptionSinglePredecessorPhiDetails +=
            "$methodName block=$blockId, predecessors=$predecessorCount, incoming=$incoming, phi=${phiNodes.size} [$locations]"
    }

    fun log(logger: Logger) {
        logger.info(
            "Built SSA for {}/{} method(s): {} values, {} operation(s), {} phi node(s) ({} local, {} stack), {} def-use edge(s), {} local load/store instruction(s) eliminated.",
            analyzedMethodCount,
            methodCount,
            valueCount,
            operationCount,
            phiCount,
            localPhiCount,
            stackPhiCount,
            useEdgeCount,
            eliminatedLocalInstructionCount,
        )
        logger.info(
            "SSA phi placement: {} block(s), {} trivial phi node(s), {} phi node(s) in {} block(s) with at most one CFG predecessor ({} with no predecessor).",
            phiBlockCount,
            trivialPhiCount,
            singlePredecessorPhiCount,
            singlePredecessorPhiBlockCount,
            zeroPredecessorPhiCount,
        )
        logger.info(
            "SSA single-predecessor phi classification: {} phi node(s) in {} exception-related block(s), {} phi node(s) in {} non-exception block(s).",
            singlePredecessorExceptionPhiCount,
            singlePredecessorExceptionPhiBlockCount,
            singlePredecessorNonExceptionPhiCount,
            singlePredecessorNonExceptionPhiBlockCount,
        )
        nonExceptionSinglePredecessorPhiDetails.forEach {
            logger.debug("SSA non-exception single-predecessor phi: {}", it)
        }
        logger.info("SSA phi density: maximum {} phi node(s) in one basic block.", maxPhiNodesPerBlock)
        logger.info(
            "SSA optimization reached fixed point for {} method(s): maximum {} iteration(s), {} method(s) required multiple iterations.",
            analyzedMethodCount,
            maxOptimizationIterationCount,
            multiIterationMethodCount,
        )
        logger.info(
            "SSA optimization resolved {} conditional branch(es) and {} switch(es): {} CFG edge(s) eliminated, {} additional block(s) became unreachable.",
            resolvedConstantBranchCount,
            resolvedConstantSwitchCount,
            eliminatedConstantEdgeCount,
            constantNewlyUnreachableBlockCount,
        )
        logger.info(
            "SSA CFG pruning removed {} operation(s), {} value(s), {} phi node(s), and {} phi input(s); propagated {} value alias(es).",
            prunedOperationCount,
            prunedValueCount,
            prunedPhiNodeCount,
            prunedPhiInputCount,
            propagatedAliasCount,
        )
        logger.info(
            "SSA dead-value elimination removed {} operation(s), {} value(s), and {} phi node(s) in {} method(s); maximum {} operation(s) removed from one method.",
            deadOperationCount,
            deadValueCount,
            deadPhiNodeCount,
            deadCodeMethodCount,
            maxDeadOperationCountPerMethod,
        )
        logger.info(
            "SSA CFG canonicalization removed {} passthrough block(s) and {} redundant control-flow operation(s) in {} method(s): {} goto, {} resolved/redundant branch or switch; {} edge(s) collapsed, {} redirected.",
            canonicalizedPassthroughBlockCount,
            removedControlFlowOperationCount,
            canonicalizedMethodCount,
            removedGotoOperationCount,
            collapsedControlFlowOperationCount,
            collapsedControlFlowEdgeCount,
            redirectedControlFlowEdgeCount,
        )
        logger.info(
            "SSA optimization finished with {} constant value(s): {} literal(s), {} operation(s) folded, {} phi result(s) resolved, {} newly exposed after pruning.",
            constantValueCount,
            literalConstantCount,
            foldedConstantOperationCount,
            constantPhiCount,
            newlyExposedConstantCount,
        )
        if (
            retainedExceptionalProvenanceOperationCount > 0 ||
            retainedExceptionalProvenancePhiCount > 0 ||
            conservativelyRetainedPhiCount > 0
        ) {
            logger.debug(
                "SSA CFG pruning retained {} unreachable operation(s), {} unreachable phi node(s), and left {} exception-related phi node(s) conservative.",
                retainedExceptionalProvenanceOperationCount,
                retainedExceptionalProvenancePhiCount,
                conservativelyRetainedPhiCount,
            )
        }
        logger.info(
            "SSA analysis completed with {} failure(s): {} inconsistent state(s).",
            failureCount,
            inconsistencyCount,
        )
    }

    private companion object {
        const val MAX_PHI_PLACEMENT_DETAILS = 32
    }
}

private fun SsaPhiLocation.toDiagnosticString(): String =
    when (this) {
        is SsaPhiLocation.Local -> "local[$slot]"
        is SsaPhiLocation.Stack -> "stack[$index]"
    }
