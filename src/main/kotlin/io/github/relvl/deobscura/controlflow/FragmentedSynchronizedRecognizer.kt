package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.*
import java.util.*

/**
 * Reassembles old compiler synchronized bodies split into peer exception-table fragments.
 *
 * Each exceptional cleanup copy must release the same monitor and belong to the nearest dominating
 * monitorenter for that slot. A matching normal monitorexit is still required before the family is
 * accepted as one source synchronized statement.
 */
internal object FragmentedSynchronizedRecognizer {
    fun recognize(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        allGroups: List<ExceptionGroupTopology>,
        facts: ControlFlowFacts,
        rejectionTrace: MutableList<String>? = null,
    ): SynchronizedRecognition? {
        val instructions = graph.code.instructions
        val monitorDominators = exceptionAwareDominators(graph, facts) ?: return null
        val catchAllHandlers = topology.group.handlers.filter { it.catchType == null }
        if (catchAllHandlers.size != 1) {
            rejectionTrace?.add("fragmented:catch-all-count=${catchAllHandlers.size}")
            return null
        }
        val anchorCatchAll = catchAllHandlers.single()
        val anchorHandlerInstructionIndex = handlerInstructionIndex(anchorCatchAll, graph, facts) ?: run {
            rejectionTrace?.add("fragmented:invalid-handler-entry")
            return null
        }
        val anchorHandlerEntry = facts.instructionToBlock.getOrNull(anchorHandlerInstructionIndex) ?: run {
            rejectionTrace?.add("fragmented:invalid-handler-block")
            return null
        }
        val anchorHandlerStart = graph.block(anchorHandlerEntry).startInstructionIndex
        val monitorSlots = handlerMonitorSlots(instructions, anchorHandlerStart)
        if (monitorSlots.isEmpty()) {
            rejectionTrace?.add("fragmented:not-monitor-cleanup")
            return null
        }

        for (monitorSlot in monitorSlots) {
            if (recognizeHandlerShape(instructions, anchorHandlerStart, monitorSlot) == null) continue
            val protectedHeader = facts.instructionToBlock.getOrNull(topology.group.envelope.start) ?: continue
            val monitorEnterInstructionIndex = owningMonitorEnter(
                instructions = instructions,
                monitorSlot = monitorSlot,
                block = protectedHeader,
                dominators = monitorDominators,
                facts = facts,
            )
            if (monitorEnterInstructionIndex == null) {
                rejectionTrace?.add("fragmented:no-owning-monitorenter")
                continue
            }
            val monitorEnterBlock = facts.instructionToBlock.getOrNull(monitorEnterInstructionIndex) ?: continue

            val cleanupCopiesByGroup = buildList {
                for (candidate in allGroups) {
                    if (candidate.group.envelope.start <= monitorEnterInstructionIndex) continue
                    if (!candidate.protectedBlocks.all { block ->
                            owningMonitorEnter(instructions, monitorSlot, block, monitorDominators, facts) == monitorEnterInstructionIndex
                        }) continue

                    val copies = linkedMapOf<Int, MonitorCleanupCopy>()
                    for (handler in candidate.group.handlers) {
                        if (handler.catchType != null) continue
                        val handlerIndex = handlerInstructionIndex(handler, graph, facts) ?: continue
                        val entry = facts.instructionToBlock.getOrNull(handlerIndex) ?: continue
                        val handlerStart = graph.block(entry).startInstructionIndex
                        val shape = recognizeHandlerShape(instructions, handlerStart, monitorSlot) ?: continue
                        copies.putIfAbsent(handlerStart, MonitorCleanupCopy(entry, handlerStart, shape))
                    }
                    val copy = copies.values.singleOrNull() ?: continue
                    add(candidate to copy)
                }
            }
            if (cleanupCopiesByGroup.none { (candidate, _) -> candidate === topology }) {
                rejectionTrace?.add("fragmented:anchor-not-owned-by-monitor")
                continue
            }

            val handlerMonitorExits = cleanupCopiesByGroup.mapTo(hashSetOf()) { (_, copy) ->
                copy.shape.monitorExitInstructionIndex
            }
            val normalExitIndices = findNormalMonitorExits(
                instructions = instructions,
                monitorSlot = monitorSlot,
                monitorEnterInstructionIndex = monitorEnterInstructionIndex,
                handlerMonitorExitInstructionIndices = handlerMonitorExits,
                dominators = monitorDominators,
                facts = facts,
            )
            if (normalExitIndices.isEmpty()) {
                rejectionTrace?.add("fragmented:no-normal-monitorexit")
                continue
            }
            val lastNormalExit = normalExitIndices.maxOrNull() ?: continue

            val familyWithCopies = cleanupCopiesByGroup
                .filter { (candidate, _) -> candidate.group.envelope.start <= lastNormalExit }
                .sortedBy { (candidate, _) -> candidate.group.envelope.start }
            if (familyWithCopies.isEmpty() || familyWithCopies.first().first !== topology) {
                rejectionTrace?.add("fragmented:not-family-anchor")
                continue
            }
            val family = familyWithCopies.map { (candidate, _) -> candidate }
            val handlerCopies = familyWithCopies.map { (_, copy) -> copy }.distinctBy { copy -> copy.handlerStart }

            val handlerBlocks = handlerCopies.flatMapTo(linkedSetOf()) { copy ->
                (copy.handlerStart..copy.shape.throwInstructionIndex).mapNotNull { index ->
                    facts.instructionToBlock.getOrNull(index)
                }
            }
            if (handlerBlocks.isEmpty()) continue

            val bodyEntry = facts.outgoing[monitorEnterBlock].orEmpty()
                .asSequence()
                .filter { edge -> edge.kind != ControlFlowEdgeKind.EXCEPTION && edge.to !in handlerBlocks }
                .map { edge -> edge.to }
                .distinct()
                .singleOrNull() ?: continue

            val normalBodyBlocks = collectBodyBlocks(
                header = bodyEntry,
                handlerBlocks = handlerBlocks,
                normalMonitorExitInstructionIndices = normalExitIndices,
                monitorEnterInstructionIndex = monitorEnterInstructionIndex,
                monitorSlot = monitorSlot,
                instructions = instructions,
                dominators = monitorDominators,
                facts = facts,
            )
            if (normalBodyBlocks.isEmpty()) continue

            val familyProtectedBlocks = family.flatMapTo(linkedSetOf()) { candidate -> candidate.protectedBlocks }
            val bodyBlocks = linkedSetOf<BasicBlockId>().apply {
                addAll(normalBodyBlocks)
                addAll(familyProtectedBlocks)
                removeAll(handlerBlocks)
            }
            if (bodyBlocks.isEmpty()) continue
            val nestedClosureTraceSize = rejectionTrace?.size ?: 0
            if (!closeOverNestedExceptionRegions(
                    owned = bodyBlocks,
                    groups = allGroups,
                    synchronizedHandlerBlocks = handlerBlocks,
                    facts = facts,
                    rejectionTrace = rejectionTrace,
                )
            ) {
                if ((rejectionTrace?.size ?: 0) == nestedClosureTraceSize) {
                    rejectionTrace?.add("fragmented:nested-handler-closure")
                }
                continue
            }
            if (hasExternalProtectedEntry(bodyEntry, bodyBlocks, facts)) {
                rejectionTrace?.add("fragmented:body-external-entry")
                continue
            }

            val protectedRanges = family.flatMap { candidate -> candidate.group.segments }
                .map { segment -> StructuredProtectedRange(segment.range.start, segment.range.endExclusive) }
                .distinct()
                .sortedWith(
                    compareBy<StructuredProtectedRange> { it.startInstructionIndex }
                        .thenBy { it.endInstructionIndexExclusive },
                )
            if (protectedRanges.isEmpty()) continue

            val syntheticCleanupCompanions = allGroups.filter { candidate ->
                candidate !in family &&
                    handlerCopies.any { copy ->
                        candidate.group.handlers.size == 1 &&
                            candidate.group.handlers.single().catchType == null &&
                            candidate.group.envelope.start == copy.handlerStart &&
                            candidate.group.envelope.endExclusive == copy.shape.monitorExitInstructionIndex + 1 &&
                            candidate.handlerEntries == setOf(copy.entry)
                    }
            }
            if (handlerCopies.any { copy ->
                    syntheticCleanupCompanions.count { candidate ->
                        candidate.group.envelope.start == copy.handlerStart &&
                            candidate.group.envelope.endExclusive == copy.shape.monitorExitInstructionIndex + 1 &&
                            candidate.handlerEntries == setOf(copy.entry)
                    } > 1
                }
            ) continue
            val syntheticCleanupRanges = syntheticCleanupCompanions.flatMap { companion ->
                companion.group.segments.map { segment ->
                    StructuredProtectedRange(segment.range.start, segment.range.endExclusive)
                }
            }

            val representative = handlerCopies.first()
            return SynchronizedRecognition(
                region = StructuredRegion.Synchronized(
                    header = monitorEnterBlock,
                    bodyEntry = bodyEntry,
                    bodyBlocks = bodyBlocks,
                    handlerEntry = representative.entry,
                    handlerBlocks = handlerBlocks,
                    monitorSlot = monitorSlot,
                    monitorEnterInstructionIndex = monitorEnterInstructionIndex,
                    normalMonitorExitInstructionIndices = normalExitIndices,
                    handlerMonitorExitInstructionIndex = representative.shape.monitorExitInstructionIndex,
                    protectedStartInstructionIndex = protectedRanges.minOf { range -> range.startInstructionIndex },
                    protectedEndInstructionIndexExclusive = protectedRanges.maxOf { range -> range.endInstructionIndexExclusive },
                    protectedRanges = protectedRanges,
                    syntheticCleanupProtectedRanges = syntheticCleanupRanges,
                ),
                consumedGroupKeys = linkedSetOf<ExceptionRegionKey>().apply {
                    family.drop(1).mapTo(this) { candidate ->
                        ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
                    }
                    syntheticCleanupCompanions.mapTo(this) { companion ->
                        ExceptionRegionKey(companion.group.envelope.start, companion.group.envelope.endExclusive)
                    }
                },
            )
        }
        return null
    }


