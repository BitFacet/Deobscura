package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawImportResult

class SsaDiagnostics(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val frameAnalyzer: FrameAnalyzer = FrameAnalyzer(),
    private val valueFlowAnalyzer: ValueFlowAnalyzer = ValueFlowAnalyzer(),
    private val ssaAnalyzer: SsaAnalyzer = SsaAnalyzer(),
    private val ssaOptimizer: SsaOptimizer = SsaOptimizer(),
    private val legacySubroutineNormalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun inspect(rawImport: RawImportResult): SsaDiagnosticsResult {
        var methodCount = 0
        var valueCount = 0L
        var operationCount = 0L
        var phiCount = 0L
        var localPhiCount = 0L
        var stackPhiCount = 0L
        var phiBlockCount = 0L
        var trivialPhiCount = 0L
        var singlePredecessorPhiCount = 0L
        var singlePredecessorPhiBlockCount = 0L
        var singlePredecessorExceptionPhiCount = 0L
        var singlePredecessorExceptionPhiBlockCount = 0L
        var singlePredecessorNonExceptionPhiCount = 0L
        var singlePredecessorNonExceptionPhiBlockCount = 0L
        var zeroPredecessorPhiCount = 0L
        val nonExceptionSinglePredecessorPhiDetails = mutableListOf<String>()
        var maxPhiNodesPerBlock = 0
        var useEdgeCount = 0L
        var eliminatedLocalInstructionCount = 0L
        var maxOptimizationIterationCount = 0
        var multiIterationMethodCount = 0
        var propagatedAliasCount = 0L
        var constantValueCount = 0L
        var literalConstantCount = 0L
        var foldedConstantOperationCount = 0L
        var constantPhiCount = 0L
        var newlyExposedConstantCount = 0L
        var resolvedConstantBranchCount = 0L
        var resolvedConstantSwitchCount = 0L
        var eliminatedConstantEdgeCount = 0L
        var constantNewlyUnreachableBlockCount = 0L
        var prunedOperationCount = 0L
        var prunedPhiNodeCount = 0L
        var prunedPhiInputCount = 0L
        var prunedValueCount = 0L
        var retainedExceptionalProvenanceOperationCount = 0L
        var retainedExceptionalProvenancePhiCount = 0L
        var conservativelyRetainedPhiCount = 0L
        var inconsistencyCount = 0
        var failureCount = 0
        val warnings = mutableListOf<String>()

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                val code = method.code ?: continue
                methodCount++
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"
                try {
                    val normalizedCode = legacySubroutineNormalizer.normalize(code).code
                    val normalizedMethod = if (normalizedCode === code) method else method.copy(code = normalizedCode)
                    val graph = graphBuilder.build(normalizedCode)
                    val frames = frameAnalyzer.analyze(rawClass.internalName, normalizedMethod, graph)
                    val valueFlow = valueFlowAnalyzer.analyze(graph, frames)
                    val initialAnalysis = ssaAnalyzer.analyze(graph, valueFlow)
                    val optimization = ssaOptimizer.optimize(graph, initialAnalysis)
                    maxOptimizationIterationCount = maxOf(maxOptimizationIterationCount, optimization.iterationCount)
                    if (optimization.iterationCount > 1) multiIterationMethodCount++
                    propagatedAliasCount += optimization.propagatedAliasCount
                    constantValueCount += optimization.constantValueCount
                    literalConstantCount += optimization.literalConstantCount
                    foldedConstantOperationCount += optimization.foldedConstantOperationCount
                    constantPhiCount += optimization.constantPhiCount
                    newlyExposedConstantCount += optimization.newlyExposedConstantCount
                    resolvedConstantBranchCount += optimization.resolvedConditionalBranchCount
                    resolvedConstantSwitchCount += optimization.resolvedSwitchCount
                    eliminatedConstantEdgeCount += optimization.eliminatedEdgeCount
                    constantNewlyUnreachableBlockCount += optimization.newlyUnreachableBlockCount
                    prunedOperationCount += optimization.removedOperationCount
                    prunedPhiNodeCount += optimization.removedPhiNodeCount
                    prunedPhiInputCount += optimization.removedPhiInputCount
                    prunedValueCount += optimization.removedValueCount
                    retainedExceptionalProvenanceOperationCount += optimization.retainedUnreachableOperationCount
                    retainedExceptionalProvenancePhiCount += optimization.retainedUnreachablePhiCount
                    conservativelyRetainedPhiCount += optimization.conservativelyRetainedPhiCount
                    valueCount += initialAnalysis.values.size
                    operationCount += initialAnalysis.operations.size
                    phiCount += initialAnalysis.phiNodes.size
                    localPhiCount += initialAnalysis.localPhiCount
                    stackPhiCount += initialAnalysis.stackPhiCount
                    phiBlockCount += initialAnalysis.phiBlockCount
                    trivialPhiCount += initialAnalysis.trivialPhiCount

                    val phiNodesByBlock = initialAnalysis.phiNodes.groupBy { it.blockId }
                    maxPhiNodesPerBlock = maxOf(maxPhiNodesPerBlock, phiNodesByBlock.values.maxOfOrNull { it.size } ?: 0)
                    phiNodesByBlock.forEach { (blockId, phiNodes) ->
                        val block = graph.block(blockId)
                        val predecessorCount = block.predecessors.size
                        if (predecessorCount <= 1) {
                            val incomingEdges = graph.edges.filter { it.to == blockId }
                            val hasExceptionEdge = incomingEdges.any { it.kind == ControlFlowEdgeKind.EXCEPTION }

                            singlePredecessorPhiCount += phiNodes.size
                            singlePredecessorPhiBlockCount++
                            if (predecessorCount == 0) {
                                zeroPredecessorPhiCount += phiNodes.size
                            }

                            if (hasExceptionEdge) {
                                singlePredecessorExceptionPhiCount += phiNodes.size
                                singlePredecessorExceptionPhiBlockCount++
                            } else {
                                singlePredecessorNonExceptionPhiCount += phiNodes.size
                                singlePredecessorNonExceptionPhiBlockCount++
                                if (nonExceptionSinglePredecessorPhiDetails.size < MAX_PHI_PLACEMENT_DETAILS) {
                                    val incoming = incomingEdges.joinToString(",") { edge ->
                                        "${edge.from.value}:${edge.kind}"
                                    }.ifEmpty { "none" }
                                    val locations = phiNodes.joinToString(",") { phi -> phi.location.toDiagnosticString() }
                                    nonExceptionSinglePredecessorPhiDetails +=
                                        "$methodName block=${blockId.value}, predecessors=$predecessorCount, incoming=$incoming, phi=${phiNodes.size} [$locations]"
                                }
                            }
                        }
                    }

                    useEdgeCount += initialAnalysis.useEdgeCount
                    eliminatedLocalInstructionCount += initialAnalysis.eliminatedLocalInstructionCount
                } catch (exception: SsaInconsistencyException) {
                    inconsistencyCount++
                    failureCount++
                    warnings += "SSA analysis found inconsistent state in '$methodName': ${exception.message}."
                } catch (exception: Exception) {
                    failureCount++
                    warnings += "Failed SSA analysis for '$methodName': ${exception.message}."
                }
            }
        }

        return SsaDiagnosticsResult(
            methodCount = methodCount,
            valueCount = valueCount,
            operationCount = operationCount,
            phiCount = phiCount,
            localPhiCount = localPhiCount,
            stackPhiCount = stackPhiCount,
            phiBlockCount = phiBlockCount,
            trivialPhiCount = trivialPhiCount,
            singlePredecessorPhiCount = singlePredecessorPhiCount,
            singlePredecessorPhiBlockCount = singlePredecessorPhiBlockCount,
            singlePredecessorExceptionPhiCount = singlePredecessorExceptionPhiCount,
            singlePredecessorExceptionPhiBlockCount = singlePredecessorExceptionPhiBlockCount,
            singlePredecessorNonExceptionPhiCount = singlePredecessorNonExceptionPhiCount,
            singlePredecessorNonExceptionPhiBlockCount = singlePredecessorNonExceptionPhiBlockCount,
            zeroPredecessorPhiCount = zeroPredecessorPhiCount,
            nonExceptionSinglePredecessorPhiDetails = nonExceptionSinglePredecessorPhiDetails,
            maxPhiNodesPerBlock = maxPhiNodesPerBlock,
            useEdgeCount = useEdgeCount,
            eliminatedLocalInstructionCount = eliminatedLocalInstructionCount,
            maxOptimizationIterationCount = maxOptimizationIterationCount,
            multiIterationMethodCount = multiIterationMethodCount,
            propagatedAliasCount = propagatedAliasCount,
            constantValueCount = constantValueCount,
            literalConstantCount = literalConstantCount,
            foldedConstantOperationCount = foldedConstantOperationCount,
            constantPhiCount = constantPhiCount,
            newlyExposedConstantCount = newlyExposedConstantCount,
            resolvedConstantBranchCount = resolvedConstantBranchCount,
            resolvedConstantSwitchCount = resolvedConstantSwitchCount,
            eliminatedConstantEdgeCount = eliminatedConstantEdgeCount,
            constantNewlyUnreachableBlockCount = constantNewlyUnreachableBlockCount,
            prunedOperationCount = prunedOperationCount,
            prunedPhiNodeCount = prunedPhiNodeCount,
            prunedPhiInputCount = prunedPhiInputCount,
            prunedValueCount = prunedValueCount,
            retainedExceptionalProvenanceOperationCount = retainedExceptionalProvenanceOperationCount,
            retainedExceptionalProvenancePhiCount = retainedExceptionalProvenancePhiCount,
            conservativelyRetainedPhiCount = conservativelyRetainedPhiCount,
            inconsistencyCount = inconsistencyCount,
            failureCount = failureCount,
            warnings = warnings,
        )
    }
}

