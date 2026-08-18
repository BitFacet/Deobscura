package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.normalize.LegacySubroutineContext
import io.github.relvl.deobscura.normalize.LegacySubroutineProvenance
import io.github.relvl.deobscura.normalize.LegacySyntheticInstructionKind
import java.util.*

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
        legacySubroutineNormalized: Boolean,
        legacySubroutineProvenance: LegacySubroutineProvenance? = null,
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

        val regions = mutableListOf<StructuredRegion>()
        val rejections = linkedMapOf<ExceptionRegionKey, UnstructuredControlFlowReason>()
        val legacyRejectionDetails = linkedMapOf<ExceptionRegionKey, String>()
        val consumedGroups = mutableSetOf<ExceptionRegionKey>()

        groupTopologies.forEach { topology ->
            val group = topology.group
            val range = group.envelope
            val handlers = group.handlers
            val key = ExceptionRegionKey(range.start, range.endExclusive)
            if (key in consumedGroups) return@forEach
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
                val legacyTryCatchFinallyTrace = mutableListOf<String>()
                val legacyTryCatchFinallyRecognition = if (legacySubroutineNormalized) recognizeLegacySubroutineTryCatchFinally(
                    graph = graph,
                    topology = topology,
                    header = header,
                    allGroups = groupTopologies,
                    labelPositions = labelPositions,
                    facts = facts,
                    provenance = legacySubroutineProvenance,
                    rejectionTrace = legacyTryCatchFinallyTrace,
                ) else null
                if (legacyTryCatchFinallyRecognition != null) {
                    regions += legacyTryCatchFinallyRecognition.region
                    regions += legacyTryCatchFinallyRecognition.additionalRegions
                    consumedGroups += legacyTryCatchFinallyRecognition.consumedGroupKeys
                    return@forEach
                }
                val legacyFinallyTrace = mutableListOf<String>()
                val legacyFinallyRecognition = if (legacySubroutineNormalized) recognizeLegacySubroutineFinally(
                    graph = graph,
                    topology = topology,
                    header = header,
                    allGroups = groupTopologies,
                    labelPositions = labelPositions,
                    facts = facts,
                    provenance = legacySubroutineProvenance,
                    rejectionTrace = legacyFinallyTrace,
                ) else null
                if (legacyFinallyRecognition != null) {
                    regions += legacyFinallyRecognition.region
                    consumedGroups += legacyFinallyRecognition.consumedGroupKeys
                    return@forEach
                }
                val synchronizedRecognition = recognizeSynchronizedMonitor(
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
                val finallyRegion = recognizeCanonicalFinally(
                    graph = graph,
                    topology = topology,
                    header = header,
                    protectedBlocks = protectedBlocks,
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
                }
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

        return ExceptionRecognition(regions, rejections, groups.size, legacyRejectionDetails)
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

    /**
     * Recognizes javac's canonical monitor-cleanup lowering for a source-level `synchronized` block:
     *
     * `astore monitor; monitorenter; BODY; aload monitor; monitorexit; ...`
     * with a catch-all handler `astore ex; aload monitor; monitorexit; aload ex; athrow`.
     *
     * The protected range may include the handler's own `monitorexit`; javac does this so an
     * exception thrown by monitor exit is routed through the same cleanup path. This recognizer
     * therefore derives source body ownership from instruction positions instead of treating the
     * raw protected range as the complete source body.
     */
    private fun recognizeSynchronizedMonitor(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        allGroups: List<ExceptionGroupTopology>,
        facts: ControlFlowFacts,
    ): SynchronizedRecognition? {
        val group = topology.group
        if (group.handlers.size != 1 || group.handlers.single().catchType != null) return null
        if (groupedHandlers.size != 1) return null

        val instructions = graph.code.instructions
        val start = group.envelope.start
        if (start < 2) return null
        val monitorEnterInstructionIndex = start - 1
        val monitorEnter = instructions.getOrNull(monitorEnterInstructionIndex) as? RawMonitorInstruction ?: return null
        if (monitorEnter.opcode.mnemonic != "monitorenter") return null
        val monitorSlot = monitorSlotBeforeEnter(instructions, monitorEnterInstructionIndex) ?: return null

        val handlerEntry = groupedHandlers.keys.single() ?: return null
        val handlerStart = graph.block(handlerEntry).startInstructionIndex
        val exceptionStore = instructions.getOrNull(handlerStart) as? RawLocalInstruction ?: return null
        val handlerMonitorLoad = instructions.getOrNull(handlerStart + 1) as? RawLocalInstruction ?: return null
        val handlerMonitorExit = instructions.getOrNull(handlerStart + 2) as? RawMonitorInstruction ?: return null
        val exceptionReload = instructions.getOrNull(handlerStart + 3) as? RawLocalInstruction ?: return null
        if (instructions.getOrNull(handlerStart + 4) !is RawThrowInstruction) return null
        if (exceptionStore.operation != LocalOperation.STORE || exceptionStore.type != JvmComputationalType.REFERENCE) return null
        if (handlerMonitorLoad.operation != LocalOperation.LOAD || handlerMonitorLoad.slot != monitorSlot) return null
        if (handlerMonitorExit.opcode.mnemonic != "monitorexit") return null
        if (exceptionReload.operation != LocalOperation.LOAD || exceptionReload.slot != exceptionStore.slot) return null

        val normalExitIndices = mutableListOf<Int>()
        for (index in start until group.envelope.endExclusive) {
            if (index == handlerStart + 2) continue
            val exit = instructions[index] as? RawMonitorInstruction ?: continue
            if (exit.opcode.mnemonic != "monitorexit") return null
            val load = instructions.getOrNull(index - 1) as? RawLocalInstruction ?: return null
            if (load.operation != LocalOperation.LOAD || load.slot != monitorSlot) return null
            normalExitIndices += index
        }
        if (normalExitIndices.isEmpty()) return null

        val handlerInstructionIndices = handlerStart..(handlerStart + 4)
        val handlerBlocks = handlerInstructionIndices.mapNotNullTo(linkedSetOf()) { index ->
            facts.instructionToBlock.getOrNull(index)
        }
        if (handlerBlocks.isEmpty()) return null

        val sourceProtectedBlocks = topology.protectedBlocks - handlerBlocks
        if (sourceProtectedBlocks.isEmpty()) return null
        val bodyBlocks = collectSynchronizedBodyBlocks(
            header = header,
            protectedBlocks = sourceProtectedBlocks,
            normalMonitorExitInstructionIndices = normalExitIndices,
            facts = facts,
        )
        if (bodyBlocks.isEmpty()) return null
        if (hasExternalProtectedEntry(header, bodyBlocks, facts)) return null

        val monitorEnterBlock = facts.instructionToBlock.getOrNull(monitorEnterInstructionIndex) ?: return null
        val cleanupCompanions = allGroups.filter { candidate ->
            candidate !== topology &&
                    candidate.group.handlers.size == 1 &&
                    candidate.group.handlers.single().catchType == null &&
                    candidate.group.envelope.start == handlerStart &&
                    candidate.group.envelope.endExclusive == handlerStart + 3 &&
                    candidate.handlerEntries == setOf(handlerEntry)
        }
        if (cleanupCompanions.size > 1) return null
        val cleanupRanges = cleanupCompanions.flatMap { companion ->
            companion.group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            }
        }

        val region = StructuredRegion.Synchronized(
            header = monitorEnterBlock,
            bodyEntry = header,
            bodyBlocks = bodyBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = handlerBlocks,
            monitorSlot = monitorSlot,
            monitorEnterInstructionIndex = monitorEnterInstructionIndex,
            normalMonitorExitInstructionIndices = normalExitIndices,
            handlerMonitorExitInstructionIndex = handlerStart + 2,
            protectedStartInstructionIndex = group.envelope.start,
            protectedEndInstructionIndexExclusive = group.envelope.endExclusive,
            protectedRanges = group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            },
            syntheticCleanupProtectedRanges = cleanupRanges,
        )
        return SynchronizedRecognition(
            region = region,
            consumedGroupKeys = cleanupCompanions.mapTo(linkedSetOf()) { companion ->
                ExceptionRegionKey(companion.group.envelope.start, companion.group.envelope.endExclusive)
            },
        )
    }

    private fun monitorSlotBeforeEnter(
        instructions: List<RawInstruction>,
        monitorEnterInstructionIndex: Int,
    ): Int? {
        val immediate = instructions.getOrNull(monitorEnterInstructionIndex - 1) as? RawLocalInstruction
        if (immediate?.operation == LocalOperation.STORE && immediate.type == JvmComputationalType.REFERENCE) {
            return immediate.slot
        }
        if (immediate?.operation != LocalOperation.LOAD || immediate.type != JvmComputationalType.REFERENCE) return null
        val store = instructions.getOrNull(monitorEnterInstructionIndex - 2) as? RawLocalInstruction ?: return null
        return store.slot.takeIf {
            store.operation == LocalOperation.STORE &&
                    store.type == JvmComputationalType.REFERENCE &&
                    store.slot == immediate.slot
        }
    }

    private data class SynchronizedRecognition(
        val region: StructuredRegion.Synchronized,
        val consumedGroupKeys: Set<ExceptionRegionKey>,
    )

    private fun collectSynchronizedBodyBlocks(
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        normalMonitorExitInstructionIndices: List<Int>,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> {
        val normalExitBlocks = normalMonitorExitInstructionIndices.mapNotNullTo(linkedSetOf()) { index ->
            facts.instructionToBlock.getOrNull(index)
        }
        if (normalExitBlocks.isEmpty()) return emptySet()

        val result = linkedSetOf<BasicBlockId>()
        val pending = ArrayDeque<BasicBlockId>()
        pending += header
        while (pending.isNotEmpty()) {
            val block = pending.removeFirst()
            if (block !in protectedBlocks || !result.add(block)) continue

            // The block containing the normal monitorexit is still part of the synchronized
            // body, but control reached after that instruction is already outside the source
            // synchronized statement. Do not absorb successor blocks from that point onward.
            if (block in normalExitBlocks) continue

            facts.outgoing[block].orEmpty()
                .asSequence()
                .filter { edge -> edge.kind != ControlFlowEdgeKind.EXCEPTION && edge.to in protectedBlocks }
                .mapTo(pending) { edge -> edge.to }
        }
        return result
    }

    /**
     * Recognizes old javac `try/catch/finally` after JSR/RET normalization. The primary protected
     * range carries both typed catches and the catch-all finally handler, while additional ranges
     * protect the catch bodies themselves with the same finally handler.
     */
    private fun <T> rejectLegacy(
        rejectionTrace: MutableList<String>?,
        reason: String,
    ): T? {
        rejectionTrace?.add(reason)
        return null
    }


    private data class LegacyFinallyShape(
        val handlerEntry: BasicBlockId,
        val handlerBlocks: Set<BasicBlockId>,
        val bodyInstructionRanges: List<IntRange>,
        val normalCopies: List<FinallyBodyMatch>,
        val continuation: BasicBlockId,
    )

    /**
     * Proves the JSR/RET-normalized finally scaffolding shared by legacy `try/finally` and
     * `try/catch/finally` recognition. Source-level ownership of protected ranges and typed
     * catches is deliberately left to the caller; this helper only establishes the synthetic
     * exceptional copy, equivalent normal copies, and their common continuation.
     */
    private fun analyzeLegacyFinallyShape(
        graph: ControlFlowGraph,
        handlerInstructionIndex: Int,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>?,
    ): LegacyFinallyShape? {
        val handlerEntry = facts.instructionToBlock.getOrNull(handlerInstructionIndex)
            ?: return rejectLegacy(rejectionTrace, "handler-entry")
        val handlerInstructions = graph.instructions(graph.block(handlerEntry))
        if (handlerInstructions.size != 3) return rejectLegacy(rejectionTrace, "handler-size")

        val store = handlerInstructions[0] as? RawLocalInstruction
            ?: return rejectLegacy(rejectionTrace, "exception-store")
        if (store.operation != LocalOperation.STORE || store.type != JvmComputationalType.REFERENCE) {
            return rejectLegacy(rejectionTrace, "exception-store-shape")
        }
        val nullSeed = handlerInstructions[1] as? RawConstantInstruction
            ?: return rejectLegacy(rejectionTrace, "null-seed")
        if (nullSeed.opcode.mnemonic != "aconst_null") return rejectLegacy(rejectionTrace, "null-seed-opcode")
        val trampoline = handlerInstructions[2] as? RawBranchInstruction
            ?: return rejectLegacy(rejectionTrace, "handler-goto")
        if (trampoline.opcode.mnemonic !in setOf("goto", "goto_w")) {
            return rejectLegacy(rejectionTrace, "handler-goto-opcode")
        }

        val bodyEntry = facts.outgoing[handlerEntry].orEmpty()
            .filter { edge -> edge.kind != ControlFlowEdgeKind.EXCEPTION }
            .map { edge -> edge.to }
            .distinct()
            .singleOrNull() ?: return rejectLegacy(rejectionTrace, "handler-successor")
        val reachable = collectAcyclicNormalRegion(bodyEntry, facts)
            ?: return rejectLegacy(rejectionTrace, "cyclic-finally-body")
        val rethrowBlocks = reachable.filter { block -> isCanonicalRethrowBlock(graph, block, store.slot) }
        if (rethrowBlocks.size != 1) return rejectLegacy(rejectionTrace, "rethrow-count=${rethrowBlocks.size}")
        val rethrow = rethrowBlocks.single()

        val bodyBlocks = collectUntil(bodyEntry, rethrow, facts)
        if (bodyBlocks.isEmpty() || rethrow in bodyBlocks) return rejectLegacy(rejectionTrace, "empty-finally-body")
        if (bodyBlocks.any { block -> rethrow !in facts.postDominators[block].orEmpty() }) {
            return rejectLegacy(rejectionTrace, "rethrow-not-postdominator")
        }
        if (bodyBlocks.any { block -> graph.instructions(graph.block(block)).any { it is RawSwitchInstruction } }) {
            return rejectLegacy(rejectionTrace, "switch-in-finally-body")
        }
        if (bodyBlocks.any { block ->
                facts.outgoing[block].orEmpty().any { edge -> edge.to !in bodyBlocks && edge.to != rethrow }
            }) {
            return rejectLegacy(rejectionTrace, "finally-body-exit")
        }

        val handlerBlocks = linkedSetOf<BasicBlockId>().apply {
            add(handlerEntry)
            addAll(bodyBlocks)
            add(rethrow)
        }
        val candidateEntries = graph.blocks.asSequence()
            .map { it.id }
            .filter { it !in handlerBlocks }
            .filter { candidate -> hasLegacyFinallyCallSite(candidate, graph, facts, provenance) }
            .toList()
        val normalCopies = candidateEntries.mapNotNull { candidate ->
            matchFinallyBodyGraph(
                graph = graph,
                handlerEntry = bodyEntry,
                handlerBlocks = bodyBlocks,
                handlerExit = rethrow,
                handlerEntryInstructionOffset = 0,
                normalEntry = candidate,
                facts = facts,
                allowSplitNormalCopy = true,
            )
        }
        if (normalCopies.isEmpty()) {
            return rejectLegacy(rejectionTrace, "no-normal-copy-match candidates=${candidateEntries.size}")
        }
        if (provenance != null) {
            val handlerRanges = instructionRanges(bodyBlocks, graph)
            if (handlerRanges.size > 1 && !hasSingleLegacyContext(bodyBlocks, graph, provenance)) {
                return rejectLegacy(rejectionTrace, "split-exceptional-body-crosses-context")
            }
            if (normalCopies.any { match ->
                    match.instructionRanges.size > 1 && !hasSingleLegacyContext(match.blocks, graph, provenance)
                }) {
                return rejectLegacy(rejectionTrace, "split-normal-copy-crosses-context")
            }
        }
        val continuations = normalCopies.mapTo(linkedSetOf()) { match ->
            normalizeLegacyFinallyContinuation(match.continuation, graph, facts, provenance)
        }
        if (continuations.size != 1) return rejectLegacy(rejectionTrace, "continuation-count=${continuations.size}")

        return LegacyFinallyShape(
            handlerEntry = handlerEntry,
            handlerBlocks = handlerBlocks,
            bodyInstructionRanges = instructionRanges(bodyBlocks, graph),
            normalCopies = normalCopies,
            continuation = continuations.single(),
        )
    }

    private fun recognizeLegacySubroutineTryCatchFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        allGroups: List<ExceptionGroupTopology>,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>? = null,
    ): LegacyTryCatchFinallyRecognition? {
        val group = topology.group
        val catchAllHandlers = group.handlers.filter { it.catchType == null }
        val typedHandlers = group.handlers.filter { it.catchType != null }
        if (catchAllHandlers.size != 1 || typedHandlers.isEmpty()) return rejectLegacy(rejectionTrace, "handler-set")

        val catchAll = catchAllHandlers.single()
        val handlerInstructionIndex = labelPosition(labelPositions, catchAll.handler)
        val finallyShape = analyzeLegacyFinallyShape(
            graph = graph,
            handlerInstructionIndex = handlerInstructionIndex,
            facts = facts,
            provenance = provenance,
            rejectionTrace = rejectionTrace,
        ) ?: return null
        val handlerEntry = finallyShape.handlerEntry
        val finallyHandlerBlocks = finallyShape.handlerBlocks
        val matches = finallyShape.normalCopies
        val continuation = finallyShape.continuation

        val catchAllRelated = allGroups.filter { candidate ->
            candidate.group.handlers.any { handler ->
                handler.catchType == null && labelPosition(labelPositions, handler.handler) == handlerInstructionIndex
            }
        }
        // Legacy JSR/RET normalization may split one source try/catch/finally into several
        // physical ranges while preserving the exact same typed-catch + catch-all handler set.
        // Treat those peer ranges as one source try. If the typed handler signature changes,
        // however, we are looking at nested/overlapping exception structure and keep it
        // block-based for a more explicit recognizer.
        val mixedSignature = handlerSignature(group.handlers, labelPositions)
        val mixedRelated = catchAllRelated
            .filter { candidate -> candidate.group.handlers.any { it.catchType != null } }
            .sortedBy { candidate -> candidate.group.envelope.start }
        if (mixedRelated.isEmpty()) return rejectLegacy(rejectionTrace, "no-mixed-peers")
        if (mixedRelated.first() !== topology) return rejectLegacy(rejectionTrace, "not-first-mixed-peer")
        if (mixedRelated.any { candidate -> handlerSignature(candidate.group.handlers, labelPositions) != mixedSignature }) {
            return recognizeLegacyNestedTryCatchFinally(
                graph = graph,
                topology = topology,
                catchAllFamily = catchAllRelated,
                mixedRelated = mixedRelated,
                finallyShape = finallyShape,
                labelPositions = labelPositions,
                facts = facts,
                provenance = provenance,
                rejectionTrace = rejectionTrace,
            )
        }
        val catchAllFamily = catchAllRelated

        val sourceTryBlocks = mixedRelated.flatMapTo(linkedSetOf()) { candidate ->
            extendWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - finallyHandlerBlocks
        if (header !in sourceTryBlocks) return rejectLegacy(rejectionTrace, "header-not-in-source-try")
        if (hasExternalProtectedEntry(header, sourceTryBlocks, facts)) return rejectLegacy(rejectionTrace, "source-try-external-entry")

        val typedByEntry = typedHandlers.groupBy { handler ->
            facts.instructionToBlock.getOrNull(labelPosition(labelPositions, handler.handler))
        }
        if (typedByEntry.keys.any { it == null }) return rejectLegacy(rejectionTrace, "typed-handler-entry")
        val typedEntries = typedByEntry.keys.filterNotNull().toSet()
        val catches = mutableListOf<StructuredCatch>()
        val occupiedCatchBlocks = linkedSetOf<BasicBlockId>()
        for ((entryOrNull, entries) in typedByEntry.entries.sortedBy { it.key?.value ?: Int.MAX_VALUE }) {
            val entry = requireNotNull(entryOrNull)
            val blocks = collectLegacyCatchBody(
                entry = entry,
                protectedBlocks = sourceTryBlocks,
                otherHandlerEntries = typedEntries + handlerEntry,
                finallyCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks },
                graph = graph,
                facts = facts,
            ) ?: return rejectLegacy(rejectionTrace, "catch-body-shape")
            if (blocks.isEmpty()) return rejectLegacy(rejectionTrace, "empty-catch-body")
            if (blocks.any { it in occupiedCatchBlocks }) return rejectLegacy(rejectionTrace, "overlapping-catch-bodies")
            if (hasExternalHandlerEntry(entry, blocks, typedEntries, facts)) return rejectLegacy(rejectionTrace, "catch-external-entry")
            occupiedCatchBlocks += blocks
            catches += StructuredCatch(
                catchTypes = entries.mapNotNull { it.catchType }.distinct(),
                entry = entry,
                blocks = blocks,
            )
        }

        val handlerRanges = finallyShape.bodyInstructionRanges
        val protectedRanges = catchAllFamily
            .flatMap { candidate -> candidate.group.segments }
            .sortedWith(compareBy<ExceptionTableSegment> { it.range.start }.thenBy { it.range.endExclusive })
            .map { segment -> StructuredProtectedRange(segment.range.start, segment.range.endExclusive) }
            .distinct()
        val firstRange = protectedRanges.first()
        val lastRange = protectedRanges.last()
        val region = StructuredRegion.TryCatchFinally(
            header = header,
            tryBlocks = sourceTryBlocks,
            catches = catches,
            handlerEntry = handlerEntry,
            handlerBlocks = finallyHandlerBlocks,
            finallyBodyInstructionRanges = handlerRanges,
            normalCopyInstructionIndices = matches.flatMap { it.instructionRanges },
            normalCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks },
            continuation = continuation,
            protectedStartInstructionIndex = firstRange.startInstructionIndex,
            protectedEndInstructionIndexExclusive = lastRange.endInstructionIndexExclusive,
            protectedRanges = protectedRanges,
        )
        return LegacyTryCatchFinallyRecognition(
            region = region,
            consumedGroupKeys = catchAllFamily.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }


    private fun recognizeLegacyNestedTryCatchFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        catchAllFamily: List<ExceptionGroupTopology>,
        mixedRelated: List<ExceptionGroupTopology>,
        finallyShape: LegacyFinallyShape,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>?,
    ): LegacyTryCatchFinallyRecognition? {
        // A legacy JSR finally can surround several nested try/catch regions. In that shape every
        // fragment carries the same outer catch-all handler, while typed handlers vary according
        // to the nested scope that is active. Never flatten those handlers into sibling catches.
        if (mixedRelated.first() !== topology) return rejectLegacy(rejectionTrace, "not-first-mixed-peer")

        val handlerEntry = finallyShape.handlerEntry
        val finallyHandlerBlocks = finallyShape.handlerBlocks
        val handlerRanges = finallyShape.bodyInstructionRanges
        val matches = finallyShape.normalCopies
        val continuation = finallyShape.continuation
        val finallyCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks }
        val typedHandlerEntries = mixedRelated.flatMap { it.group.handlers }
            .filter { it.catchType != null }
            .mapNotNullTo(linkedSetOf()) { handler ->
                facts.instructionToBlock.getOrNull(labelPosition(labelPositions, handler.handler))
            }
        if (typedHandlerEntries.isEmpty()) return rejectLegacy(rejectionTrace, "mixed-handler-signature")

        val groupsByEntry = linkedMapOf<BasicBlockId, MutableList<ExceptionGroupTopology>>()
        val handlersByEntry = linkedMapOf<BasicBlockId, MutableList<RawExceptionHandler>>()
        for (candidate in mixedRelated) {
            for (handler in candidate.group.handlers) {
                if (handler.catchType == null) continue
                val entry = facts.instructionToBlock.getOrNull(labelPosition(labelPositions, handler.handler))
                    ?: return rejectLegacy(rejectionTrace, "typed-handler-entry")
                groupsByEntry.getOrPut(entry) { mutableListOf() } += candidate
                handlersByEntry.getOrPut(entry) { mutableListOf() } += handler
            }
        }

        val entriesByScope = groupsByEntry.entries.groupBy { (_, groups) ->
            groups.map { candidate -> ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive) }
                .distinct()
                .sortedWith(compareBy<ExceptionRegionKey> { it.protectedStartInstructionIndex }.thenBy { it.protectedEndInstructionIndexExclusive })
        }
        val scopeBlocks = linkedMapOf<List<ExceptionRegionKey>, Set<BasicBlockId>>()
        for ((scope, entries) in entriesByScope) {
            val groups = entries.flatMap { it.value }.distinct()
            scopeBlocks[scope] = groups.flatMapTo(linkedSetOf()) { candidate ->
                extendWithTerminalTransfers(candidate.protectedBlocks, facts)
            } - finallyHandlerBlocks - finallyCopyBlocks
        }

        val scopes = scopeBlocks.keys.toList()
        for (i in scopes.indices) {
            val left = scopeBlocks.getValue(scopes[i])
            if (left.isEmpty()) return rejectLegacy(rejectionTrace, "empty-nested-try")
            for (j in i + 1 until scopes.size) {
                val right = scopeBlocks.getValue(scopes[j])
                val overlap = left intersect right
                if (overlap.isNotEmpty() && !left.containsAll(right) && !right.containsAll(left)) {
                    return rejectLegacy(rejectionTrace, "crossing-mixed-handler-scopes")
                }
            }
        }

        val nestedRegions = mutableListOf<StructuredRegion>()
        for ((scope, entries) in entriesByScope.entries.sortedBy { it.key.first().protectedStartInstructionIndex }) {
            val protectedBlocks = scopeBlocks.getValue(scope)
            val scopeGroups = entries.flatMap { it.value }.distinct().sortedBy { it.group.envelope.start }
            val scopeStart = scopeGroups.first().group.envelope.start
            val scopeHeader = facts.instructionToBlock.getOrNull(scopeStart)
                ?: return rejectLegacy(rejectionTrace, "nested-try-header")
            if (scopeHeader !in protectedBlocks || hasExternalProtectedEntry(scopeHeader, protectedBlocks, facts)) {
                return rejectLegacy(rejectionTrace, "nested-try-external-entry")
            }

            val catches = mutableListOf<StructuredCatch>()
            val occupied = linkedSetOf<BasicBlockId>()
            for ((entry, _) in entries.sortedBy { it.key.value }) {
                val blocks = collectLegacyCatchBody(
                    entry = entry,
                    protectedBlocks = protectedBlocks,
                    otherHandlerEntries = typedHandlerEntries + handlerEntry,
                    finallyCopyBlocks = finallyCopyBlocks,
                    graph = graph,
                    facts = facts,
                ) ?: return rejectLegacy(rejectionTrace, "nested-catch-body-shape")
                if (blocks.isEmpty() || blocks.any { it in occupied }) return rejectLegacy(rejectionTrace, "nested-catch-overlap")
                if (hasExternalLegacyHandlerEntry(entry, blocks, typedHandlerEntries, facts, graph, provenance)) {
                    return rejectLegacy(rejectionTrace, "nested-catch-external-entry")
                }
                occupied += blocks
                catches += StructuredCatch(
                    catchTypes = handlersByEntry.getValue(entry).mapNotNull { it.catchType }.distinct(),
                    entry = entry,
                    blocks = blocks,
                )
            }

            val protectedRanges = scopeGroups.flatMap { it.group.segments }
                .map { StructuredProtectedRange(it.range.start, it.range.endExclusive) }
                .distinct()
                .sortedWith(compareBy<StructuredProtectedRange> { it.startInstructionIndex }.thenBy { it.endInstructionIndexExclusive })
            nestedRegions += StructuredRegion.TryCatch(
                header = scopeHeader,
                tryBlocks = protectedBlocks,
                catches = catches,
                continuation = selectContinuation(protectedBlocks, entries.mapTo(linkedSetOf()) { it.key }, facts),
                protectedStartInstructionIndex = protectedRanges.first().startInstructionIndex,
                protectedEndInstructionIndexExclusive = protectedRanges.last().endInstructionIndexExclusive,
                protectedRanges = protectedRanges,
            )
        }

        val outerTryBlocks = catchAllFamily.flatMapTo(linkedSetOf()) { candidate ->
            extendWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - finallyHandlerBlocks - finallyCopyBlocks
        val outerStart = catchAllFamily.minOf { it.group.envelope.start }
        val outerHeader = facts.instructionToBlock.getOrNull(outerStart)
            ?: return rejectLegacy(rejectionTrace, "outer-finally-header")
        if (outerHeader !in outerTryBlocks || hasExternalProtectedEntry(outerHeader, outerTryBlocks, facts)) {
            return rejectLegacy(rejectionTrace, "outer-finally-external-entry")
        }
        val outerRanges = catchAllFamily.flatMap { it.group.segments }
            .map { StructuredProtectedRange(it.range.start, it.range.endExclusive) }
            .distinct()
            .sortedWith(compareBy<StructuredProtectedRange> { it.startInstructionIndex }.thenBy { it.endInstructionIndexExclusive })
        val outerFinally = StructuredRegion.TryFinally(
            header = outerHeader,
            tryBlocks = outerTryBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = finallyHandlerBlocks,
            finallyBodyInstructionRanges = handlerRanges,
            normalCopyInstructionIndices = matches.flatMap { it.instructionRanges },
            normalCopyBlocks = finallyCopyBlocks,
            continuation = continuation,
            protectedStartInstructionIndex = outerRanges.first().startInstructionIndex,
            protectedEndInstructionIndexExclusive = outerRanges.last().endInstructionIndexExclusive,
            protectedRanges = outerRanges,
        )
        return LegacyTryCatchFinallyRecognition(
            region = outerFinally,
            additionalRegions = nestedRegions,
            consumedGroupKeys = catchAllFamily.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }

    private fun collectLegacyCatchBody(
        entry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        otherHandlerEntries: Set<BasicBlockId>,
        finallyCopyBlocks: Set<BasicBlockId>,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId>? {
        val result = linkedSetOf<BasicBlockId>()
        val pending = ArrayDeque<BasicBlockId>()
        pending += entry
        while (pending.isNotEmpty()) {
            val block = pending.removeFirst()
            if (block in protectedBlocks || block in finallyCopyBlocks || (block != entry && block in otherHandlerEntries)) continue
            if (!result.add(block)) continue
            if (block in facts.explicitTerminalBlocks) continue

            val outgoing = facts.outgoing[block].orEmpty()
            for (edge in outgoing) {
                if (edge.to in finallyCopyBlocks) continue
                if (edge.to in protectedBlocks || edge.to in otherHandlerEntries) return null
                pending += edge.to
            }
        }
        if (result.isEmpty()) return null
        // Do not let the catch walk escape into arbitrary post-try flow. A non-terminal boundary is
        // valid here only when it enters a proven cloned finally body.
        val hasUnsupportedBoundary = result.any { block ->
            if (block in facts.explicitTerminalBlocks) return@any false
            facts.outgoing[block].orEmpty().any { edge -> edge.to !in result && edge.to !in finallyCopyBlocks }
        }
        return result.takeUnless { hasUnsupportedBoundary }
    }

    private data class LegacyTryCatchFinallyRecognition(
        val region: StructuredRegion,
        val additionalRegions: List<StructuredRegion> = emptyList(),
        val consumedGroupKeys: Set<ExceptionRegionKey>,
    )

    /**
     * Recognizes the characteristic shape left by `LegacySubroutineNormalizer` for old javac
     * JSR/RET finally blocks. The normalizer replaces every JSR call with `aconst_null; goto`
     * and clones the subroutine body per return site, so one source finally can leave several
     * catch-all exception-table groups that all target the same exceptional trampoline.
     *
     * This slice is intentionally strict: the catch-all handler must be
     * `astore ex; aconst_null; goto FINALLY_COPY`, the exceptional copy must end in
     * `aload ex; athrow`, and at least one normal copy must be entered through the same synthetic
     * `aconst_null; goto` call-site marker and be graph-equivalent to the exceptional copy.
     */
    private fun recognizeLegacySubroutineFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        allGroups: List<ExceptionGroupTopology>,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>? = null,
    ): LegacyFinallyRecognition? {
        val group = topology.group
        if (group.handlers.size != 1 || group.handlers.single().catchType != null) return rejectLegacy(rejectionTrace, "handler-set")

        val signature = handlerSignature(group.handlers, labelPositions)
        val handlerInstructionIndex = signature.single().handlerInstructionIndex
        val finallyShape = analyzeLegacyFinallyShape(
            graph = graph,
            handlerInstructionIndex = handlerInstructionIndex,
            facts = facts,
            provenance = provenance,
            rejectionTrace = rejectionTrace,
        ) ?: return null
        val handlerEntry = finallyShape.handlerEntry
        val handlerBlocks = finallyShape.handlerBlocks
        val matches = finallyShape.normalCopies

        val family = allGroups.filter { candidate ->
            handlerSignature(candidate.group.handlers, labelPositions) == signature
        }
        if (family.isEmpty()) return rejectLegacy(rejectionTrace, "empty-handler-family")
        val protectedBlocks = family.flatMapTo(linkedSetOf()) { candidate ->
            extendWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - handlerBlocks
        if (header !in protectedBlocks) return rejectLegacy(rejectionTrace, "header-not-in-protected-blocks")

        val handlerRanges = finallyShape.bodyInstructionRanges
        val protectedRanges = family
            .flatMap { candidate -> candidate.group.segments }
            .sortedWith(compareBy<ExceptionTableSegment> { it.range.start }.thenBy { it.range.endExclusive })
            .map { segment -> StructuredProtectedRange(segment.range.start, segment.range.endExclusive) }
        val firstRange = protectedRanges.first()
        val lastRange = protectedRanges.last()
        val region = StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = handlerBlocks,
            finallyBodyInstructionRanges = handlerRanges,
            normalCopyInstructionIndices = matches.flatMap { it.instructionRanges },
            normalCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks },
            continuation = finallyShape.continuation,
            protectedStartInstructionIndex = firstRange.startInstructionIndex,
            protectedEndInstructionIndexExclusive = lastRange.endInstructionIndexExclusive,
            protectedRanges = protectedRanges,
        )
        return LegacyFinallyRecognition(
            region = region,
            consumedGroupKeys = family.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }

    /**
     * Legacy JSR/RET normalization can leave one pure `goto` block after a cloned finally body.
     * The block is not always an instruction synthesized by the normalizer itself: cloning can
     * preserve an original compiler-generated trampoline whose source-level role is still only to
     * return from the shared finally subroutine. Therefore instruction provenance alone is not a
     * complete proof for this equivalence yet.
     *
     * Keep the established legacy-only shape rule here and use provenance elsewhere for facts it
     * models exactly (JSR call sites, clone contexts and synthetic transfers). Once normalization
     * provenance records return-path equivalence explicitly, this fallback can be tightened again.
     */
    private fun normalizeLegacyFinallyContinuation(
        continuation: BasicBlockId,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
    ): BasicBlockId {
        var current = continuation
        val visited = linkedSetOf<BasicBlockId>()
        while (visited.add(current)) {
            val instructions = graph.instructions(graph.block(current))
            if (instructions.size != 1) break
            val jump = instructions.single() as? RawBranchInstruction ?: break
            if (jump.opcode.mnemonic !in setOf("goto", "goto_w")) break

            val outgoing = facts.outgoing[current].orEmpty()
            if (outgoing.any { edge -> edge.kind == ControlFlowEdgeKind.EXCEPTION }) break
            val next = outgoing.singleOrNull()?.to ?: break
            current = next
        }
        return current
    }

    private fun hasLegacyFinallyCallSite(
        bodyEntry: BasicBlockId,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
    ): Boolean = facts.incoming[bodyEntry].orEmpty().any { edge ->
        if (edge.kind == ControlFlowEdgeKind.EXCEPTION) return@any false
        val predecessor = graph.block(edge.from)
        if (provenance != null) {
            val lastOrigin = provenance.originAt(predecessor.endInstructionIndexExclusive - 1)
            return@any lastOrigin?.syntheticKind == LegacySyntheticInstructionKind.JSR_GOTO
        }

        // Synthetic unit tests can construct already-normalized legacy shapes directly.
        val instructions = graph.instructions(predecessor)
        if (instructions.size < 2) return@any false
        val nullSeed = instructions[instructions.lastIndex - 1] as? RawConstantInstruction ?: return@any false
        val jump = instructions.last() as? RawBranchInstruction ?: return@any false
        nullSeed.opcode.mnemonic == "aconst_null" && jump.opcode.mnemonic in setOf("goto", "goto_w")
    }

    private fun hasSingleLegacyContext(
        blocks: Set<BasicBlockId>,
        graph: ControlFlowGraph,
        provenance: LegacySubroutineProvenance,
    ): Boolean {
        val contexts = blocks.mapNotNullTo(linkedSetOf()) { block ->
            provenance.originAt(graph.block(block).startInstructionIndex)?.context
        }
        return contexts.size == 1 && contexts.single().frames.isNotEmpty()
    }

    private fun hasExternalLegacyHandlerEntry(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        graph: ControlFlowGraph,
        provenance: LegacySubroutineProvenance?,
    ): Boolean = blocks.any { block ->
        facts.incoming[block].orEmpty().any { edge ->
            if (edge.from in blocks || block == entry && edge.from in handlerEntries) return@any false
            if (provenance == null) return@any true
            !isNormalizerOwnedTransfer(edge.from, block, graph, provenance)
        }
    }

    private fun isNormalizerOwnedTransfer(
        from: BasicBlockId,
        to: BasicBlockId,
        graph: ControlFlowGraph,
        provenance: LegacySubroutineProvenance,
    ): Boolean {
        val fromBlock = graph.block(from)
        val toBlock = graph.block(to)
        val transfer = provenance.originAt(fromBlock.endInstructionIndexExclusive - 1) ?: return false
        val target = provenance.originAt(toBlock.startInstructionIndex) ?: return false
        if (transfer.syntheticKind == null) return false
        return contextsRelated(transfer.context, target.context)
    }

    private fun contextsRelated(left: LegacySubroutineContext, right: LegacySubroutineContext): Boolean =
        left == right || left.parent == right || right.parent == left

    private fun instructionRanges(
        blocks: Set<BasicBlockId>,
        graph: ControlFlowGraph,
    ): List<IntRange> {
        val physical = blocks.map(graph::block).sortedBy { it.startInstructionIndex }
        if (physical.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var rangeStart = physical.first().startInstructionIndex
        var rangeEndExclusive = physical.first().endInstructionIndexExclusive
        for (block in physical.drop(1)) {
            if (block.startInstructionIndex == rangeEndExclusive) {
                rangeEndExclusive = block.endInstructionIndexExclusive
            } else {
                ranges += rangeStart until rangeEndExclusive
                rangeStart = block.startInstructionIndex
                rangeEndExclusive = block.endInstructionIndexExclusive
            }
        }
        ranges += rangeStart until rangeEndExclusive
        return ranges
    }

    private fun contiguousInstructionRange(
        blocks: Set<BasicBlockId>,
        graph: ControlFlowGraph,
    ): IntRange? = instructionRanges(blocks, graph).singleOrNull()

    private data class LegacyFinallyRecognition(
        val region: StructuredRegion.TryFinally,
        val consumedGroupKeys: Set<ExceptionRegionKey>,
    )

    private fun recognizeCanonicalFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        facts: ControlFlowFacts,
    ): StructuredRegion.TryFinally? = recognizeLinearCanonicalFinally(
        graph,
        topology,
        header,
        protectedBlocks,
        groupedHandlers,
        facts,
    ) ?: recognizeBranchingCanonicalFinally(
        graph,
        topology,
        header,
        protectedBlocks,
        groupedHandlers,
        facts,
    )

    /**
     * First safe finally slice: a single linear catch-all handler
     * `astore ex; BODY; aload ex; athrow` whose BODY occurs verbatim at the start of a normal exit.
     */
    private fun recognizeLinearCanonicalFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        facts: ControlFlowFacts,
    ): StructuredRegion.TryFinally? {
        val group = topology.group
        if (group.handlers.size != 1 || group.handlers.single().catchType != null) return null
        if (groupedHandlers.size != 1) return null
        if (hasExternalProtectedEntry(header, protectedBlocks, facts)) return null

        val handlerEntry = groupedHandlers.keys.single() ?: return null
        val handlerBlock = graph.block(handlerEntry)
        val handlerInstructions = graph.instructions(handlerBlock)
        if (handlerInstructions.size < 4) return null

        val store = handlerInstructions.first() as? RawLocalInstruction ?: return null
        if (store.operation != LocalOperation.STORE || store.type != JvmComputationalType.REFERENCE) return null
        val reload = handlerInstructions[handlerInstructions.lastIndex - 1] as? RawLocalInstruction ?: return null
        if (reload.operation != LocalOperation.LOAD || reload.slot != store.slot) return null
        if (handlerInstructions.last() !is RawThrowInstruction) return null

        val body = handlerInstructions.subList(1, handlerInstructions.size - 2)
        if (body.isEmpty() || body.any(::isControlTransfer)) return null

        val boundaryTargets = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts)
        if (boundaryTargets.isEmpty()) return null
        val matches = boundaryTargets.mapNotNull { target ->
            val block = graph.block(target)
            val instructions = graph.instructions(block)
            if (instructions.size < body.size || instructions.subList(0, body.size) != body) return@mapNotNull null
            target to (block.startInstructionIndex..(block.startInstructionIndex + body.size - 1))
        }
        if (matches.isEmpty()) return null

        val continuationCandidates = matches.mapNotNullTo(linkedSetOf()) { (target, _) ->
            facts.outgoing[target].orEmpty().map { it.to }.distinct().singleOrNull()
        }
        if (continuationCandidates.size > 1) return null

        val handlerBodyStart = handlerBlock.startInstructionIndex + 1
        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = setOf(handlerEntry),
            finallyBodyInstructionRanges = listOf(handlerBodyStart..(handlerBodyStart + body.size - 1)),
            normalCopyInstructionIndices = matches.map { it.second },
            normalCopyBlocks = matches.mapTo(linkedSetOf()) { it.first },
            continuation = continuationCandidates.singleOrNull(),
            protectedStartInstructionIndex = group.envelope.start,
            protectedEndInstructionIndexExclusive = group.envelope.endExclusive,
            protectedRanges = group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            },
        )
    }

    /**
     * Second safe finally slice: a canonical catch-all whose body is a small acyclic CFG ending
     * at `aload ex; athrow`, with an isomorphic copy on a normal exit. Branch targets may differ,
     * but instruction semantics and edge kinds must match exactly.
     */
    private fun recognizeBranchingCanonicalFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        facts: ControlFlowFacts,
    ): StructuredRegion.TryFinally? {
        val group = topology.group
        if (group.handlers.size != 1 || group.handlers.single().catchType != null) return null
        if (groupedHandlers.size != 1) return null
        if (hasExternalProtectedEntry(header, protectedBlocks, facts)) return null

        val handlerEntry = groupedHandlers.keys.single() ?: return null
        val entryBlock = graph.block(handlerEntry)
        val entryInstructions = graph.instructions(entryBlock)
        if (entryInstructions.isEmpty()) return null
        val store = entryInstructions.first() as? RawLocalInstruction ?: return null
        if (store.operation != LocalOperation.STORE || store.type != JvmComputationalType.REFERENCE) return null

        val handlerEntryInstructionOffset: Int
        val bodyEntry: BasicBlockId
        if (entryInstructions.size == 1) {
            handlerEntryInstructionOffset = 0
            bodyEntry = facts.outgoing[handlerEntry].orEmpty().map { it.to }.distinct().singleOrNull() ?: return null
        } else {
            handlerEntryInstructionOffset = 1
            bodyEntry = handlerEntry
        }
        val reachable = collectAcyclicNormalRegion(bodyEntry, facts) ?: return null
        val rethrowBlocks = reachable.filter { block -> isCanonicalRethrowBlock(graph, block, store.slot) }
        if (rethrowBlocks.size != 1) return null
        val rethrow = rethrowBlocks.single()

        val bodyBlocks = collectUntil(bodyEntry, rethrow, facts) ?: return null
        if (bodyBlocks.isEmpty() || rethrow in bodyBlocks) return null
        if (bodyBlocks.any { block -> rethrow !in facts.postDominators[block].orEmpty() }) return null
        if (bodyBlocks.any { block -> graph.instructions(graph.block(block)).any { it is RawSwitchInstruction } }) return null
        if (bodyBlocks.any { block -> facts.outgoing[block].orEmpty().any { edge -> edge.to !in bodyBlocks && edge.to != rethrow } }) return null

        val boundaryTargets = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts)
        if (boundaryTargets.isEmpty()) return null

        val matches = boundaryTargets.mapNotNull { target ->
            matchFinallyBodyGraph(
                graph = graph,
                handlerEntry = bodyEntry,
                handlerBlocks = bodyBlocks,
                handlerExit = rethrow,
                handlerEntryInstructionOffset = handlerEntryInstructionOffset,
                normalEntry = target,
                facts = facts,
            )
        }
        if (matches.isEmpty()) return null
        val continuations = matches.mapTo(linkedSetOf()) { it.continuation }
        if (continuations.size != 1) return null

        val handlerRange = bodyBlocks.asSequence()
            .map(graph::block)
            .sortedBy { it.startInstructionIndex }
            .let { blocks ->
                val list = blocks.toList()
                val first = list.first().startInstructionIndex + handlerEntryInstructionOffset
                val last = list.last().endInstructionIndexExclusive - 1
                val instructionCount = list.sumOf { it.endInstructionIndexExclusive - it.startInstructionIndex } -
                        handlerEntryInstructionOffset
                if ((first..last).count() != instructionCount) return null
                first..last
            }

        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = linkedSetOf<BasicBlockId>().apply {
                add(handlerEntry)
                addAll(bodyBlocks)
                add(rethrow)
            },
            finallyBodyInstructionRanges = listOf(handlerRange),
            normalCopyInstructionIndices = matches.flatMap { it.instructionRanges },
            normalCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks },
            continuation = continuations.single(),
            protectedStartInstructionIndex = group.envelope.start,
            protectedEndInstructionIndexExclusive = group.envelope.endExclusive,
            protectedRanges = group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            },
        )
    }

    private fun collectAcyclicNormalRegion(
        entry: BasicBlockId,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId>? {
        val result = linkedSetOf<BasicBlockId>()
        val visiting = mutableSetOf<BasicBlockId>()

        fun visit(block: BasicBlockId): Boolean {
            if (block in result) return true
            if (!visiting.add(block)) return false
            for (edge in facts.outgoing[block].orEmpty()) {
                if (!visit(edge.to)) return false
            }
            visiting.remove(block)
            result += block
            return true
        }

        return result.takeIf { visit(entry) }
    }

    private fun collectUntil(
        entry: BasicBlockId,
        stop: BasicBlockId,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue += entry
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == stop || !result.add(block)) continue
            for (edge in facts.outgoing[block].orEmpty()) {
                if (edge.to != stop) queue += edge.to
            }
        }
        return result
    }

    private fun isCanonicalRethrowBlock(
        graph: ControlFlowGraph,
        block: BasicBlockId,
        exceptionSlot: Int,
    ): Boolean {
        val instructions = graph.instructions(graph.block(block))
        if (instructions.size != 2) return false
        val reload = instructions[0] as? RawLocalInstruction ?: return false
        return reload.operation == LocalOperation.LOAD &&
                reload.type == JvmComputationalType.REFERENCE &&
                reload.slot == exceptionSlot &&
                instructions[1] is RawThrowInstruction
    }

    private data class FinallyBodyMatch(
        val blocks: Set<BasicBlockId>,
        val continuation: BasicBlockId,
        val instructionRanges: List<IntRange>,
    )

    private fun matchFinallyBodyGraph(
        graph: ControlFlowGraph,
        handlerEntry: BasicBlockId,
        handlerBlocks: Set<BasicBlockId>,
        handlerExit: BasicBlockId,
        handlerEntryInstructionOffset: Int,
        normalEntry: BasicBlockId,
        facts: ControlFlowFacts,
        allowSplitNormalCopy: Boolean = false,
    ): FinallyBodyMatch? {
        val mapping = linkedMapOf<BasicBlockId, BasicBlockId>()
        var continuation: BasicBlockId? = null

        fun match(handlerBlock: BasicBlockId, normalBlock: BasicBlockId): Boolean {
            mapping[handlerBlock]?.let { return it == normalBlock }
            if (normalBlock in mapping.values) return false
            mapping[handlerBlock] = normalBlock

            val left = graph.instructions(graph.block(handlerBlock)).let { instructions ->
                if (handlerBlock == handlerEntry && handlerEntryInstructionOffset != 0) {
                    instructions.drop(handlerEntryInstructionOffset)
                } else {
                    instructions
                }
            }
            val right = graph.instructions(graph.block(normalBlock))
            val rightBody = if (
                right.size == left.size + 1 &&
                right.last() is RawBranchInstruction &&
                (right.last() as RawBranchInstruction).opcode.mnemonic == "goto"
            ) {
                right.dropLast(1)
            } else {
                right
            }
            if (left.size != rightBody.size || left.indices.any { !equivalentFinallyInstruction(left[it], rightBody[it]) }) {
                return false
            }

            val leftEdges = facts.outgoing[handlerBlock].orEmpty()
            val rightEdges = facts.outgoing[normalBlock].orEmpty()
            if (leftEdges.size != rightEdges.size) return false

            for (leftEdge in leftEdges) {
                val sameKind = rightEdges.filter { it.kind == leftEdge.kind }
                val candidates = if (leftEdge.to == handlerExit && sameKind.isEmpty() && rightEdges.size == 1) {
                    rightEdges
                } else {
                    sameKind
                }
                if (candidates.size != 1) return false
                val rightTarget = candidates.single().to
                if (leftEdge.to == handlerExit) {
                    if (continuation != null && continuation != rightTarget) return false
                    continuation = rightTarget
                } else {
                    if (leftEdge.to !in handlerBlocks || !match(leftEdge.to, rightTarget)) return false
                }
            }
            return true
        }

        if (!match(handlerEntry, normalEntry)) return null
        val resolvedContinuation = continuation ?: return null
        val normalBlocks = mapping.values.toSet()
        if (resolvedContinuation in normalBlocks) return null
        if (normalBlocks.any { block -> facts.incoming[block].orEmpty().any { it.from !in normalBlocks && block != normalEntry } }) return null

        val physical = normalBlocks.map(graph::block).sortedBy { it.startInstructionIndex }
        val ranges = mutableListOf<IntRange>()
        var rangeStart = physical.first().startInstructionIndex
        var rangeEndExclusive = physical.first().endInstructionIndexExclusive
        for (block in physical.drop(1)) {
            if (block.startInstructionIndex == rangeEndExclusive) {
                rangeEndExclusive = block.endInstructionIndexExclusive
            } else {
                ranges += rangeStart until rangeEndExclusive
                rangeStart = block.startInstructionIndex
                rangeEndExclusive = block.endInstructionIndexExclusive
            }
        }
        ranges += rangeStart until rangeEndExclusive
        if (!allowSplitNormalCopy && ranges.size != 1) return null
        return FinallyBodyMatch(normalBlocks, resolvedContinuation, ranges)
    }

    private fun equivalentFinallyInstruction(
        left: RawInstruction,
        right: RawInstruction,
    ): Boolean = when {
        left is RawBranchInstruction && right is RawBranchInstruction -> left.opcode == right.opcode
        else -> left == right
    }

    private fun isControlTransfer(instruction: RawInstruction): Boolean =
        instruction is RawBranchInstruction ||
                instruction is RawSwitchInstruction ||
                instruction is RawReturnInstruction ||
                instruction is RawThrowInstruction

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
    val regions: List<StructuredRegion>,
    val rejections: Map<ExceptionRegionKey, UnstructuredControlFlowReason>,
    val regionCount: Int,
    val legacyRejectionDetails: Map<ExceptionRegionKey, String> = emptyMap(),
)