    /**
     * Expands source ownership over nested exception handlers whose protected scope is already
     * inside the synchronized body. This keeps catch rejoin edges internal without relaxing the
     * external-entry proof for genuinely unrelated control flow.
     */
    private fun closeOverNestedExceptionRegions(
        owned: MutableSet<BasicBlockId>,
        groups: List<ExceptionGroupTopology>,
        synchronizedHandlerBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        rejectionTrace: MutableList<String>? = null,
    ): Boolean = closeOverContainedExceptionRegions(
        owned = owned,
        groups = groups,
        excludeGroup = { false },
        revisitContainedGroups = false,
        handlerEntriesFor = { candidate ->
            candidate.handlerEntries.filterTo(linkedSetOf()) { entry -> entry !in synchronizedHandlerBlocks }
        },
        absorb = { candidate, entries, _ ->
            val before = owned.size
            for (entry in entries) {
                val nestedBlocks = collectRejoiningNestedHandlerBlocks(
                    entry = entry,
                    owned = owned,
                    synchronizedHandlerBlocks = synchronizedHandlerBlocks,
                    facts = facts,
                    rejectionTrace = rejectionTrace,
                    context = "range=${candidate.group.envelope.start}..<${candidate.group.envelope.endExclusive} entry=$entry",
                ) ?: return@closeOverContainedExceptionRegions ExceptionOwnershipExpansion.REJECTED
                owned.addAll(nestedBlocks)
            }
            if (owned.size != before) ExceptionOwnershipExpansion.CHANGED else ExceptionOwnershipExpansion.UNCHANGED
        },
    )

