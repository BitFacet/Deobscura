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
    private val ssaSimplifier: SsaSimplifier = SsaSimplifier(),
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
        var propagatedAliasCount = 0L
        var simplifiedPhiCount = 0L
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
                    val simplification = ssaSimplifier.simplify(initialAnalysis)
                    val analysis = simplification.analysis
                    propagatedAliasCount += simplification.propagatedAliasCount
                    simplifiedPhiCount += simplification.removedPhiCount
                    valueCount += analysis.values.size
                    operationCount += analysis.operations.size
                    phiCount += analysis.phiNodes.size
                    localPhiCount += analysis.localPhiCount
                    stackPhiCount += analysis.stackPhiCount
                    phiBlockCount += analysis.phiBlockCount
                    trivialPhiCount += analysis.trivialPhiCount

                    val phiNodesByBlock = analysis.phiNodes.groupBy { it.blockId }
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

                    useEdgeCount += analysis.useEdgeCount
                    eliminatedLocalInstructionCount += analysis.eliminatedLocalInstructionCount
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
            propagatedAliasCount = propagatedAliasCount,
            simplifiedPhiCount = simplifiedPhiCount,
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
    val propagatedAliasCount: Long,
    val simplifiedPhiCount: Long,
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
