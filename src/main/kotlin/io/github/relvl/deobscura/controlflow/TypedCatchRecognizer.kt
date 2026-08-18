package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.RawExceptionHandler
import java.util.*

/** Result of ordinary typed-catch reconstruction for one exception-table group. */
internal data class TypedCatchRecognition(
    val region: StructuredRegion.TryCatch? = null,
    val failure: UnstructuredControlFlowReason? = null,
)

/** Fully proved source-level typed catch scope, reusable inside legacy finally families. */
internal data class TypedCatchScopeProof(
    val header: BasicBlockId,
    val protectedBlocks: Set<BasicBlockId>,
    val catches: List<StructuredCatch>,
    val continuation: BasicBlockId?,
    val protectedRanges: List<StructuredProtectedRange>,
) {
    fun toRegion(): StructuredRegion.TryCatch = StructuredRegion.TryCatch(
        header = header,
        tryBlocks = protectedBlocks,
        catches = catches,
        continuation = continuation,
        protectedStartInstructionIndex = protectedRanges.first().startInstructionIndex,
        protectedEndInstructionIndexExclusive = protectedRanges.last().endInstructionIndexExclusive,
        protectedRanges = protectedRanges,
    )
}

/** Structural reasons a typed scope can fail before it is safe to emit as source syntax. */
internal enum class TypedCatchScopeFailure {
    PROTECTED_EXTERNAL_ENTRY,
    NO_COMMON_CONTINUATION,
    CATCH_BODY_SHAPE,
    EMPTY_HANDLER_REGION,
    OVERLAPPING_HANDLER_REGIONS,
    HANDLER_EXTERNAL_ENTRY,
    UNSUPPORTED_HANDLER_EXIT,
    ;

    fun toUnstructuredReason(): UnstructuredControlFlowReason = when (this) {
        PROTECTED_EXTERNAL_ENTRY -> UnstructuredControlFlowReason.EXCEPTION_PROTECTED_REGION_HAS_EXTERNAL_ENTRY
        NO_COMMON_CONTINUATION -> UnstructuredControlFlowReason.EXCEPTION_NO_COMMON_CONTINUATION
        CATCH_BODY_SHAPE -> UnstructuredControlFlowReason.EXCEPTION_UNSUPPORTED_HANDLER_EXIT
        EMPTY_HANDLER_REGION -> UnstructuredControlFlowReason.EXCEPTION_EMPTY_HANDLER_REGION
        OVERLAPPING_HANDLER_REGIONS -> UnstructuredControlFlowReason.EXCEPTION_OVERLAPPING_HANDLER_REGIONS
        HANDLER_EXTERNAL_ENTRY -> UnstructuredControlFlowReason.EXCEPTION_HANDLER_HAS_EXTERNAL_ENTRY
        UNSUPPORTED_HANDLER_EXIT -> UnstructuredControlFlowReason.EXCEPTION_UNSUPPORTED_HANDLER_EXIT
    }
}

internal data class TypedCatchScopeAnalysis(
    val proof: TypedCatchScopeProof? = null,
    val failure: TypedCatchScopeFailure? = null,
)

/** Catch-body ownership plus a continuation inferred while walking that body. */
internal data class TypedCatchHandlerCollection(
    val blocks: Set<BasicBlockId>,
    val inferredContinuation: BasicBlockId?,
)

private data class CollectedCatch(
    val entry: BasicBlockId,
    val entries: List<RawExceptionHandler>,
    val collection: TypedCatchHandlerCollection,
)

/**
 * Proves typed `try/catch` ownership independently of any particular exception-table layout.
 * Legacy finally reconstruction reuses the same scope proof with stricter collection policies.
 */
