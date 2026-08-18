package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.normalize.LegacySubroutineProvenance

/**
 * Coordinates source-level exception reconstruction over precomputed exception-table topology.
 * Specialized recognizers own the actual proofs; this class only chooses the applicable path,
 * records consumed table groups, and preserves rejection diagnostics.
 */
internal class StructuredExceptionRecognizer {
    private val typedCatchRecognizer = TypedCatchRecognizer()
    private val legacyFinallyRecognizer = LegacyFinallyRecognizer(typedCatchRecognizer)

    /** Reconstructs all exception constructs for one method without weakening failed proofs. */
    fun recognize(
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
        legacySubroutineNormalized: Boolean,
        legacySubroutineProvenance: LegacySubroutineProvenance? = null
    ): ExceptionRecognition {
        if (graph.code.exceptionHandlers.isEmpty()) return ExceptionRecognition(emptyList(), emptyMap(), 0)

        val exceptionTopology = ExceptionTopologyBuilder.build(graph, facts)
        val labelPositions = exceptionTopology.labelPositions
        val groupTopologies = exceptionTopology.groups

        val regions = mutableListOf<StructuredRegion>()
        val rejections = linkedMapOf<ExceptionRegionKey, UnstructuredControlFlowReason>()
        val legacyRejectionDetails = linkedMapOf<ExceptionRegionKey, String>()
        val residualFamilies = linkedMapOf<ExceptionRegionKey, String>()
        val consumedGroups = mutableSetOf<ExceptionRegionKey>()

        groupTopologies.forEach { topology ->
            val group = topology.group
            val range = group.envelope
            val key = ExceptionRegionKey(range.start, range.endExclusive)
            if (key in consumedGroups) return@forEach

            if (group.handlers.none { it.catchType == null }) {
                val typedRecognition = typedCatchRecognizer.recognizeOrdinary(
                    topology = topology,
                    labelPositions = labelPositions,
                    facts = facts,
                    allGroups = groupTopologies,
                )
                if (typedRecognition.failure != null) {
                    rejections[key] = typedRecognition.failure
                } else {
                    regions += requireNotNull(typedRecognition.region)
                }
                return@forEach
            }

            val protectedBlocks = extendExceptionProtectedScopeWithTerminalTransfers(topology.protectedBlocks, facts)
            if (protectedBlocks.isEmpty()) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_EMPTY_PROTECTED_REGION
                return@forEach
            }

            val header = facts.instructionToBlock.getOrNull(range.start)
            if (header == null || header !in protectedBlocks) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_INVALID_PROTECTED_ENTRY
                return@forEach
            }

            val groupedHandlers = group.handlers.groupBy { handler ->
                facts.instructionToBlock.getOrNull(exceptionLabelPosition(labelPositions, handler.handler))
            }
            if (groupedHandlers.keys.any { it == null }) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_INVALID_HANDLER_ENTRY
                return@forEach
            }

            val legacyTryCatchFinallyTrace = mutableListOf<String>()
            val legacyTryCatchFinallyRecognition = if (legacySubroutineNormalized) {
                legacyFinallyRecognizer.recognizeTryCatchFinally(
                    graph = graph,
                    topology = topology,
                    header = header,
                    exceptionTopology = exceptionTopology,
                    facts = facts,
                    provenance = legacySubroutineProvenance,
                    rejectionTrace = legacyTryCatchFinallyTrace,
                )
            } else {
                null
            }
            if (legacyTryCatchFinallyRecognition != null) {
                regions += legacyTryCatchFinallyRecognition.region
                regions += legacyTryCatchFinallyRecognition.additionalRegions
                consumedGroups += legacyTryCatchFinallyRecognition.consumedGroupKeys
                return@forEach
            }

            val legacyFinallyTrace = mutableListOf<String>()
            val legacyFinallyRecognition = if (legacySubroutineNormalized) {
                legacyFinallyRecognizer.recognizeFinally(
                    graph = graph,
                    topology = topology,
                    header = header,
                    allGroups = groupTopologies,
                    labelPositions = labelPositions,
                    facts = facts,
                    provenance = legacySubroutineProvenance,
                    rejectionTrace = legacyFinallyTrace,
                )
            } else {
                null
            }
            if (legacyFinallyRecognition != null) {
                regions += legacyFinallyRecognition.region
                consumedGroups += legacyFinallyRecognition.consumedGroupKeys
                return@forEach
            }

            val synchronizedRecognition = SynchronizedExceptionRecognizer.recognize(
                graph = graph,
                topology = topology,
                header = header,
                groupedHandlers = groupedHandlers,
                allGroups = groupTopologies,
                facts = facts,
            )
            if (synchronizedRecognition != null) {
                regions += synchronizedRecognition.region
                consumedGroups += synchronizedRecognition.consumedGroupKeys
                return@forEach
            }

            val finallyRegion = ModernFinallyRecognizer.recognize(
                graph = graph,
                topology = topology,
                header = header,
                // Unlike catches, finally must keep source-terminal transfer blocks outside the
                // physical try range: those blocks can contain the duplicated cleanup before return.
                protectedBlocks = topology.protectedBlocks,
                groupedHandlers = groupedHandlers,
                facts = facts,
            )
            if (finallyRegion != null) {
                regions += finallyRegion
            } else {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED
                if (legacySubroutineNormalized) {
                    legacyRejectionDetails[key] = buildString {
                        append("legacy-try-catch-finally=")
                        append(legacyTryCatchFinallyTrace.lastOrNull() ?: "not-attempted")
                        append(", legacy-finally=")
                        append(legacyFinallyTrace.lastOrNull() ?: "not-attempted")
                    }
                }
                residualFamilies[key] = ExceptionResidualProfiler.profileCatchAll(
                    graph = graph,
                    topology = topology,
                    header = header,
                    exceptionTopology = exceptionTopology,
                    protectedBlocks = protectedBlocks,
                    groupedHandlers = groupedHandlers,
                    facts = facts,
                    legacyDetail = legacyRejectionDetails[key],
                )
            }
        }

        return ExceptionRecognition(regions, rejections, groupTopologies.size, legacyRejectionDetails, residualFamilies)
    }


}

/** Stable key for diagnostics attached to one coalesced protected range. */
internal data class ExceptionRegionKey(
    val protectedStartInstructionIndex: Int,
    val protectedEndInstructionIndexExclusive: Int,
)

/** Aggregate result consumed by the structured-control-flow analyzer and diagnostics. */
internal data class ExceptionRecognition(
    val regions: List<StructuredRegion>,
    val rejections: Map<ExceptionRegionKey, UnstructuredControlFlowReason>,
    val regionCount: Int,
    val legacyRejectionDetails: Map<ExceptionRegionKey, String> = emptyMap(),
    val residualFamilies: Map<ExceptionRegionKey, String> = emptyMap(),
)
