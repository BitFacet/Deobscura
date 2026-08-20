package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.normalize.LegacySubroutineContext
import io.github.relvl.deobscura.normalize.LegacySubroutineProvenance
import io.github.relvl.deobscura.normalize.LegacySyntheticInstructionKind
import io.github.relvl.deobscura.raw.*

/**
 * Reconstructs legacy JSR/RET finally families after normalization.
 *
 * This component owns both the semantic proof of the exceptional/normal finally copies and the
 * source-level composition of legacy try/finally and try/catch/finally regions. Physical exception
 * topology, typed-catch proof, ownership closure, and duplicated-body equivalence remain shared
 * lower-level services.
 */
internal class LegacyFinallyRecognizer(private val typedCatchRecognizer: TypedCatchRecognizer) {
    private fun <T> rejectLegacy(rejectionTrace: MutableList<String>?, reason: String): T? {
        rejectionTrace?.add(reason)
        return null
    }

    private data class LegacyFinallyShape(
        val handlerEntry: BasicBlockId,
        val handlerBlocks: Set<BasicBlockId>,
        val bodyInstructionRanges: List<IntRange>,
        val normalCopies: List<FinallyBodyMatch>,
        val continuation: BasicBlockId?,
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
        rejectionTrace: MutableList<String>?
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
            FinallyBodyMatcher.match(
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
            normalizeLegacyFinallyContinuation(requireNotNull(match.continuation), graph, facts, provenance)
        }
        val continuation = when {
            continuations.size == 1 -> continuations.single()
            continuations.all { candidate -> hasOnlyTerminalNormalExits(candidate, graph, facts) } -> null
            else -> return rejectLegacy(rejectionTrace, "continuation-count=${continuations.size}")
        }

        return LegacyFinallyShape(
            handlerEntry = handlerEntry,
            handlerBlocks = handlerBlocks,
            bodyInstructionRanges = instructionRanges(bodyBlocks, graph),
            normalCopies = normalCopies,
            continuation = continuation,
        )
    }

    /**
     * Reconstructs an old javac `try/catch/finally` family after JSR/RET normalization, including
     * nested typed catches protected by the same outer finally.
     */
    fun recognizeTryCatchFinally(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        exceptionTopology: ExceptionTopology,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>? = null,
    ): LegacyTryCatchFinallyRecognition? {
        val allGroups = exceptionTopology.groups
        val labelPositions = exceptionTopology.labelPositions
        val group = topology.group
        val catchAllHandlers = group.handlers.filter { it.catchType == null }
        val typedHandlers = group.handlers.filter { it.catchType != null }
        if (catchAllHandlers.size != 1) return rejectLegacy(rejectionTrace, "handler-set")

        val catchAll = catchAllHandlers.single()
        val handlerInstructionIndex = exceptionLabelPosition(labelPositions, catchAll.handler)
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

        val finallyCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks }
        val familyBuild = buildFinallyFamilyTopology(
            anchor = topology,
            catchAllHandlerInstructionIndex = handlerInstructionIndex,
            exceptionTopology = exceptionTopology,
            excludedBlocks = finallyHandlerBlocks + finallyCopyBlocks,
            continuation = continuation,
            facts = facts,
        )
        if (familyBuild.failure != null) {
            val reason = when (familyBuild.failure) {
                FinallyFamilyTopologyFailure.EMPTY_CATCH_ALL_FAMILY -> "empty-catch-all-family"
                FinallyFamilyTopologyFailure.NOT_FAMILY_ANCHOR -> "not-first-catch-all-peer"
            }
            return rejectLegacy(rejectionTrace, reason)
        }
        if (familyBuild.typedFailure != null) {
            val reason = when (familyBuild.typedFailure) {
                TypedCatchTopologyFailure.INVALID_HANDLER_ENTRY -> "typed-handler-entry"
                TypedCatchTopologyFailure.EMPTY_PROTECTED_SCOPE -> "empty-nested-try"
                TypedCatchTopologyFailure.INVALID_PROTECTED_HEADER -> "nested-try-header"
                TypedCatchTopologyFailure.CROSSING_SCOPES -> "crossing-mixed-handler-scopes"
            }
            return rejectLegacy(rejectionTrace, reason)
        }
        val family = requireNotNull(familyBuild.topology)
        if (family.mixedGroups.isEmpty()) return rejectLegacy(rejectionTrace, "no-mixed-peers")

        val mixedSignatures = family.mixedGroups
            .map { candidate -> exceptionHandlerSignature(candidate.group.handlers, labelPositions) }
            .distinct()
        if (typedHandlers.isEmpty() || mixedSignatures.size > 1) {
            return recognizeLegacyNestedTryCatchFinally(
                graph = graph,
                family = family,
                allGroups = allGroups,
                finallyShape = finallyShape,
                labelPositions = labelPositions,
                facts = facts,
                provenance = provenance,
                rejectionTrace = rejectionTrace,
            )
        }