internal class TypedCatchRecognizer {
    /** Reconstructs the ordinary one-group typed-catch form used by modern bytecode. */
    fun recognizeOrdinary(
        topology: ExceptionGroupTopology,
        labelPositions: Map<io.github.relvl.deobscura.raw.RawLabelId, Int>,
        facts: ControlFlowFacts,
        allGroups: List<ExceptionGroupTopology>,
    ): TypedCatchRecognition {
        val protectedBlocks = extendExceptionProtectedScopeWithTerminalTransfers(topology.protectedBlocks, facts)
        val typedTopology = buildTypedCatchScopeTopologies(
            groups = listOf(topology),
            labelPositions = labelPositions,
            facts = facts,
        )
        if (typedTopology.failure != null || typedTopology.topology?.scopes?.size != 1) {
            val failure = when (typedTopology.failure) {
                TypedCatchTopologyFailure.INVALID_HANDLER_ENTRY ->
                    UnstructuredControlFlowReason.EXCEPTION_INVALID_HANDLER_ENTRY

                TypedCatchTopologyFailure.EMPTY_PROTECTED_SCOPE ->
                    UnstructuredControlFlowReason.EXCEPTION_EMPTY_PROTECTED_REGION

                TypedCatchTopologyFailure.INVALID_PROTECTED_HEADER ->
                    UnstructuredControlFlowReason.EXCEPTION_INVALID_PROTECTED_ENTRY

                TypedCatchTopologyFailure.CROSSING_SCOPES, null ->
                    UnstructuredControlFlowReason.EXCEPTION_OVERLAPPING_HANDLER_REGIONS
            }
            return TypedCatchRecognition(failure = failure)
        }

        val scope = analyze(
            scope = requireNotNull(typedTopology.topology).scopes.single(),
            facts = facts,
            rejectNormalBoundaryWithoutContinuation = true,
            allowLoopBackContinuation = true,
            collectCatch = { entry, entries, continuation ->
                collectHandlerBlocks(
                    entry = entry,
                    protectedBlocks = protectedBlocks,
                    handlerEntries = entries,
                    continuation = continuation,
                    facts = facts,
                    currentGroup = topology,
                    allGroups = allGroups,
                )
            },
            hasExternalCatchEntry = { entry, blocks, entries ->
                hasExternalHandlerEntry(entry, blocks, entries, facts)
            },
            supportsCatchExit = { blocks, continuation ->
                handlerExitsAreSupported(blocks, continuation, facts)
            },
        )
        return if (scope.failure != null) {
            TypedCatchRecognition(failure = scope.failure.toUnstructuredReason())
        } else {
            TypedCatchRecognition(region = requireNotNull(scope.proof).toRegion())
        }
    }

    fun hasExternalHandlerEntry(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): Boolean = hasExternalEntry(blocks, facts) { target, source ->
        isHandlerPeerEntryTransfer(entry, target, source, handlerEntries)
    }

    /** Runs the shared typed-scope proof using caller-supplied catch ownership policy. */
    fun analyze(
        scope: TypedCatchScopeTopology,
        facts: ControlFlowFacts,
        rejectNormalBoundaryWithoutContinuation: Boolean,
        allowLoopBackContinuation: Boolean,
        collectCatch: (BasicBlockId, Set<BasicBlockId>, BasicBlockId?) -> TypedCatchHandlerCollection?,
        hasExternalCatchEntry: (BasicBlockId, Set<BasicBlockId>, Set<BasicBlockId>) -> Boolean,
        supportsCatchExit: (Set<BasicBlockId>, BasicBlockId?) -> Boolean,
        requiredContinuation: BasicBlockId? = null,
    ): TypedCatchScopeAnalysis {
        val header = scope.header
        val protectedBlocks = scope.protectedBlocks
        val handlersByEntry = scope.handlersByEntry
        val protectedRanges = scope.protectedRanges
        if (header !in protectedBlocks || hasExternalProtectedEntry(header, protectedBlocks, facts)) {
            return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.PROTECTED_EXTERNAL_ENTRY)
        }

