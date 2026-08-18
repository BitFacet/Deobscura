package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind

/** Classifies already-discovered region boundary edges without deciding region ownership. */
internal class RegionTransferClassifier {
    fun classifySwitchCaseTransfers(
        caseBlocks: Set<BasicBlockId>,
        entry: BasicBlockId,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        loopContext: NaturalLoopFlowContext?,
    ): List<StructuredRegionTransfer>? {
        if (caseBlocks.isEmpty()) {
            return when {
                entry == continuation -> listOf(StructuredRegionTransfer(header, continuation, StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION))
                loopContext?.exit == entry -> listOf(StructuredRegionTransfer(header, entry, StructuredRegionTransferKind.BREAK_LOOP))
                entry in loopContext?.continueTargets.orEmpty() -> listOf(StructuredRegionTransfer(header, entry, StructuredRegionTransferKind.CONTINUE_LOOP))
                else -> emptyList()
            }
        }

        val boundaryEdges = caseBlocks.sortedBy { it.value }.flatMap { block -> outgoing[block].orEmpty().distinctTargets().filter { edge -> edge.to !in caseBlocks } }
        val transfers = mutableListOf<StructuredRegionTransfer>()
        caseBlocks.sortedBy { it.value }.forEach { block ->
            if (block in explicitTerminalBlocks) {
                transfers += StructuredRegionTransfer(block, kind = StructuredRegionTransferKind.RETURN_OR_THROW)
            }
        }
        boundaryEdges.forEach { edge ->
            val transfer = classifyBoundaryEdge(
                edge = edge,
                entry = entry,
                header = header,
                continuation = continuation,
                caseEntries = caseEntries,
                explicitTerminalBlocks = explicitTerminalBlocks,
                loopContext = loopContext,
                hasAlternativePath = outgoing[edge.from].orEmpty().distinctTargets().any { other -> other.to != edge.to && (other.to in caseBlocks || other.to in caseEntries) },
            ) ?: return null
            transfers += transfer
        }

        return transfers.distinct().sortedWith(
            compareBy<StructuredRegionTransfer> { it.from.value }
                .thenBy { it.target?.value ?: Int.MAX_VALUE }
                .thenBy { it.kind.ordinal },
        )
    }

    private fun classifyBoundaryEdge(
        edge: ControlFlowEdge,
        entry: BasicBlockId,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        loopContext: NaturalLoopFlowContext?,
        hasAlternativePath: Boolean,
    ): StructuredRegionTransfer? {
        val target = edge.to
        if (target == header) return null
        if (target == continuation) {
            val kind = if (edge.kind == ControlFlowEdgeKind.JUMP || (edge.kind == ControlFlowEdgeKind.CONDITIONAL && hasAlternativePath)) {
                StructuredRegionTransferKind.BREAK_SWITCH
            } else {
                StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION
            }
            return StructuredRegionTransfer(edge.from, target, kind)
        }
        if (target in caseEntries && target != entry) {
            return StructuredRegionTransfer(edge.from, target, StructuredRegionTransferKind.CASE_FALLTHROUGH)
        }
        if (loopContext != null && target == loopContext.exit) {
            return StructuredRegionTransfer(edge.from, target, StructuredRegionTransferKind.BREAK_LOOP)
        }
        if (loopContext != null && target in loopContext.continueTargets) {
            return StructuredRegionTransfer(edge.from, target, StructuredRegionTransferKind.CONTINUE_LOOP)
        }
        if (target in explicitTerminalBlocks) {
            return StructuredRegionTransfer(edge.from, target, StructuredRegionTransferKind.RETURN_OR_THROW)
        }
        return null
    }
}