data class SsaDiagnosticsResult(
    val methodCount: Int,
    val valueCount: Long,
    val operationCount: Long,
    val phiCount: Long,
    val localPhiCount: Long,
    val stackPhiCount: Long,
    val phiBlockCount: Long,
    val trivialPhiCount: Long,
    val singlePredecessorPhiCount: Long,
    val singlePredecessorPhiBlockCount: Long,
    val singlePredecessorExceptionPhiCount: Long,
    val singlePredecessorExceptionPhiBlockCount: Long,
    val singlePredecessorNonExceptionPhiCount: Long,
    val singlePredecessorNonExceptionPhiBlockCount: Long,
    val zeroPredecessorPhiCount: Long,
    val nonExceptionSinglePredecessorPhiDetails: List<String>,
    val maxPhiNodesPerBlock: Int,
    val useEdgeCount: Long,
    val eliminatedLocalInstructionCount: Long,
    val maxOptimizationIterationCount: Int,
    val multiIterationMethodCount: Int,
    val propagatedAliasCount: Long,
    val constantValueCount: Long,
    val literalConstantCount: Long,
    val foldedConstantOperationCount: Long,
    val constantPhiCount: Long,
    val newlyExposedConstantCount: Long,
    val resolvedConstantBranchCount: Long,
    val resolvedConstantSwitchCount: Long,
    val eliminatedConstantEdgeCount: Long,
    val constantNewlyUnreachableBlockCount: Long,
    val prunedOperationCount: Long,
    val prunedPhiNodeCount: Long,
    val prunedPhiInputCount: Long,
    val prunedValueCount: Long,
    val retainedExceptionalProvenanceOperationCount: Long,
    val retainedExceptionalProvenancePhiCount: Long,
    val conservativelyRetainedPhiCount: Long,
    val inconsistencyCount: Int,
    val failureCount: Int,
    val warnings: List<String>,
)

private fun SsaPhiLocation.toDiagnosticString(): String =
    when (this) {
        is SsaPhiLocation.Local -> "local[$slot]"
        is SsaPhiLocation.Stack -> "stack[$index]"
    }

private const val MAX_PHI_PLACEMENT_DETAILS = 32
