package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawExceptionHandler
import io.github.relvl.deobscura.raw.RawLabelId
import java.util.ArrayDeque

/**
 * Reconstructs conservative source-level `try/catch` regions directly from exception-table ranges.
 *
 * The exception table defines protected ownership more precisely than exceptional CFG edges do, so
 * this recognizer deliberately starts from metadata and uses normal CFG only to prove handler-body
 * ownership and an optional common continuation. Catch-all/finally semantics are kept block-based
 * until an explicit finally model exists.
 *
 * A single source `try` may be emitted as several adjacent exception-table ranges. javac commonly
 * does this around terminal control transfers such as `return`: the transfer itself cannot throw,
 * so it need not be covered by the table. Compatible ranges separated only by such terminal blocks
 * are therefore coalesced before source-level reconstruction.
 */
internal class StructuredExceptionRecognizer {
    fun recognize(
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): ExceptionRecognition {
        if (graph.code.exceptionHandlers.isEmpty()) return ExceptionRecognition(emptyList(), emptyMap(), 0)

        val labelPositions = graph.code.labels.associate { it.id to it.instructionIndex }
        val segments = graph.code.exceptionHandlers
            .groupBy { handler ->
                ProtectedRange(
                    start = labelPosition(labelPositions, handler.tryStart),
                    endExclusive = labelPosition(labelPositions, handler.tryEnd),
                )
            }
            .entries
            .map { (range, handlers) -> ExceptionTableSegment(range, handlers) }
            .sortedWith(compareBy<ExceptionTableSegment> { it.range.start }.thenBy { it.range.endExclusive })
        val groups = coalesceSegments(segments, labelPositions, facts)
        val groupTopologies = groups.map { group ->
            ExceptionGroupTopology(
                group = group,
                protectedBlocks = protectedBlocks(group.envelope, graph, facts),
                handlerEntries = group.handlers.mapNotNullTo(linkedSetOf()) { handler ->
                    facts.instructionToBlock.getOrNull(labelPosition(labelPositions, handler.handler))
                },
            )
        }

        val regions = mutableListOf<StructuredRegion.TryCatch>()
        val rejections = linkedMapOf<ExceptionRegionKey, UnstructuredControlFlowReason>()

        groupTopologies.forEach { topology ->
            val group = topology.group
            val range = group.envelope
            val handlers = group.handlers
            val key = ExceptionRegionKey(range.start, range.endExclusive)
            val protectedBlocks = extendWithTerminalTransfers(topology.protectedBlocks, facts)
            if (protectedBlocks.isEmpty()) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_EMPTY_PROTECTED_REGION
                return@forEach
            }

            val header = facts.instructionToBlock.getOrNull(range.start)
            if (header == null || header !in protectedBlocks) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_INVALID_PROTECTED_ENTRY
                return@forEach
            }

