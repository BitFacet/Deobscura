package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind

/** Classifies already-discovered region boundary edges without deciding region ownership. */
internal class RegionTransferClassifier {
    fun classifySwitchCaseExit(
        caseBlocks: Set<BasicBlockId>,
        entry: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        continueTargets: Set<BasicBlockId>,
    ): StructuredSwitchCaseExit? {
        if (caseBlocks.isEmpty()) return null
        val exits = caseBlocks.flatMap { block ->
            outgoing[block].orEmpty().distinctTargets().filter { edge -> edge.to !in caseBlocks }
        }
        val targets = exits.mapTo(linkedSetOf()) { it.to }

        if (targets.isEmpty()) {
            return if (caseBlocks.any { it in explicitTerminalBlocks }) {
                StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.RETURN_OR_THROW)
            } else null
        }
        if (targets.size != 1) return null
        val target = targets.single()

        if (target in caseEntries && target != entry) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.FALLTHROUGH, target)
        }
        if (target in continueTargets) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.CONTINUE, target)
        }
        if (target == continuation) {
            val kind = if (exits.all { it.kind == ControlFlowEdgeKind.JUMP }) {
                StructuredSwitchCaseExitKind.BREAK
            } else {
                StructuredSwitchCaseExitKind.NORMAL
            }
            return StructuredSwitchCaseExit(kind, target)
        }
        if (target in explicitTerminalBlocks) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.RETURN_OR_THROW, target)
        }
        return null
    }
}
