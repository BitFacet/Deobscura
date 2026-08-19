package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.LocalOperation
import io.github.relvl.deobscura.raw.RawExceptionHandler
import io.github.relvl.deobscura.raw.RawLocalInstruction
import io.github.relvl.deobscura.raw.RawMonitorInstruction
import java.util.*

/**
 * Recognizes compiler-generated monitor cleanup as a source-level `synchronized` region.
 *
 * This is deliberately separate from generic catch-all/finally recognition: monitor cleanup has
 * stronger bytecode invariants and maps to its own source construct rather than `try/finally`.
 */
internal object SynchronizedExceptionRecognizer {
    /** Proves matching monitorenter/monitorexit cleanup and returns the source synchronized body. */
    fun recognize(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        groupedHandlers: Map<BasicBlockId?, List<RawExceptionHandler>>,
        allGroups: List<ExceptionGroupTopology>,
        facts: ControlFlowFacts,
    ): SynchronizedRecognition? {
        val group = topology.group
        if (group.handlers.size != 1 || group.handlers.single().catchType != null) {
            return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        }
        if (groupedHandlers.size != 1) {
            return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        }

        val instructions = graph.code.instructions
        val start = group.envelope.start
        if (start < 2) return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        val monitorEnterInstructionIndex = start - 1
        val monitorEnter = instructions.getOrNull(monitorEnterInstructionIndex) as? RawMonitorInstruction
            ?: return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        if (monitorEnter.opcode.mnemonic != "monitorenter") {
            return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        }
        val monitorSlot = monitorSlotBeforeEnter(instructions, monitorEnterInstructionIndex)
            ?: return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)

        val handlerEntry = groupedHandlers.keys.single() ?: return null
        val handlerStart = graph.block(handlerEntry).startInstructionIndex
        val handlerShape = recognizeHandlerShape(instructions, handlerStart, monitorSlot) ?: return null

        val normalExitIndices = mutableListOf<Int>()
        for (index in start until group.envelope.endExclusive) {
            if (index == handlerShape.monitorExitInstructionIndex) continue
            val exit = instructions[index] as? RawMonitorInstruction ?: continue
            if (exit.opcode.mnemonic != "monitorexit") return null
            val load = instructions.getOrNull(index - 1) as? RawLocalInstruction ?: return null
            if (load.operation != LocalOperation.LOAD || load.slot != monitorSlot) return null
            normalExitIndices += index
        }
        if (normalExitIndices.isEmpty()) {
            return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        }

        val handlerInstructionIndices = handlerStart..handlerShape.throwInstructionIndex
        val handlerBlocks = handlerInstructionIndices.mapNotNullTo(linkedSetOf()) { index ->
            facts.instructionToBlock.getOrNull(index)
        }
        if (handlerBlocks.isEmpty()) return null

        val sourceProtectedBlocks = topology.protectedBlocks - handlerBlocks
        if (sourceProtectedBlocks.isEmpty()) return null
        val bodyBlocks = collectBodyBlocks(
            header = header,
            protectedBlocks = sourceProtectedBlocks,
            normalMonitorExitInstructionIndices = normalExitIndices,
            facts = facts,
        )
        if (bodyBlocks.isEmpty()) return null
        if (hasExternalProtectedEntry(header, bodyBlocks, facts)) {
            // A nested catch may rejoin the synchronized body and therefore look like an external
            // normal predecessor to the strict single-range proof. The fragmented recognizer owns
            // the exception-aware closure needed for that shape.
            return FragmentedSynchronizedRecognizer.recognize(graph, topology, allGroups, facts)
        }

        val monitorEnterBlock = facts.instructionToBlock.getOrNull(monitorEnterInstructionIndex) ?: return null
        val cleanupCompanions = allGroups.filter { candidate ->
            candidate !== topology &&
                candidate.group.handlers.size == 1 &&
                candidate.group.handlers.single().catchType == null &&
                candidate.group.envelope.start == handlerStart &&
                candidate.group.envelope.endExclusive == handlerShape.monitorExitInstructionIndex + 1 &&
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
            handlerMonitorExitInstructionIndex = handlerShape.monitorExitInstructionIndex,
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


    private fun collectBodyBlocks(
        header: BasicBlockId,
        protectedBlocks: Set<BasicBlockId>,
        normalMonitorExitInstructionIndices: List<Int>,
        facts: ControlFlowFacts
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
}

/** Recognized synchronized region plus companion cleanup groups consumed with it. */
internal data class SynchronizedRecognition(
    val region: StructuredRegion.Synchronized,
    val consumedGroupKeys: Set<ExceptionRegionKey>,
)
