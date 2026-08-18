package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import java.util.*

/** Recognizes switch ownership/continuations; transfer semantics are delegated separately. */
internal class StructuredSwitchRecognizer(private val transferClassifier: RegionTransferClassifier = RegionTransferClassifier()) {
    fun recognize(facts: ControlFlowFacts, loopContexts: List<NaturalLoopFlowContext>): SwitchRecognition {
        val regions = mutableListOf<StructuredRegion.Switch>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()

        facts.switches.forEach { (header, statement) ->
            val switchEdges = facts.outgoing[header].orEmpty().filter { it.kind == ControlFlowEdgeKind.SWITCH }
            if (switchEdges.isEmpty() || switchEdges.none { it.switchValue == null }) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_MISSING_EDGES
                return@forEach
            }

            val entries = switchEdges.mapTo(linkedSetOf()) { it.to }
            val containingLoop = loopContexts
                .filter { it.contains(header) || it.header == header }
                .minByOrNull { it.blocks.size }
            val continuation = switchContinuation(header, entries, facts, containingLoop)

            val collected = linkedMapOf<BasicBlockId, Set<BasicBlockId>>()
            for (entry in entries) {
                if (entry == continuation) {
                    collected[entry] = emptySet()
                    continue
                }
                when (val result = collectSwitchCase(
                    start = entry,
                    header = header,
                    continuation = continuation,
                    caseEntries = entries,
                    facts = facts,
                    boundaryTargets = containingLoop?.let { context ->
                        context.continueTargets + listOfNotNull(context.exit)
                    }.orEmpty(),
                )) {
                    is SwitchCaseCollection.Success -> collected[entry] = result.blocks
                    is SwitchCaseCollection.Rejected -> {
                        rejections[header] = result.reason
                        break
                    }
                }
            }
            if (header in rejections) return@forEach

            detachSharedTerminalBlocks(
                collected = collected,
                header = header,
                continuation = continuation,
                caseEntries = entries,
                predecessors = facts.predecessors,
                explicitTerminalBlocks = facts.explicitTerminalBlocks,
            )

            val nonEmptyCaseBlocks = collected.values.filter { it.isNotEmpty() }
            for (i in nonEmptyCaseBlocks.indices) {
                for (j in i + 1 until nonEmptyCaseBlocks.size) {
                    if (nonEmptyCaseBlocks[i].intersect(nonEmptyCaseBlocks[j]).isNotEmpty()) {
                        rejections[header] = UnstructuredControlFlowReason.SWITCH_OVERLAPPING_CASES
                        return@forEach
                    }
                }
            }

            val allCaseBlocks = collected.values.flatten().toSet()
            val allowedEntryPredecessors = allCaseBlocks + header
            val hasExternalEntry = collected.any { (entry, caseBlocks) ->
                caseBlocks.any { block ->
                    facts.predecessors[block].orEmpty().any { predecessor ->
                        predecessor !in allowedEntryPredecessors && !(block == entry && predecessor in entries)
                    }
                }
            }
            if (hasExternalEntry) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_EXTERNAL_ENTRY
                return@forEach
            }

            val cases = switchEdges.groupBy { it.to }.map { (entry, edges) ->
                val labels = edges.mapNotNull { it.switchValue }.distinct().sorted()
                val isDefault = edges.any { it.switchValue == null }
                val caseBlocks = collected.getValue(entry)
                val transfers = transferClassifier.classifySwitchCaseTransfers(
                    caseBlocks = caseBlocks,
                    entry = entry,
                    header = header,
                    continuation = continuation,
                    caseEntries = entries,
                    outgoing = facts.outgoing,
                    explicitTerminalBlocks = facts.explicitTerminalBlocks,
                    loopContext = containingLoop,
                ) ?: if (caseBlocks.isEmpty() && entry == continuation) {
                    listOf(
                        StructuredRegionTransfer(
                            from = entry,
                            target = continuation,
                            kind = StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION,
                        ),
                    )
                } else {
                    rejections[header] = UnstructuredControlFlowReason.SWITCH_UNSUPPORTED_EXIT
                    return@forEach
                }
                if (transfers.isEmpty()) {
                    rejections[header] = UnstructuredControlFlowReason.SWITCH_UNSUPPORTED_EXIT
                    return@forEach
                }
                StructuredSwitchCase(
                    labels = labels,
                    isDefault = isDefault,
                    entry = entry,
                    blocks = caseBlocks,
                    transfers = transfers,
                )
            }.sortedWith(compareBy<StructuredSwitchCase> { it.entry.value }.thenBy { it.labels.firstOrNull() ?: Int.MAX_VALUE })

