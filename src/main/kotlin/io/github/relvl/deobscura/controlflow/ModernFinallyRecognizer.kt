package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*

/**
 * Proves modern compiler-style catch-all cleanup by matching the exceptional cleanup body against
 * equivalent normal-flow copies. It owns semantic finally equivalence, not exception-table topology.
 */
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
            target to (block.startInstructionIndex..<block.startInstructionIndex + body.size)
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

        val bodyBlocks = collectUntil(bodyEntry, rethrow, facts)
        if (bodyBlocks.isEmpty() || rethrow in bodyBlocks) return null
        if (bodyBlocks.any { block -> rethrow !in facts.postDominators[block].orEmpty() }) return null
        if (bodyBlocks.any { block -> graph.instructions(graph.block(block)).any { it is RawSwitchInstruction } }) return null
        if (bodyBlocks.any { block -> facts.outgoing[block].orEmpty().any { edge -> edge.to !in bodyBlocks && edge.to != rethrow } }) return null

        val boundaryTargets = normalBoundaryTargets(protectedBlocks, setOf(handlerEntry), facts)
        if (boundaryTargets.isEmpty()) return null

        val matches = boundaryTargets.mapNotNull { target ->
            FinallyBodyMatcher.match(
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
                val instructionCount = list.sumOf { it.endInstructionIndexExclusive - it.startInstructionIndex } - handlerEntryInstructionOffset
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
            protectedRanges = group.segments.map { segment -> StructuredProtectedRange(segment.range.start, segment.range.endExclusive) },
        )
    }

    private fun isControlTransfer(instruction: RawInstruction): Boolean =
        instruction is RawBranchInstruction ||
                instruction is RawSwitchInstruction ||
                instruction is RawReturnInstruction ||
                instruction is RawThrowInstruction
}