    /**
     * Collects a contained handler until it rejoins already-owned synchronized flow. Exception
     * handlers are not normally dominated by monitorenter in the normal CFG, so applying the normal
     * monitor-dominance test here would reject every genuine rejoining catch.
     */
    private fun collectRejoiningNestedHandlerBlocks(
        entry: BasicBlockId,
        owned: Set<BasicBlockId>,
        synchronizedHandlerBlocks: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        rejectionTrace: MutableList<String>? = null,
        context: String = "entry=$entry",
    ): Set<BasicBlockId>? {
        val result = linkedSetOf<BasicBlockId>()
        val pending = ArrayDeque<BasicBlockId>()
        var rejoinsOwnedFlow = false
        pending += entry

        while (pending.isNotEmpty()) {
            val block = pending.removeFirst()
            if (block in owned) {
                rejoinsOwnedFlow = true
                continue
            }
            if (block in synchronizedHandlerBlocks || !result.add(block)) continue
            if (block in facts.explicitTerminalBlocks) {
                rejectionTrace?.add("fragmented:nested-terminal $context block=$block")
                return null
            }

            for (edge in facts.outgoing[block].orEmpty()) {
                when (edge.to) {
                    in owned -> rejoinsOwnedFlow = true
                    in synchronizedHandlerBlocks -> {
                        rejectionTrace?.add(
                            "fragmented:nested-enters-cleanup $context block=$block target=${edge.to} kind=${edge.kind}",
                        )
                        return null
                    }
                    else -> pending += edge.to
                }
            }
        }
        if (!rejoinsOwnedFlow) {
            rejectionTrace?.add("fragmented:nested-no-rejoin $context blocks=${result.size}")
            return null
        }
        return result
    }

    private fun handlerMonitorSlots(instructions: List<RawInstruction>, handlerStart: Int): Set<Int> {
        val first = instructions.getOrNull(handlerStart) as? RawLocalInstruction ?: return emptySet()
        val monitorLoadIndex = if (first.operation == LocalOperation.STORE && first.type == JvmComputationalType.REFERENCE) {
            handlerStart + 1
        } else {
            handlerStart
        }
        val monitorLoad = instructions.getOrNull(monitorLoadIndex) as? RawLocalInstruction ?: return emptySet()
        if (monitorLoad.operation != LocalOperation.LOAD || monitorLoad.type != JvmComputationalType.REFERENCE) return emptySet()
        val monitorExit = instructions.getOrNull(monitorLoadIndex + 1) as? RawMonitorInstruction ?: return emptySet()
        if (monitorExit.opcode.mnemonic != "monitorexit") return emptySet()
        return setOf(monitorLoad.slot)
    }

