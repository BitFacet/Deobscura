package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*

/** Builds coarse structural fingerprints for unsupported exception regions used only for corpus triage. */
internal object ExceptionResidualProfiler {
    /**
     * Describes a rejected catch-all region without attempting recognition again. Buckets stay coarse
     * on purpose so recurring compiler shapes converge on a small number of corpus families.
     */
    fun profileCatchAll(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        exceptionTopology: ExceptionTopology,
        protectedBlocks: Set<BasicBlockId>,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        facts: ControlFlowFacts,
        legacyDetail: String?,
    ): String {
        val group = topology.group
        if (legacyDetail != null) {
            val catchAllCount = group.handlers.count { it.catchType == null }
            val typedCount = group.handlers.size - catchAllCount
            val entries = groupedHandlers.keys.filterNotNull()
            val entryShapes = entries
                .map { entry -> classifyLegacyHandlerEntry(graph, entry) }
                .distinct()
                .sorted()

            return buildString {
                append("legacy [").append(legacyDetail).append(']')
                append(", catch-all=").append(countBucket(catchAllCount))
                append(", typed=").append(countBucket(typedCount))
                append(", entries=").append(countBucket(entries.size))
                if (entryShapes.isNotEmpty()) append(", entry=").append(entryShapes.joinToString("+"))
            }
        }

        val catchAllHandlers = group.handlers.filter { it.catchType == null }
        val typedHandlerCount = group.handlers.size - catchAllHandlers.size
        val boundaryCount = normalBoundaryTargets(protectedBlocks, topology.handlerEntries, facts).size
        val nestedGroupCount = exceptionTopology.groups.count { candidate ->
            candidate !== topology &&
                    candidate.group.envelope.start >= group.envelope.start &&
                    candidate.group.envelope.endExclusive <= group.envelope.endExclusive
        }
        val peerCount = catchAllHandlers.maxOfOrNull { handler ->
            val handlerIndex = exceptionLabelPosition(exceptionTopology.labelPositions, handler.handler)
            exceptionTopology.catchAllPeersByHandlerInstructionIndex[handlerIndex].orEmpty().size
        } ?: 0
        val handlerShape = groupedHandlers.keys.filterNotNull().singleOrNull()?.let { entry ->
            classifyHandlerEntry(graph, entry)
        } ?: if (groupedHandlers.size > 1) "multiple-entries" else "missing-entry"

        return buildString {
            append("modern ")
            append(if (typedHandlerCount == 0) "catch-all-only" else "mixed+$typedHandlerCount")
            append(", handler=").append(handlerShape)
            append(", boundaries=").append(countBucket(boundaryCount))
            append(", peers=").append(countBucket(peerCount))
            append(", nested=").append(countBucket(nestedGroupCount))
            if (hasExternalProtectedEntry(header, protectedBlocks, facts)) append(", protected-external-entry")
        }
    }

    private fun classifyLegacyHandlerEntry(graph: ControlFlowGraph, entry: BasicBlockId): String {
        val size = graph.instructions(graph.block(entry)).size
        return when (size) {
            0 -> "empty"
            1 -> "size-1"
            2 -> "size-2"
            3 -> "size-3"
            in 4..6 -> "size-4-6"
            else -> "size-7+"
        }
    }

    private fun classifyHandlerEntry(graph: ControlFlowGraph, entry: BasicBlockId): String {
        val instructions = graph.instructions(graph.block(entry))
        if (instructions.isEmpty()) return "empty"
        val first = instructions.firstOrNull() as? RawLocalInstruction ?: return "noncanonical-entry"
        if (first.operation != LocalOperation.STORE || first.type != JvmComputationalType.REFERENCE) {
            return "noncanonical-entry"
        }
        if (instructions.size == 1) return "exception-store-only"

        val reload = instructions.getOrNull(instructions.lastIndex - 1) as? RawLocalInstruction
        if (instructions.lastOrNull() is RawThrowInstruction &&
            reload?.operation == LocalOperation.LOAD &&
            reload.slot == first.slot
        ) {
            return "linear-rethrow"
        }
        return "exception-store-prefix"
    }

    private fun countBucket(count: Int): String = when (count) {
        0 -> "0"
        1 -> "1"
        2 -> "2"
        in 3..4 -> "3-4"
        else -> "5+"
    }
}