            if (header in rejections) return@forEach
            if (continuation == null && !switchCasesNeedNoContinuation(cases)) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_NO_CONTINUATION
                return@forEach
            }

            regions += StructuredRegion.Switch(
                header = header,
                selector = statement.selector,
                cases = cases,
                continuation = continuation,
            )
        }

        return SwitchRecognition(regions, rejections)
    }

    private fun switchCasesNeedNoContinuation(cases: List<StructuredSwitchCase>): Boolean {
        val byEntry = cases.associateBy { it.entry }
        val memo = mutableMapOf<BasicBlockId, Boolean>()

        fun exitsSwitch(entry: BasicBlockId, visiting: MutableSet<BasicBlockId>): Boolean {
            memo[entry]?.let { return it }
            if (!visiting.add(entry)) return false
            val transfers = byEntry[entry]?.transfers.orEmpty()
            val result = transfers.isNotEmpty() && transfers.all { transfer ->
                when (transfer.kind) {
                    StructuredRegionTransferKind.RETURN_OR_THROW,
                    StructuredRegionTransferKind.BREAK_LOOP,
                    StructuredRegionTransferKind.CONTINUE_LOOP,
                        -> true

                    StructuredRegionTransferKind.CASE_FALLTHROUGH ->
                        transfer.target?.let { target -> target in byEntry && exitsSwitch(target, visiting) } == true

                    StructuredRegionTransferKind.BREAK_SWITCH,
                    StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION,
                        -> false
                }
            }
            visiting.remove(entry)
            memo[entry] = result
            return result
        }

        return cases.all { exitsSwitch(it.entry, linkedSetOf()) }
    }

    private fun switchContinuation(header: BasicBlockId, entries: Set<BasicBlockId>, facts: ControlFlowFacts, containingLoop: NaturalLoopFlowContext?): BasicBlockId? {
        fun isValidContinuation(candidate: BasicBlockId): Boolean = (containingLoop == null || candidate in containingLoop.blocks) &&
            !reachesOtherCaseEntryBeforeHeader(candidate, header, entries, facts.outgoing)

        immediatePostDominator(header, facts.postDominators)?.let { candidate -> if (isValidContinuation(candidate)) return candidate }

        val reachableFromHeader = reachableFrom(header, facts.outgoing)
        val externallySharedEntries = entries.filter { candidate ->
            isValidContinuation(candidate)
                && facts.incoming[candidate].orEmpty().any { edge -> edge.from != header && edge.from !in reachableFromHeader }
        }
        if (externallySharedEntries.size == 1) return externallySharedEntries.single()

        val reachableSupport = linkedMapOf<BasicBlockId, Int>()
        entries.forEach { entry ->
            reachableUntilCaseOrTerminal(
                start = entry,
                caseEntries = entries,
                outgoing = facts.outgoing,
                explicitTerminalBlocks = facts.explicitTerminalBlocks,
            ).forEach { candidate ->
                if (candidate != header && candidate !in entries && isValidContinuation(candidate)) {
                    reachableSupport[candidate] = reachableSupport.getOrDefault(candidate, 0) + 1
                }
            }
        }
        val maxReachableSupport = reachableSupport.values.maxOrNull() ?: 0
        if (maxReachableSupport >= 2) {
            val candidates = reachableSupport.filterValues { it == maxReachableSupport }.keys
            val earliest = candidates.filter { candidate ->
                val reachableBeforeHeader = reachableBeforeHeader(candidate, header, facts.outgoing)
                candidates.all { other -> other == candidate || other in reachableBeforeHeader }
            }
            if (earliest.size == 1) return earliest.single()
        }

        val externallySharedJoins = reachableFromHeader.filter { candidate ->
            candidate != header &&
                candidate !in entries &&
                isValidContinuation(candidate) &&
                candidate !in facts.explicitTerminalBlocks &&
                header !in reachableFrom(candidate, facts.outgoing) &&
                facts.incoming[candidate].orEmpty().any { edge -> edge.from !in reachableFromHeader }
        }
        if (externallySharedJoins.size == 1) return externallySharedJoins.single()

        val support = linkedMapOf<BasicBlockId, Int>()
        entries.forEach { entry ->
            facts.postDominators[entry].orEmpty().forEach { candidate ->
                val isDirectSwitchEntry = candidate in entries
                val hasBreakLikeIncoming = facts.incoming[candidate].orEmpty().any { edge ->
                    edge.from != header && edge.kind == ControlFlowEdgeKind.JUMP
                }
                if (
                    candidate != header &&
                    isValidContinuation(candidate) &&
                    (!isDirectSwitchEntry || hasBreakLikeIncoming)
                ) {
                    support[candidate] = support.getOrDefault(candidate, 0) + 1
                }
            }
        }
        val maxSupport = support.values.maxOrNull() ?: return null
        if (maxSupport < 2) return null
        val candidates = support.filterValues { it == maxSupport }.keys
        return candidates.firstOrNull { candidate ->
            candidates.none { other ->
                other != candidate && candidate in facts.postDominators[other].orEmpty()
            }
        }
    }

    private fun reachesOtherCaseEntryBeforeHeader(
        start: BasicBlockId,
        header: BasicBlockId,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): Boolean {
        val visited = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        outgoing[start].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == header || !visited.add(block)) continue
            if (block in caseEntries && block != start) return true
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return false
    }

    private fun reachableUntilCaseOrTerminal(
        start: BasicBlockId,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block != start && block in caseEntries) continue
            if (!result.add(block)) continue
            if (block in explicitTerminalBlocks) continue
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return result
    }

    private fun reachableBeforeHeader(start: BasicBlockId, header: BasicBlockId, outgoing: Map<BasicBlockId, List<ControlFlowEdge>>): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == header || !result.add(block)) continue
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return result
    }

    private fun reachableFrom(start: BasicBlockId, outgoing: Map<BasicBlockId, List<ControlFlowEdge>>): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (!result.add(block)) continue
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return result
    }

    private fun collectSwitchCase(
        start: BasicBlockId,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        facts: ControlFlowFacts,
        boundaryTargets: Set<BasicBlockId>,
    ): SwitchCaseCollection {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == continuation || (block != start && block in caseEntries) || block in boundaryTargets) continue
            if (block == header) return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in facts.blocks) return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue

            val successors = facts.outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) {
                if (block !in facts.explicitTerminalBlocks) {
                    return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.SWITCH_UNSUPPORTED_EXIT)
                }
                continue
            }
            successors.forEach { successor ->
                if (
                    successor != continuation &&
                    successor !in boundaryTargets &&
                    (successor == start || successor !in caseEntries)
                ) {
                    queue.addLast(successor)
                }
            }
        }
        return SwitchCaseCollection.Success(result)
    }

    private fun detachSharedTerminalBlocks(
        collected: MutableMap<BasicBlockId, Set<BasicBlockId>>,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ) {
        val allCaseBlocks = collected.values.flatten().toSet()
        val owners = linkedMapOf<BasicBlockId, MutableSet<BasicBlockId>>()
        collected.forEach { (entry, caseBlocks) ->
            caseBlocks.forEach { block ->
                if (block in explicitTerminalBlocks) {
                    owners.getOrPut(block) { linkedSetOf() } += entry
                }
            }
        }

        val sharedTerminalBlocks = owners.filter { (block, blockOwners) ->
            block != continuation &&
                block !in caseEntries &&
                (
                    blockOwners.size > 1 ||
                        predecessors[block].orEmpty().any { predecessor ->
                            predecessor != header && predecessor !in allCaseBlocks
                        }
                    )
        }.keys
        if (sharedTerminalBlocks.isEmpty()) return

        collected.entries.forEach { entry ->
            if (entry.value.any { it in sharedTerminalBlocks }) {
                entry.setValue(entry.value.filterTo(linkedSetOf()) { it !in sharedTerminalBlocks })
            }
        }
    }
}

internal data class SwitchRecognition(
    val regions: List<StructuredRegion.Switch>,
    val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
)

private sealed interface SwitchCaseCollection {
    data class Success(val blocks: Set<BasicBlockId>) : SwitchCaseCollection
    data class Rejected(val reason: UnstructuredControlFlowReason) : SwitchCaseCollection
}