        // Identical mixed signatures represent one source typed-catch scope under the outer finally.
        // Reuse the same typed-scope proof as ordinary and nested catches instead of rebuilding the
        // handler topology and validation locally in the direct try/catch/finally path.
        val sourceScope = family.typedTopology.scopes.singleOrNull()
            ?: return rejectLegacy(rejectionTrace, "typed-scope-count=${family.typedTopology.scopes.size}")
        val sourceTryBlocks = family.mixedGroups.flatMapTo(linkedSetOf()) { candidate ->
            extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - finallyHandlerBlocks
        val typedEntries = sourceScope.handlersByEntry.keys
        val typedScope = typedCatchRecognizer.analyze(
            scope = sourceScope.copy(
                header = header,
                protectedBlocks = sourceTryBlocks,
            ),
            facts = facts,
            rejectNormalBoundaryWithoutContinuation = false,
            allowLoopBackContinuation = false,
            collectCatch = { entry, _, scopeContinuation ->
                collectCatchBodyBeforeFinally(
                    typedCatchRecognizer = typedCatchRecognizer,
                    entry = entry,
                    protectedBlocks = sourceTryBlocks,
                    otherHandlerEntries = typedEntries + handlerEntry,
                    finallyCopyBlocks = finallyCopyBlocks,
                    continuation = scopeContinuation,
                    facts = facts,
                )?.let { blocks -> TypedCatchHandlerCollection(blocks, null) }
            },
            hasExternalCatchEntry = { entry, blocks, entries ->
                typedCatchRecognizer.hasExternalHandlerEntry(entry, blocks, entries, facts)
            },
            supportsCatchExit = { _, _ -> true },
            requiredContinuation = continuation,
            hasExternalProtectedEntryCheck = { sourceHeader, blocks ->
                hasExternalEntry(blocks, facts) { target, source ->
                    target == sourceHeader || source in finallyCopyBlocks
                }
            },
        )
        if (typedScope.failure != null) {
            val reason = when (typedScope.failure) {
                TypedCatchScopeFailure.PROTECTED_EXTERNAL_ENTRY -> "source-try-external-entry"
                TypedCatchScopeFailure.CATCH_BODY_SHAPE -> "catch-body-shape"
                TypedCatchScopeFailure.EMPTY_HANDLER_REGION -> "empty-catch-body"
                TypedCatchScopeFailure.OVERLAPPING_HANDLER_REGIONS -> "overlapping-catch-bodies"
                TypedCatchScopeFailure.HANDLER_EXTERNAL_ENTRY -> "catch-external-entry"
                TypedCatchScopeFailure.NO_COMMON_CONTINUATION,
                TypedCatchScopeFailure.UNSUPPORTED_HANDLER_EXIT -> "catch-body-shape"
            }
            return rejectLegacy(rejectionTrace, reason)
        }
        val catches = requireNotNull(typedScope.proof).catches

        val handlerRanges = finallyShape.bodyInstructionRanges
        val protectedRanges = family.protectedRanges
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
            consumedGroupKeys = family.groups.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }

    private fun recognizeLegacyNestedTryCatchFinally(
        graph: ControlFlowGraph,
        family: FinallyFamilyTopology,
        allGroups: List<ExceptionGroupTopology>,
        finallyShape: LegacyFinallyShape,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
        provenance: LegacySubroutineProvenance?,
        rejectionTrace: MutableList<String>?,
    ): LegacyTryCatchFinallyRecognition? {
        // Typed catches are reconstructed independently from the outer catch-all family. The
        // exception table may split the outer finally around each nested catch, but those physical
        // fragments are not separate source try/finally constructs.
        val handlerEntry = finallyShape.handlerEntry
        val finallyHandlerBlocks = finallyShape.handlerBlocks
        val matches = finallyShape.normalCopies
        val continuation = finallyShape.continuation
        val finallyCopyBlocks = matches.flatMapTo(linkedSetOf()) { it.blocks }
        val typedHandlerEntries = family.typedTopology.scopes
            .flatMapTo(linkedSetOf()) { it.handlersByEntry.keys }
        if (typedHandlerEntries.isEmpty()) return rejectLegacy(rejectionTrace, "mixed-handler-signature")

        val nestedRegions = mutableListOf<StructuredRegion>()
        for (scope in family.typedTopology.depthFirstScopes()) {
            val typedScope = typedCatchRecognizer.analyze(
                scope = scope,
                facts = facts,
                rejectNormalBoundaryWithoutContinuation = false,
                allowLoopBackContinuation = false,
                collectCatch = { entry, _, nestedContinuation ->
                    collectCatchBodyBeforeFinally(
                        typedCatchRecognizer = typedCatchRecognizer,
                        entry = entry,
                        protectedBlocks = scope.protectedBlocks,
                        otherHandlerEntries = typedHandlerEntries + handlerEntry,
                        finallyCopyBlocks = finallyCopyBlocks,
                        continuation = nestedContinuation,
                        facts = facts,
                    )?.let { blocks -> TypedCatchHandlerCollection(blocks, null) }
                },
                hasExternalCatchEntry = { entry, blocks, scopeHandlerEntries ->
                    // Only handlers belonging to this typed scope are legitimate peer entries.
                    // The global handler set remains a traversal boundary above, but trusting a
                    // handler from another nested/sibling scope here would hide a real external
                    // entry into this catch body.
                    hasExternalLegacyHandlerEntry(entry, blocks, scopeHandlerEntries, facts, graph, provenance)
                },
                supportsCatchExit = { _, _ -> true },
            )
            if (typedScope.failure != null) {
                val reason = when (typedScope.failure) {
                    TypedCatchScopeFailure.PROTECTED_EXTERNAL_ENTRY -> "nested-try-external-entry"
                    TypedCatchScopeFailure.CATCH_BODY_SHAPE -> "nested-catch-body-shape"
                    TypedCatchScopeFailure.EMPTY_HANDLER_REGION,
                    TypedCatchScopeFailure.OVERLAPPING_HANDLER_REGIONS -> "nested-catch-overlap"

                    TypedCatchScopeFailure.HANDLER_EXTERNAL_ENTRY -> "nested-catch-external-entry"
                    TypedCatchScopeFailure.NO_COMMON_CONTINUATION -> "nested-catch-no-common-continuation"
                    TypedCatchScopeFailure.UNSUPPORTED_HANDLER_EXIT -> "nested-catch-unsupported-exit"
                }
                return rejectLegacy(rejectionTrace, reason)
            }
            nestedRegions += requireNotNull(typedScope.proof).toRegion()
        }

        val outerHeader = facts.instructionToBlock.getOrNull(family.protectedRanges.first().startInstructionIndex)
            ?: return rejectLegacy(rejectionTrace, "outer-finally-header")
        val finallyCopyOwnership = collectExceptionRegionClosure(
            seedBlocks = finallyCopyBlocks,
            allGroups = allGroups,
            excludedHandlerEntries = setOf(handlerEntry),
            continuation = continuation,
            labelPositions = labelPositions,
            facts = facts,
        )
        if (outerHeader !in family.protectedBlocks || hasExternalEntryOutsideOwnership(
                header = outerHeader,
                blocks = family.protectedBlocks,
                externalOwnership = finallyCopyOwnership,
                facts = facts,
            )
        ) {
            return rejectLegacy(rejectionTrace, "outer-finally-external-entry")
        }

        val outerFinally = StructuredRegion.TryFinally(
            header = outerHeader,
            tryBlocks = family.protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = finallyHandlerBlocks,
            finallyBodyInstructionRanges = finallyShape.bodyInstructionRanges,
            normalCopyInstructionIndices = matches.flatMap { it.instructionRanges },
            normalCopyBlocks = finallyCopyBlocks,
            continuation = continuation,
            protectedStartInstructionIndex = family.protectedRanges.first().startInstructionIndex,
            protectedEndInstructionIndexExclusive = family.protectedRanges.last().endInstructionIndexExclusive,
            protectedRanges = family.protectedRanges,
        )
        return LegacyTryCatchFinallyRecognition(
            region = outerFinally,
            additionalRegions = nestedRegions,
            consumedGroupKeys = family.groups.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }


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
    /** Reconstructs a legacy catch-all family that represents a plain source `try/finally`. */
    fun recognizeFinally(
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

        val signature = exceptionHandlerSignature(group.handlers, labelPositions)
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
            exceptionHandlerSignature(candidate.group.handlers, labelPositions) == signature
        }
        if (family.isEmpty()) return rejectLegacy(rejectionTrace, "empty-handler-family")
        val protectedBlocks = family.flatMapTo(linkedSetOf()) { candidate ->
            extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
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

    /** Multiple JSR return sites can be distinct terminal source exits after one finally. */
    private fun hasOnlyTerminalNormalExits(
        start: BasicBlockId,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): Boolean {
        val visiting = linkedSetOf<BasicBlockId>()
        val proven = linkedSetOf<BasicBlockId>()

        fun prove(block: BasicBlockId): Boolean {
            if (block in proven) return true
            if (!visiting.add(block)) return false
            val normalTargets = facts.outgoing[block].orEmpty()
                .filter { edge -> edge.kind != ControlFlowEdgeKind.EXCEPTION }
                .map { edge -> edge.to }
                .distinct()
            val result = if (normalTargets.isEmpty()) {
                when (graph.instructions(graph.block(block)).lastOrNull()) {
                    is RawReturnInstruction, is RawThrowInstruction -> true
                    else -> false
                }
            } else {
                normalTargets.all(::prove)
            }
            visiting.remove(block)
            if (result) proven += block
            return result
        }

        return prove(start)
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

    /**
     * Computes structural ownership around an already-proven region. Exception handlers whose
     * protected blocks are wholly owned by the region are part of that region too. The caller proves
     * the seed blocks; this function only supplies the legacy handler-collection policy to the
     * generic exception-containment closure above.
     */
    private fun collectExceptionRegionClosure(
        seedBlocks: Set<BasicBlockId>,
        allGroups: List<ExceptionGroupTopology>,
        excludedHandlerEntries: Set<BasicBlockId>,
        continuation: BasicBlockId?,
        labelPositions: Map<RawLabelId, Int>,
        facts: ControlFlowFacts,
    ): Set<BasicBlockId> {
        val owned = linkedSetOf<BasicBlockId>().apply { addAll(seedBlocks) }
        closeOverContainedExceptionRegions(
            owned = owned,
            groups = allGroups,
            excludeGroup = { false },
            revisitContainedGroups = true,
            handlerEntriesFor = { candidate ->
                candidate.group.handlers.mapNotNullTo(linkedSetOf()) { handler ->
                    facts.instructionToBlock.getOrNull(exceptionLabelPosition(labelPositions, handler.handler))
                } - excludedHandlerEntries
            },
            absorb = { candidate, entries, containedHandlerEntries ->
                var changed = false
                for (entry in entries) {
                    if (entry in owned) continue
                    val blocks = collectCatchBodyBeforeFinally(
                        typedCatchRecognizer = typedCatchRecognizer,
                        entry = entry,
                        protectedBlocks = candidate.protectedBlocks,
                        otherHandlerEntries = containedHandlerEntries + excludedHandlerEntries,
                        finallyCopyBlocks = owned,
                        continuation = continuation,
                        facts = facts,
                    ) ?: continue
                    if (owned.addAll(blocks)) changed = true
                }
                if (changed) ExceptionOwnershipExpansion.CHANGED else ExceptionOwnershipExpansion.UNCHANGED
            },
        )
        return owned
    }

    private fun hasExternalEntryOutsideOwnership(header: BasicBlockId, blocks: Set<BasicBlockId>, externalOwnership: Set<BasicBlockId>, facts: ControlFlowFacts): Boolean =
        hasExternalEntry(blocks, facts) { target, source -> target == header || source in externalOwnership }

    private fun hasExternalLegacyHandlerEntry(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        handlerEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        graph: ControlFlowGraph,
        provenance: LegacySubroutineProvenance?
    ): Boolean = hasExternalEntry(blocks, facts) { target, source ->
        if (isHandlerPeerEntryTransfer(entry, target, source, handlerEntries)) {
            return@hasExternalEntry true
        }
        provenance != null && isNormalizerOwnedTransfer(source, target, graph, provenance)
    }

    private fun isNormalizerOwnedTransfer(from: BasicBlockId, to: BasicBlockId, graph: ControlFlowGraph, provenance: LegacySubroutineProvenance): Boolean {
        val fromBlock = graph.block(from)
        val toBlock = graph.block(to)
        val transfer = provenance.originAt(fromBlock.endInstructionIndexExclusive - 1) ?: return false
        val target = provenance.originAt(toBlock.startInstructionIndex) ?: return false
        if (transfer.syntheticKind == null) return false
        return contextsRelated(transfer.context, target.context)
    }

    private fun contextsRelated(left: LegacySubroutineContext, right: LegacySubroutineContext): Boolean =
        left == right || left.parent == right || right.parent == left
}

/** Legacy composite recognition may emit nested typed regions and consume several table groups. */
internal data class LegacyTryCatchFinallyRecognition(
    val region: StructuredRegion,
    val additionalRegions: List<StructuredRegion> = emptyList(),
    val consumedGroupKeys: Set<ExceptionRegionKey>,
)

/** Plain legacy finally recognition and the physical groups represented by it. */
internal data class LegacyFinallyRecognition(
    val region: StructuredRegion.TryFinally,
    val consumedGroupKeys: Set<ExceptionRegionKey>,
)