    /**
     * Monitor ownership must survive exception-handler rejoin edges. Normal-only dominators treat
     * each handler as an independent root, which incorrectly drops the enclosing monitorenter from
     * every block after the rejoin. Add only exception edges back to the already-pruned normal CFG.
     */
    private fun exceptionAwareDominators(
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): Map<BasicBlockId, Set<BasicBlockId>>? {
        val entry = graph.entryBlock?.takeIf { it in facts.blocks } ?: return null
        val edges = buildList {
            addAll(facts.normalEdges)
            graph.edges.asSequence()
                .filter { edge ->
                    edge.kind == ControlFlowEdgeKind.EXCEPTION &&
                        edge.from in facts.blocks &&
                        edge.to in facts.blocks
                }
                .forEach(::add)
        }
        val predecessors = edges.groupBy { it.to }.mapValues { (_, incoming) ->
            incoming.map { it.from }.distinct()
        }
        return dominators(facts.blocks, entry, predecessors)
    }

    private fun owningMonitorEnter(
        instructions: List<RawInstruction>,
        monitorSlot: Int,
        block: BasicBlockId,
        dominators: Map<BasicBlockId, Set<BasicBlockId>>,
        facts: ControlFlowFacts,
    ): Int? = instructions.indices.asSequence()
        .filter { index ->
            val monitor = instructions[index] as? RawMonitorInstruction ?: return@filter false
            if (monitor.opcode.mnemonic != "monitorenter") return@filter false
            if (monitorSlotBeforeEnter(instructions, index) != monitorSlot) return@filter false
            val enterBlock = facts.instructionToBlock.getOrNull(index) ?: return@filter false
            enterBlock in dominators[block].orEmpty()
        }
        .maxOrNull()

    private fun findNormalMonitorExits(
        instructions: List<RawInstruction>,
        monitorSlot: Int,
        monitorEnterInstructionIndex: Int,
        handlerMonitorExitInstructionIndices: Set<Int>,
        dominators: Map<BasicBlockId, Set<BasicBlockId>>,
        facts: ControlFlowFacts,
    ): List<Int> = instructions.indices.asSequence()
        .filter { index -> index > monitorEnterInstructionIndex && index !in handlerMonitorExitInstructionIndices }
        .filter { index ->
            val monitor = instructions[index] as? RawMonitorInstruction ?: return@filter false
            if (monitor.opcode.mnemonic != "monitorexit") return@filter false
            val load = instructions.getOrNull(index - 1) as? RawLocalInstruction ?: return@filter false
            if (load.operation != LocalOperation.LOAD || load.slot != monitorSlot) return@filter false
            val block = facts.instructionToBlock.getOrNull(index) ?: return@filter false
            owningMonitorEnter(instructions, monitorSlot, block, dominators, facts) == monitorEnterInstructionIndex
        }
        .toList()

    private fun collectBodyBlocks(
        header: BasicBlockId,
        handlerBlocks: Set<BasicBlockId>,
        normalMonitorExitInstructionIndices: List<Int>,
        monitorEnterInstructionIndex: Int,
        monitorSlot: Int,
        instructions: List<RawInstruction>,
        dominators: Map<BasicBlockId, Set<BasicBlockId>>,
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
            if (block in handlerBlocks || block in result) continue
            if (owningMonitorEnter(instructions, monitorSlot, block, dominators, facts) != monitorEnterInstructionIndex) continue
            result += block
            if (block in normalExitBlocks) continue

            facts.outgoing[block].orEmpty()
                .asSequence()
                .filter { edge -> edge.kind != ControlFlowEdgeKind.EXCEPTION && edge.to !in handlerBlocks }
                .mapTo(pending) { edge -> edge.to }
        }
        return result
    }

    private fun handlerInstructionIndex(
        handler: RawExceptionHandler,
        graph: ControlFlowGraph,
        facts: ControlFlowFacts,
    ): Int? = graph.code.labels.firstOrNull { label -> label.id == handler.handler }
        ?.instructionIndex
        ?.takeIf { index -> facts.instructionToBlock.getOrNull(index) != null }

    private data class MonitorCleanupCopy(
        val entry: BasicBlockId,
        val handlerStart: Int,
        val shape: MonitorCleanupHandlerShape,
    )
}
