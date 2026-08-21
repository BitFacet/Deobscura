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
    private val modernTryCatchFinallyRecognizer = ModernTryCatchFinallyRecognizer(typedCatchRecognizer)

    /** Reconstructs all exception constructs for one method without weakening failed proofs. */
    fun recognize(
        graph: ControlFlowGraph, facts: ControlFlowFacts, legacySubroutineNormalized: Boolean, legacySubroutineProvenance: LegacySubroutineProvenance? = null
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

            val synchronizedTrace = mutableListOf<String>()
            val synchronizedRecognition = SynchronizedExceptionRecognizer.recognize(
                graph = graph,
                topology = topology,
                header = header,
                groupedHandlers = groupedHandlers,
                allGroups = groupTopologies,
                facts = facts,
                rejectionTrace = synchronizedTrace,
            )
            if (synchronizedRecognition != null) {
                regions += synchronizedRecognition.region
                consumedGroups += synchronizedRecognition.consumedGroupKeys
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

            val modernTryCatchFinallyTrace = mutableListOf<String>()
            val modernTryCatchFinallyRecognition = modernTryCatchFinallyRecognizer.recognize(
                graph = graph,
                topology = topology,
                header = header,
                exceptionTopology = exceptionTopology,
                facts = facts,
                rejectionTrace = modernTryCatchFinallyTrace,
            )
            if (modernTryCatchFinallyRecognition != null) {
                regions += modernTryCatchFinallyRecognition.region
                consumedGroups += modernTryCatchFinallyRecognition.consumedGroupKeys
                return@forEach
            }

            val finallyRegion = ModernFinallyRecognizer.recognize(
                graph = graph,
                topology = topology,
                header = header, // Unlike catches, finally must keep source-terminal transfer blocks outside the
                // physical try range: those blocks can contain the duplicated cleanup before return.
                protectedBlocks = topology.protectedBlocks,
                groupedHandlers = groupedHandlers,
                facts = facts,
            )
            if (finallyRegion != null) {
                regions += finallyRegion
            } else {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED
                legacyRejectionDetails[key] = buildString {
                    append("synchronized=")
                    append(synchronizedTrace.lastOrNull() ?: "not-attempted")
                    synchronizedResidualContext(topology, regions)?.let { context ->
                        append(", synchronized-context=")
                        append(context)
                    }
                    append(", modern-try-catch-finally=")
                    append(modernTryCatchFinallyTrace.lastOrNull() ?: "not-attempted")
                    if (legacySubroutineNormalized) {
                        append(", legacy-try-catch-finally=")
                        append(legacyTryCatchFinallyTrace.lastOrNull() ?: "not-attempted")
                        append(", legacy-finally=")
                        append(legacyFinallyTrace.lastOrNull() ?: "not-attempted")
                    }
                }
                val legacyResidualDetail = if (legacySubroutineNormalized) {
                    buildString {
                        append("legacy-try-catch-finally=")
                        append(legacyTryCatchFinallyTrace.lastOrNull() ?: "not-attempted")
                        append(", legacy-finally=")
                        append(legacyFinallyTrace.lastOrNull() ?: "not-attempted")
                    }
                } else {
                    null
                }
                residualFamilies[key] = ExceptionResidualProfiler.profileCatchAll(
                    graph = graph,
                    topology = topology,
                    header = header,
                    exceptionTopology = exceptionTopology,
                    protectedBlocks = protectedBlocks,
                    groupedHandlers = groupedHandlers,
                    facts = facts,
                    legacyDetail = legacyResidualDetail,
                )
            }
        }

        return ExceptionRecognition(regions, rejections, groupTopologies.size, legacyRejectionDetails, residualFamilies)
    }

}

private fun synchronizedResidualContext(
    topology: ExceptionGroupTopology,
    regions: List<StructuredRegion>,
): String? {
    val range = topology.group.envelope
    val candidates = regions.asSequence().filterIsInstance<StructuredRegion.Synchronized>().filter { region -> region.monitorEnterInstructionIndex < range.endExclusive }.map { region ->
        val lastNormalExit = region.normalMonitorExitInstructionIndices.maxOrNull() ?: return@map null
        val bodyOverlap = topology.protectedBlocks.count { block -> block in region.bodyBlocks }
        val handlerOverlap = topology.protectedBlocks.count { block -> block in region.handlerBlocks }
        val handlerEntryOverlap = topology.handlerEntries.count { block -> block in region.handlerBlocks }
        val distance = when {
            range.endExclusive <= region.monitorEnterInstructionIndex -> region.monitorEnterInstructionIndex - range.endExclusive
            range.start > lastNormalExit -> range.start - lastNormalExit
            else -> 0
        }
        SynchronizedResidualContext(
            region = region,
            lastNormalExit = lastNormalExit,
            bodyOverlap = bodyOverlap,
            handlerOverlap = handlerOverlap,
            handlerEntryOverlap = handlerEntryOverlap,
            distance = distance,
        )
    }.filterNotNull().sortedWith(compareBy<SynchronizedResidualContext> { context -> context.distance }.thenByDescending { context -> context.region.monitorEnterInstructionIndex }).take(2).toList()
    if (candidates.isEmpty()) return null

    return candidates.joinToString(";") { context ->
        val region = context.region
        buildString {
            append("monitor@")
            append(region.monitorEnterInstructionIndex)
            append("/handler-exit@")
            append(region.handlerMonitorExitInstructionIndex)
            append("/normal-exit@")
            append(context.lastNormalExit)
            append("/position=")
            append(
                when {
                    range.endExclusive <= region.monitorEnterInstructionIndex -> "before-monitor"
                    range.start > context.lastNormalExit -> "after-monitor"
                    range.start > region.handlerMonitorExitInstructionIndex -> "between-handler-and-normal-exit"
                    else -> "inside-monitor-span"
                },
            )
            append("/body-overlap=")
            append(context.bodyOverlap)
            append("/handler-overlap=")
            append(context.handlerOverlap)
            append("/handler-entry-overlap=")
            append(context.handlerEntryOverlap)
        }
    }
}

private data class SynchronizedResidualContext(
    val region: StructuredRegion.Synchronized,
    val lastNormalExit: Int,
    val bodyOverlap: Int,
    val handlerOverlap: Int,
    val handlerEntryOverlap: Int,
    val distance: Int,
)

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
