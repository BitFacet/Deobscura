package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*

/**
 * Proves modern compiler-style catch-all cleanup by matching the exceptional cleanup body against
 * equivalent normal-flow copies. It owns semantic finally equivalence, not exception-table topology.
 */
internal data class ModernFinallyShape(
    val handlerEntry: BasicBlockId,
    val handlerBlocks: Set<BasicBlockId>,
    val bodyInstructionRanges: List<IntRange>,
    val normalCopies: List<FinallyBodyMatch>,
    val continuation: BasicBlockId?,
)

internal object ModernFinallyRecognizer {
    /** Tries the supported modern finally shapes, from strict linear form to branching cleanup. */
    fun recognize(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        facts: ControlFlowFacts
    ): StructuredRegion.TryFinally? =
        recognizeLinearCanonicalFinally(graph, topology, header, protectedBlocks, groupedHandlers, facts)
            ?: recognizeSplitLinearCanonicalFinally(graph, topology, header, protectedBlocks, groupedHandlers, facts)
            ?: recognizeStackPreservedCanonicalFinally(graph, topology, header, protectedBlocks, groupedHandlers, facts)
            ?: recognizeBranchingCanonicalFinally(graph, topology, header, protectedBlocks, groupedHandlers, facts)

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
        val shape = analyzeLinearFinallyShape(
            graph = graph,
            handlerEntry = handlerEntry,
            protectedBlocks = protectedBlocks,
            facts = facts,
        ) ?: return null

        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = shape.handlerEntry,
            handlerBlocks = shape.handlerBlocks,
            finallyBodyInstructionRanges = shape.bodyInstructionRanges,
            normalCopyInstructionIndices = shape.normalCopies.flatMap { it.instructionRanges },
            normalCopyBlocks = shape.normalCopies.flatMapTo(linkedSetOf()) { it.blocks },
            continuation = shape.continuation,
            protectedStartInstructionIndex = group.envelope.start,
            protectedEndInstructionIndexExclusive = group.envelope.endExclusive,
            protectedRanges = group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            },
        )
    }

    /**
     * Proves the linear `astore ex; BODY; aload ex; athrow` shape independently from handler-set
     * topology so mixed try/catch/finally families can reuse the same evidence as plain finally.
     */
    internal fun analyzeLinearFinallyShape(
        graph: ControlFlowGraph,
        handlerEntry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): ModernFinallyShape? {
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

        val matches = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts).mapNotNull { target ->
            val block = graph.block(target)
            val instructions = graph.instructions(block)
            if (instructions.size < body.size || instructions.subList(0, body.size) != body) return@mapNotNull null
            val continuation = facts.outgoing[target].orEmpty().map { it.to }.distinct().singleOrNull()
            FinallyBodyMatch(
                blocks = setOf(target),
                continuation = continuation,
                instructionRanges = listOf(block.startInstructionIndex..<block.startInstructionIndex + body.size),
            )
        }
        if (matches.isEmpty()) return null

        val continuationCandidates = matches.mapNotNullTo(linkedSetOf()) { it.continuation }
        if (continuationCandidates.size > 1) return null

        val handlerBodyStart = handlerBlock.startInstructionIndex + 1
        return ModernFinallyShape(
            handlerEntry = handlerEntry,
            handlerBlocks = setOf(handlerEntry),
            bodyInstructionRanges = listOf(handlerBodyStart..(handlerBodyStart + body.size - 1)),
            normalCopies = matches,
            continuation = continuationCandidates.singleOrNull(),
        )
    }

    /**
     * Handles the equally canonical split form `astore ex` -> `BODY; aload ex; athrow`.
     * Some compilers introduce the block boundary immediately after storing the exception even
     * though the cleanup itself is linear.
     */
    private fun recognizeSplitLinearCanonicalFinally(
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
        val entryInstructions = graph.instructions(graph.block(handlerEntry))
        if (entryInstructions.size != 1) return null
        val store = entryInstructions.single() as? RawLocalInstruction ?: return null
        if (store.operation != LocalOperation.STORE || store.type != JvmComputationalType.REFERENCE) return null

        val bodyBlock = facts.outgoing[handlerEntry].orEmpty()
            .map { it.to }
            .distinct()
            .singleOrNull() ?: return null
        if (facts.incoming[bodyBlock].orEmpty().any { it.from != handlerEntry }) return null

        val bodyInstructions = graph.instructions(graph.block(bodyBlock))
        if (bodyInstructions.size < 3) return null
        val reload = bodyInstructions[bodyInstructions.lastIndex - 1] as? RawLocalInstruction ?: return null
        if (reload.operation != LocalOperation.LOAD || reload.type != JvmComputationalType.REFERENCE || reload.slot != store.slot) return null
        if (bodyInstructions.last() !is RawThrowInstruction) return null

        val body = bodyInstructions.subList(0, bodyInstructions.size - 2)
        if (body.isEmpty() || body.any(::isControlTransfer)) return null
        if (facts.outgoing[bodyBlock].orEmpty().isNotEmpty()) return null

        val boundaryTargets = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts)
        if (boundaryTargets.isEmpty()) return null
        val matches = boundaryTargets.mapNotNull { target ->
            val block = graph.block(target)
            val instructions = graph.instructions(block)
            if (instructions.size < body.size || instructions.subList(0, body.size) != body) return@mapNotNull null
            target to (block.startInstructionIndex..<block.startInstructionIndex + body.size)
        }
        if (matches.isEmpty()) return null

        val continuationCandidates = matches.mapNotNullTo(linkedSetOf()) { (target, _) ->
            facts.outgoing[target].orEmpty().map { it.to }.distinct().singleOrNull()
        }
        if (continuationCandidates.size > 1) return null

        val bodyStart = graph.block(bodyBlock).startInstructionIndex
        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = linkedSetOf(handlerEntry, bodyBlock),
            finallyBodyInstructionRanges = listOf(bodyStart..(bodyStart + body.size - 1)),
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
     * Handles catch-all cleanup that keeps the implicit handler exception on the operand stack:
     * `BODY; athrow`. The same BODY must occur verbatim on a normal exit, which proves that the
     * instructions before `athrow` are cleanup rather than an ordinary catch body.
     */
    private fun recognizeStackPreservedCanonicalFinally(
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
        if (handlerInstructions.size < 2 || handlerInstructions.last() !is RawThrowInstruction) return null

        val body = handlerInstructions.dropLast(1)
        if (body.isEmpty() || body.any(::isControlTransfer)) return null
        if (facts.outgoing[handlerEntry].orEmpty().isNotEmpty()) return null

        val boundaryMatches = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts).mapNotNull { target ->
            val block = graph.block(target)
            val instructions = graph.instructions(block)
            if (instructions.size < body.size || instructions.subList(0, body.size) != body) return@mapNotNull null
            target to (block.startInstructionIndex..<block.startInstructionIndex + body.size)
        }
        val terminalMatches = terminalProtectedReturnCopies(graph, protectedBlocks, body, facts)
        val matches = boundaryMatches + terminalMatches
        if (matches.isEmpty()) return null

        val continuationCandidates = boundaryMatches.mapNotNullTo(linkedSetOf()) { (target, _) ->
            facts.outgoing[target].orEmpty().map { it.to }.distinct().singleOrNull()
        }
        if (continuationCandidates.size > 1) return null

        val bodyStart = handlerBlock.startInstructionIndex
        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = handlerEntry,
            handlerBlocks = setOf(handlerEntry),
            finallyBodyInstructionRanges = listOf(bodyStart..(bodyStart + body.size - 1)),
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
        val shape = analyzeBranchingFinallyShape(
            graph = graph,
            handlerEntry = handlerEntry,
            protectedBlocks = protectedBlocks,
            facts = facts,
        ) ?: return null

        return StructuredRegion.TryFinally(
            header = header,
            tryBlocks = protectedBlocks,
            handlerEntry = shape.handlerEntry,
            handlerBlocks = shape.handlerBlocks,
            finallyBodyInstructionRanges = shape.bodyInstructionRanges,
            normalCopyInstructionIndices = shape.normalCopies.flatMap { it.instructionRanges },
            normalCopyBlocks = shape.normalCopies.flatMapTo(linkedSetOf()) { it.blocks },
            continuation = shape.continuation,
            protectedStartInstructionIndex = group.envelope.start,
            protectedEndInstructionIndexExclusive = group.envelope.endExclusive,
            protectedRanges = group.segments.map { segment ->
                StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
            },
        )
    }

    /**
     * Proves a branching exceptional cleanup against every matching normal boundary copy. This is
     * intentionally independent from handler-set topology so the same semantic proof can be reused
     * by a mixed try/catch/finally family.
     */
    internal fun analyzeBranchingFinallyShape(
        graph: ControlFlowGraph,
        handlerEntry: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        rejectionTrace: MutableList<String>? = null,
        allowTerminalAndGuardElidedNormalCopies: Boolean = false,
        excludeExceptionalCleanupFromNormalBoundaries: Boolean = false,
    ): ModernFinallyShape? {
        fun reject(reason: String): ModernFinallyShape? {
            rejectionTrace?.add(reason)
            return null
        }

        val entryBlock = graph.block(handlerEntry)
        val entryInstructions = graph.instructions(entryBlock)
        if (entryInstructions.isEmpty()) return reject("empty-handler-entry")
        val store = entryInstructions.first() as? RawLocalInstruction ?: return reject("handler-entry-not-local")
        if (store.operation != LocalOperation.STORE || store.type != JvmComputationalType.REFERENCE) return reject("handler-entry-not-reference-store")

        val handlerEntryInstructionOffset: Int
        val bodyEntry: BasicBlockId
        if (entryInstructions.size == 1) {
            handlerEntryInstructionOffset = 0
            bodyEntry = facts.outgoing[handlerEntry].orEmpty().map { it.to }.distinct().singleOrNull() ?: return reject("handler-body-entry")
        } else {
            handlerEntryInstructionOffset = 1
            bodyEntry = handlerEntry
        }
        val reachable = collectAcyclicNormalRegion(bodyEntry, facts) ?: return reject("handler-body-cyclic")
        val rethrowBlocks = reachable.filter { block -> isCanonicalRethrowBlock(graph, block, store.slot) }
        if (rethrowBlocks.size != 1) return reject("rethrow-count=${rethrowBlocks.size}")
        val rethrow = rethrowBlocks.single()

        val bodyBlocks = collectUntil(bodyEntry, rethrow, facts)
        if (bodyBlocks.isEmpty() || rethrow in bodyBlocks) return reject("empty-or-overlapping-body")
        if (bodyBlocks.any { block -> rethrow !in facts.postDominators[block].orEmpty() }) return reject("rethrow-not-postdominator")
        if (bodyBlocks.any { block -> graph.instructions(graph.block(block)).any { it is RawSwitchInstruction } }) return reject("switch-in-cleanup")
        if (bodyBlocks.any { block -> facts.outgoing[block].orEmpty().any { edge -> edge.to !in bodyBlocks && edge.to != rethrow } }) return reject("cleanup-has-extra-exit")

        val boundaryTargets = if (excludeExceptionalCleanupFromNormalBoundaries) {
            val handlerOwnedBlocks = linkedSetOf<BasicBlockId>().apply {
                add(handlerEntry)
                addAll(bodyBlocks)
                add(rethrow)
            }
            // Mixed try/catch/finally peers may physically overlap the catch-all handler itself.
            // In that topology the proven exceptional cleanup must not masquerade as a normal copy.
            normalBoundaryTargets(
                protectedBlocks - handlerOwnedBlocks,
                handlerOwnedBlocks,
                facts,
            )
        } else {
            normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts)
        }
        if (boundaryTargets.isEmpty()) return reject("no-normal-boundary")
        val directMatches = boundaryTargets.mapNotNull { target ->
            FinallyBodyMatcher.match(
                graph = graph,
                handlerEntry = bodyEntry,
                handlerBlocks = bodyBlocks,
                handlerExit = rethrow,
                handlerEntryInstructionOffset = handlerEntryInstructionOffset,
                normalEntry = target,
                facts = facts,
                allowEquivalentTerminalReturnTargets = allowTerminalAndGuardElidedNormalCopies,
            )?.let { target to it }
        }
        val projectedMatches = if (allowTerminalAndGuardElidedNormalCopies && directMatches.isNotEmpty()) {
            val unmatchedTargets = boundaryTargets - directMatches.mapTo(linkedSetOf()) { it.first }
            matchGuardElidedBoundaryCopies(
                graph = graph,
                bodyEntry = bodyEntry,
                bodyBlocks = bodyBlocks,
                handlerExit = rethrow,
                handlerEntryInstructionOffset = handlerEntryInstructionOffset,
                normalEntries = unmatchedTargets,
                facts = facts,
            )
        } else {
            emptyList()
        }
        val matches = directMatches.map { it.second } + projectedMatches
        if (matches.isEmpty()) return reject("no-matching-normal-copy")
        val continuation = if (allowTerminalAndGuardElidedNormalCopies) {
            val continuations = matches.mapNotNullTo(linkedSetOf()) { it.continuation }
            if (continuations.size != 1) return reject("continuation-count=${continuations.size}")
            continuations.single()
        } else {
            val continuations = matches.mapTo(linkedSetOf()) { it.continuation }
            if (continuations.size != 1) return reject("continuation-count=${continuations.size}")
            continuations.single()
        }

        val handlerRange = bodyBlocks.asSequence()
            .map(graph::block)
            .sortedBy { it.startInstructionIndex }
            .let { blocks ->
                val list = blocks.toList()
                val first = list.first().startInstructionIndex + handlerEntryInstructionOffset
                val last = list.last().endInstructionIndexExclusive - 1
                val instructionCount = list.sumOf { it.endInstructionIndexExclusive - it.startInstructionIndex } - handlerEntryInstructionOffset
                if ((first..last).count() != instructionCount) return reject("non-contiguous-handler-body")
                first..last
            }

        return ModernFinallyShape(
            handlerEntry = handlerEntry,
            handlerBlocks = linkedSetOf<BasicBlockId>().apply {
                add(handlerEntry)
                addAll(bodyBlocks)
                add(rethrow)
            },
            bodyInstructionRanges = listOf(handlerRange),
            normalCopies = matches,
            continuation = continuation,
        )
    }


    /**
     * Accepts a path-specialized copy only after another boundary has proven the complete cleanup.
     * The specialization may remove a single entry guard whose other branch skips directly to the
     * exceptional exit; the remaining branch must still match instruction-for-instruction.
     */
    private fun matchGuardElidedBoundaryCopies(
        graph: ControlFlowGraph,
        bodyEntry: BasicBlockId,
        bodyBlocks: Set<BasicBlockId>,
        handlerExit: BasicBlockId,
        handlerEntryInstructionOffset: Int,
        normalEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
    ): List<FinallyBodyMatch> {
        val entryInstructions = graph.instructions(graph.block(bodyEntry)).drop(handlerEntryInstructionOffset)
        if (entryInstructions.size != 2) return emptyList()
        val guardValue = entryInstructions.first() as? RawLocalInstruction ?: return emptyList()
        if (guardValue.operation != LocalOperation.LOAD || guardValue.type != JvmComputationalType.REFERENCE) return emptyList()
        val guard = entryInstructions.last() as? RawBranchInstruction ?: return emptyList()
        if (guard.opcode.mnemonic !in setOf("ifnull", "ifnonnull")) return emptyList()

        val entryEdges = facts.outgoing[bodyEntry].orEmpty()
        if (entryEdges.size != 2) return emptyList()
        if (entryEdges.count { it.to == handlerExit } != 1) return emptyList()
        val guardedBodyEntry = entryEdges.single { it.to != handlerExit }.to
        if (guardedBodyEntry !in bodyBlocks) return emptyList()

        val guardedBodyBlocks = bodyBlocks - bodyEntry
        return normalEntries.mapNotNull { normalEntry ->
            FinallyBodyMatcher.match(
                graph = graph,
                handlerEntry = guardedBodyEntry,
                handlerBlocks = guardedBodyBlocks,
                handlerExit = handlerExit,
                handlerEntryInstructionOffset = 0,
                normalEntry = normalEntry,
                facts = facts,
            )
        }
    }

    /**
     * Finds normal finally copies emitted as a suffix of a protected block immediately before a
     * return. Such copies have no CFG boundary target: the physical exception range includes the
     * cleanup and the block terminates locally.
     */
    private fun terminalProtectedReturnCopies(
        graph: ControlFlowGraph,
        protectedBlocks: Set<BasicBlockId>,
        body: List<RawInstruction>,
        facts: ControlFlowFacts,
    ): List<Pair<BasicBlockId, IntRange>> = protectedBlocks.mapNotNull { blockId ->
        if (facts.outgoing[blockId].orEmpty().any { it.to !in protectedBlocks }) return@mapNotNull null
        val block = graph.block(blockId)
        val instructions = graph.instructions(block)
        if (instructions.size <= body.size || instructions.last() !is RawReturnInstruction) return@mapNotNull null
        val bodyStart = instructions.size - body.size - 1
        if (instructions.subList(bodyStart, instructions.lastIndex) != body) return@mapNotNull null
        blockId to (block.startInstructionIndex + bodyStart..<block.endInstructionIndexExclusive - 1)
    }

    private fun isControlTransfer(instruction: RawInstruction): Boolean =
        instruction is RawBranchInstruction ||
            instruction is RawSwitchInstruction ||
            instruction is RawReturnInstruction ||
            instruction is RawThrowInstruction
}