        val handlerEntries = handlersByEntry.keys
        val selectedContinuation = requiredContinuation ?: selectContinuation(protectedBlocks, handlerEntries, facts)
        if (selectedContinuation == null && rejectNormalBoundaryWithoutContinuation &&
            normalBoundaryTargets(protectedBlocks, handlerEntries, facts).isNotEmpty()
        ) {
            return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.NO_COMMON_CONTINUATION)
        }

        val collectedCatches = mutableListOf<CollectedCatch>()
        for ((entry, entries) in handlersByEntry.entries.sortedBy { it.key.value }) {
            val collected = collectCatch(entry, handlerEntries, selectedContinuation)
                ?: return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.CATCH_BODY_SHAPE)
            if (collected.blocks.isEmpty()) {
                return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.EMPTY_HANDLER_REGION)
            }
            collectedCatches += CollectedCatch(entry, entries, collected)
        }

        val inferredContinuations = collectedCatches.mapNotNullTo(linkedSetOf()) { it.collection.inferredContinuation }
        val regionContinuation = when {
            selectedContinuation != null && inferredContinuations.all { it == selectedContinuation } -> selectedContinuation
            selectedContinuation != null -> {
                return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.UNSUPPORTED_HANDLER_EXIT)
            }

            inferredContinuations.size == 1 -> inferredContinuations.single()
            inferredContinuations.isEmpty() && allowLoopBackContinuation -> inferLoopBackContinuation(
                header = header,
                catches = collectedCatches,
                facts = facts,
            )

            inferredContinuations.isEmpty() -> null
            else -> return TypedCatchScopeAnalysis(failure = TypedCatchScopeFailure.NO_COMMON_CONTINUATION)
        }

        val validatedCatches = validateCollectedCatches(
            collectedCatches = collectedCatches,
            handlerEntries = handlerEntries,
            continuation = regionContinuation,
            hasExternalCatchEntry = hasExternalCatchEntry,
            supportsCatchExit = supportsCatchExit,
        )
        if (validatedCatches.failure != null) {
            return TypedCatchScopeAnalysis(failure = validatedCatches.failure)
        }

        return TypedCatchScopeAnalysis(
            proof = TypedCatchScopeProof(
                header = header,
                protectedBlocks = protectedBlocks,
                catches = requireNotNull(validatedCatches.catches),
                continuation = regionContinuation,
                protectedRanges = protectedRanges,
            ),
        )
    }

    /** Walks one catch body while honoring protected, sibling, continuation, and stop boundaries. */
    fun collectCatchBodyRegion(
        entry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        stopBlocks: Set<BasicBlockId>,
        rejectTargets: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        nestedGroups: List<ExceptionGroupTopology> = emptyList(),
        currentGroup: ExceptionGroupTopology? = null,
    ): TypedCatchHandlerCollection? {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()

        fun drainQueue(): Boolean {
            while (queue.isNotEmpty()) {
                val block = queue.removeFirst()
                if (block == continuation || block in protectedBlocks || block in stopBlocks ||
                    (block != entry && block in handlerEntries)
                ) {
                    continue
                }
                if (!result.add(block)) continue
                if (block in facts.explicitTerminalBlocks) continue
                for (edge in facts.outgoing[block].orEmpty()) {
                    if (edge.to in stopBlocks) continue
                    if (edge.to in rejectTargets) return false
                    queue.addLast(edge.to)
                }
            }
            return true
        }

        queue.add(entry)
        if (!drainQueue()) return null

        if (nestedGroups.isNotEmpty()) {
            val closed = closeOverContainedExceptionRegions(
                owned = result,
                groups = nestedGroups,
                excludeGroup = { nested -> nested === currentGroup },
                revisitContainedGroups = false,
                handlerEntriesFor = { nested ->
                    nested.handlerEntries.filterTo(linkedSetOf()) { it !in handlerEntries }
                },
                absorb = { _, nestedEntries, _ ->
                    val before = result.size
                    nestedEntries.forEach(queue::addLast)
                    if (!drainQueue()) {
                        ExceptionOwnershipExpansion.REJECTED
                    } else if (result.size != before) {
                        ExceptionOwnershipExpansion.CHANGED
                    } else {
                        ExceptionOwnershipExpansion.UNCHANGED
                    }
                },
            )
            if (!closed) return null
        }

        if (continuation != null) return TypedCatchHandlerCollection(result, null)

        val inferredContinuation = inferSharedHandlerContinuation(entry, result, facts)
            ?: return TypedCatchHandlerCollection(result, null)
        val trimmed = result.filterTo(linkedSetOf()) { block ->
            inferredContinuation !in facts.dominators[block].orEmpty()
        }
        return TypedCatchHandlerCollection(trimmed, inferredContinuation)
    }

    private data class StructuredCatchValidation(
        val catches: List<StructuredCatch>? = null,
        val failure: TypedCatchScopeFailure? = null,
    )

    private fun validateCollectedCatches(
        collectedCatches: List<CollectedCatch>,
        handlerEntries: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        hasExternalCatchEntry: (BasicBlockId, Set<BasicBlockId>, Set<BasicBlockId>) -> Boolean,
        supportsCatchExit: (Set<BasicBlockId>, BasicBlockId?) -> Boolean,
    ): StructuredCatchValidation {
        val catches = mutableListOf<StructuredCatch>()
        val occupiedHandlerBlocks = linkedSetOf<BasicBlockId>()
        for (collected in collectedCatches) {
            val entry = collected.entry
            val blocks = collected.collection.blocks
            if (blocks.any { it in occupiedHandlerBlocks }) {
                return StructuredCatchValidation(failure = TypedCatchScopeFailure.OVERLAPPING_HANDLER_REGIONS)
            }
            if (hasExternalCatchEntry(entry, blocks, handlerEntries)) {
                return StructuredCatchValidation(failure = TypedCatchScopeFailure.HANDLER_EXTERNAL_ENTRY)
            }
            if (!supportsCatchExit(blocks, continuation)) {
                return StructuredCatchValidation(failure = TypedCatchScopeFailure.UNSUPPORTED_HANDLER_EXIT)
            }
            occupiedHandlerBlocks += blocks
            catches += StructuredCatch(
                catchTypes = collected.entries.mapNotNull { it.catchType }.distinct(),
                entry = entry,
                blocks = blocks,
            )
        }
        return StructuredCatchValidation(catches = catches)
    }

    private fun collectHandlerBlocks(
        entry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        facts: ControlFlowFacts,
        currentGroup: ExceptionGroupTopology,
        allGroups: List<ExceptionGroupTopology>,
    ): TypedCatchHandlerCollection = requireNotNull(
        collectCatchBodyRegion(
            entry = entry,
            protectedBlocks = protectedBlocks,
            handlerEntries = handlerEntries,
            continuation = continuation,
            stopBlocks = emptySet(),
            rejectTargets = emptySet(),
            facts = facts,
            nestedGroups = allGroups,
            currentGroup = currentGroup,
        ),
    )

    private fun selectContinuation(
        protectedBlocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): BasicBlockId? {
        val targets = normalBoundaryTargets(protectedBlocks, handlerEntries, facts)
        if (targets.isEmpty()) return null

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
}