            val groupedHandlers = handlers.groupBy { handler ->
                facts.instructionToBlock.getOrNull(labelPosition(labelPositions, handler.handler))
            }
            if (groupedHandlers.keys.any { it == null }) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_INVALID_HANDLER_ENTRY
                return@forEach
            }
            val handlerEntries = groupedHandlers.keys.filterNotNull().toSet()
            if (handlers.any { it.catchType == null }) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED
                return@forEach
            }
            if (hasExternalProtectedEntry(header, protectedBlocks, facts)) {
                rejections[key] = UnstructuredControlFlowReason.EXCEPTION_PROTECTED_REGION_HAS_EXTERNAL_ENTRY
                return@forEach
            }

            val continuation = selectContinuation(protectedBlocks, handlerEntries, facts)
                ?: run {
                    val externalTargets = normalBoundaryTargets(protectedBlocks, handlerEntries, facts)
                    if (externalTargets.isNotEmpty()) {
                        rejections[key] = UnstructuredControlFlowReason.EXCEPTION_NO_COMMON_CONTINUATION
                        return@forEach
                    }
                    null
                }

            val collectedCatches = mutableListOf<CollectedCatch>()
            for ((entryOrNull, entries) in groupedHandlers.entries.sortedBy { it.key?.value ?: Int.MAX_VALUE }) {
                val entry = requireNotNull(entryOrNull)
                val collected = collectHandlerBlocks(
                    entry = entry,
                    protectedBlocks = protectedBlocks,
                    handlerEntries = handlerEntries,
                    continuation = continuation,
                    facts = facts,
                    currentGroup = topology,
                    allGroups = groupTopologies,
                )
                if (collected.blocks.isEmpty()) {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_EMPTY_HANDLER_REGION
                    break
                }
                collectedCatches += CollectedCatch(entry, entries, collected)
            }
            if (key in rejections) return@forEach

            val inferredContinuations = collectedCatches.mapNotNullTo(linkedSetOf()) { it.collection.inferredContinuation }
            val regionContinuation = when {
                continuation != null && inferredContinuations.all { it == continuation } -> continuation
                continuation != null -> {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_UNSUPPORTED_HANDLER_EXIT
                    return@forEach
                }
                inferredContinuations.size == 1 -> inferredContinuations.single()
                inferredContinuations.isEmpty() -> inferLoopBackContinuation(
                    header = header,
                    catches = collectedCatches,
                    facts = facts,
                )
                else -> {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_NO_COMMON_CONTINUATION
                    return@forEach
                }
            }

            val catches = mutableListOf<StructuredCatch>()
            val occupiedHandlerBlocks = linkedSetOf<BasicBlockId>()
            for (collected in collectedCatches) {
                val entry = collected.entry
                val blocks = collected.collection.blocks
                if (blocks.any { it in occupiedHandlerBlocks }) {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_OVERLAPPING_HANDLER_REGIONS
                    break
                }
                if (hasExternalHandlerEntry(entry, blocks, handlerEntries, facts)) {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_HANDLER_HAS_EXTERNAL_ENTRY
                    break
                }
                if (!handlerExitsAreSupported(blocks, regionContinuation, facts)) {
                    rejections[key] = UnstructuredControlFlowReason.EXCEPTION_UNSUPPORTED_HANDLER_EXIT
                    break
                }
                occupiedHandlerBlocks += blocks
                catches += StructuredCatch(
                    catchTypes = collected.entries.mapNotNull { it.catchType }.distinct(),
                    entry = entry,
                    blocks = blocks,
                )
            }
            if (key in rejections) return@forEach

            regions += StructuredRegion.TryCatch(
                header = header,
                tryBlocks = protectedBlocks,
                catches = catches,
                continuation = regionContinuation,
                protectedStartInstructionIndex = range.start,
                protectedEndInstructionIndexExclusive = range.endExclusive,
                protectedRanges = group.segments.map { segment ->
                    StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
                },
            )
        }

        return ExceptionRecognition(regions, rejections, groups.size)
    }

    private fun protectedBlocks(
        range: ProtectedRange,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> = graph.blocks.asSequence()
        .filter { block ->
            block.id in facts.blocks &&
                block.startInstructionIndex < range.endExclusive &&
                block.endInstructionIndexExclusive > range.start
        }
        .mapTo(linkedSetOf()) { it.id }

    private fun extendWithTerminalTransfers(
        protectedBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> {
        val result = protectedBlocks.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            val candidates = result.asSequence()
                .flatMap { block -> facts.outgoing[block].orEmpty().asSequence() }
                .map { it.to }
                .filter { it in facts.explicitTerminalBlocks && it !in result }
                .distinct()
                .toList()
            for (candidate in candidates) {
                val incoming = facts.incoming[candidate].orEmpty()
                if (incoming.isNotEmpty() && incoming.all { it.from in result }) {
                    result += candidate
                    changed = true
                }
            }
        } while (changed)
        return result
    }

    private fun coalesceSegments(
        segments: List<ExceptionTableSegment>,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
    ): List<ExceptionTableGroup> {
        if (segments.isEmpty()) return emptyList()

        val result = mutableListOf<ExceptionTableGroup>()
        var current = ExceptionTableGroup(listOf(segments.first()))
        for (next in segments.drop(1)) {
            if (canCoalesce(current, next, labelPositions, facts)) {
                current = ExceptionTableGroup(current.segments + next)
            } else {
                result += current
                current = ExceptionTableGroup(listOf(next))
            }
        }
        result += current
        return result
    }

    private fun canCoalesce(
        current: ExceptionTableGroup,
        next: ExceptionTableSegment,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
    ): Boolean {
        if (handlerSignature(current.handlers, labelPositions) != handlerSignature(next.handlers, labelPositions)) return false
        val currentEnd = current.envelope.endExclusive
        if (next.range.start < currentEnd) return false
        if (next.range.start == currentEnd) return true

        val gapBlocks = (currentEnd until next.range.start)
            .map { instructionIndex -> facts.instructionToBlock.getOrNull(instructionIndex) ?: return false }
            .toSet()
        return gapBlocks.isNotEmpty() && gapBlocks.all { it in facts.explicitTerminalBlocks }
    }

    private fun handlerSignature(
        handlers: List<RawExceptionHandler>,
        labelPositions: Map<RawLabelId, Int>,
    ): List<HandlerSignature> = handlers
        .map { handler -> HandlerSignature(labelPosition(labelPositions, handler.handler), handler.catchType) }
        .sortedWith(compareBy<HandlerSignature> { it.handlerInstructionIndex }.thenBy { it.catchType ?: "" })

    private fun hasExternalProtectedEntry(
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): Boolean = protectedBlocks.any { block ->
        facts.incoming[block].orEmpty().any { edge -> edge.from !in protectedBlocks && block != header }
    }

    private fun hasExternalHandlerEntry(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): Boolean = blocks.any { block ->
        facts.incoming[block].orEmpty().any { edge ->
            edge.from !in blocks && !(block == entry && edge.from in handlerEntries)
        }
    }

    private fun selectContinuation(
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): BasicBlockId? {
        val targets = normalBoundaryTargets(protectedBlocks, handlerEntries, facts)
        if (targets.isEmpty()) return null

        // The immediate normal exit of a protected range is not always the source-level continuation.
        // A compiler may place a bridge block after the protected instructions while the catch body
        // rejoins one block later. When both the normal exits and every non-terminal handler have a
        // common post-dominator, prefer that common source join.
        val allEntries = targets + handlerEntries
        val sourceCommon = allEntries
            .map { block -> facts.postDominators[block].orEmpty() }
            .reduce(Set<BasicBlockId>::intersect)
            .filter { candidate -> candidate !in protectedBlocks && candidate !in handlerEntries }
        val sourceJoin = nearestCommonPostDominator(sourceCommon, facts)
        if (sourceJoin != null) return sourceJoin
        if (targets.size == 1) return targets.single()

        val common = targets
            .map { target -> facts.postDominators[target].orEmpty() }
            .reduce(Set<BasicBlockId>::intersect)
            .filter { candidate -> candidate !in protectedBlocks && candidate !in handlerEntries }
        return nearestCommonPostDominator(common, facts)
    }

    private fun nearestCommonPostDominator(
        candidates: Collection<BasicBlockId>,
        facts: ControlFlowFacts,
    ): BasicBlockId? = candidates.firstOrNull { candidate ->
        candidates.none { other ->
            other != candidate && candidate in facts.postDominators[other].orEmpty()
        }
    }

    private fun normalBoundaryTargets(
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> = protectedBlocks.asSequence()
        .flatMap { block -> facts.outgoing[block].orEmpty().asSequence() }
        .map { it.to }
        .filter { target -> target !in protectedBlocks && target !in handlerEntries }
        .toCollection(linkedSetOf())

    private fun collectHandlerBlocks(
        entry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        facts: ControlFlowFacts,
        currentGroup: ExceptionGroupTopology,
        allGroups: List<ExceptionGroupTopology>,
    ): HandlerCollection {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()

        fun drainQueue() {
            while (queue.isNotEmpty()) {
                val block = queue.removeFirst()
                if (block == continuation || block in protectedBlocks || (block != entry && block in handlerEntries)) continue
                if (!result.add(block)) continue
                if (block in facts.explicitTerminalBlocks) continue
                facts.outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
            }
        }

        queue.add(entry)
        drainQueue()

        // A source catch may itself contain a nested try/catch. Exceptional CFG edges are deliberately
        // absent from ControlFlowFacts, so the nested catch body is not reached by the normal walk
        // above. If the nested protected range is wholly owned by this handler body, its handler is
        // owned by the same source catch as well. Add such handlers and repeat to support deeper nesting.
        val absorbedGroups = mutableSetOf<ExceptionTableGroup>()
        var changed: Boolean
        do {
            changed = false
            for (nested in allGroups) {
                if (nested === currentGroup || nested.group in absorbedGroups) continue
                if (nested.protectedBlocks.isEmpty() || !result.containsAll(nested.protectedBlocks)) continue
                val nestedEntries = nested.handlerEntries.filter { it !in handlerEntries }
                if (nestedEntries.isEmpty()) continue
                absorbedGroups += nested.group
                val before = result.size
                nestedEntries.forEach(queue::addLast)
                drainQueue()
                if (result.size != before) changed = true
            }
        } while (changed)

        if (continuation != null) return HandlerCollection(result, null)

        val inferredContinuation = inferSharedHandlerContinuation(entry, result, facts)
        if (inferredContinuation == null) return HandlerCollection(result, null)

        val trimmed = result.filterTo(linkedSetOf()) { block ->
            inferredContinuation !in facts.dominators[block].orEmpty()
        }
        return HandlerCollection(trimmed, inferredContinuation)
    }

    private fun inferSharedHandlerContinuation(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): BasicBlockId? {
        val candidates = blocks.asSequence()
            .filter { block ->
                block != entry &&
                    block in facts.postDominators[entry].orEmpty() &&
                    facts.incoming[block].orEmpty().any { edge -> edge.from !in blocks }
            }
            .toList()
        return candidates.firstOrNull { candidate ->
            candidates.none { other ->
                other != candidate && candidate in facts.postDominators[other].orEmpty()
            }
        }
    }

    private fun inferLoopBackContinuation(
        header: BasicBlockId,
        catches: List<CollectedCatch>,
        facts: ControlFlowFacts,
    ): BasicBlockId? {
        var sawLoopBack = false
        for (collected in catches) {
            val blocks = collected.collection.blocks
            for (block in blocks) {
                if (block in facts.explicitTerminalBlocks) continue
                for (edge in facts.outgoing[block].orEmpty()) {
                    if (edge.to in blocks) continue
                    if (edge.to != header) return null
                    sawLoopBack = true
                }
            }
        }
        return header.takeIf { sawLoopBack }
    }

    private fun handlerExitsAreSupported(
        blocks: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        facts: ControlFlowFacts,
    ): Boolean = blocks.all { block ->
        if (block in facts.explicitTerminalBlocks) return@all true
        facts.outgoing[block].orEmpty().all { edge -> edge.to in blocks || edge.to == continuation }
    }

    private fun labelPosition(positions: Map<RawLabelId, Int>, label: RawLabelId): Int =
        requireNotNull(positions[label]) { "Unknown exception-table label ${label.value}." }

    private data class ProtectedRange(val start: Int, val endExclusive: Int)

    private data class ExceptionTableSegment(
        val range: ProtectedRange,
        val handlers: List<RawExceptionHandler>,
    )

    private data class ExceptionTableGroup(
        val segments: List<ExceptionTableSegment>,
    ) {
        val envelope: ProtectedRange = ProtectedRange(
            segments.first().range.start,
            segments.last().range.endExclusive,
        )
        val handlers: List<RawExceptionHandler> = segments.first().handlers
    }

    private data class ExceptionGroupTopology(
        val group: ExceptionTableGroup,
        val protectedBlocks: Set<BasicBlockId>,
        val handlerEntries: Set<BasicBlockId>,
    )

    private data class HandlerCollection(
        val blocks: Set<BasicBlockId>,
        val inferredContinuation: BasicBlockId?,
    )

    private data class CollectedCatch(
        val entry: BasicBlockId,
        val entries: List<RawExceptionHandler>,
        val collection: HandlerCollection,
    )

    private data class HandlerSignature(
        val handlerInstructionIndex: Int,
        val catchType: String?,
    )
}

internal data class ExceptionRegionKey(
    val protectedStartInstructionIndex: Int,
    val protectedEndInstructionIndexExclusive: Int,
)

internal data class ExceptionRecognition(
    val regions: List<StructuredRegion.TryCatch>,
    val rejections: Map<ExceptionRegionKey, UnstructuredControlFlowReason>,
    val regionCount: Int,
)
