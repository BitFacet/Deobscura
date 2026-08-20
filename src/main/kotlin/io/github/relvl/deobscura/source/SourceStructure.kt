package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowDiagnostic

/**
 * Compositional source-oriented view built from already-proven structured control flow.
 *
 * This is deliberately a projection, not a replacement for CFG/Expression IR/StructuredRegion.
 * Every node retains physical block provenance so later recognition improvements can replace local
 * unresolved fragments without losing the diagnostic path back to the canonical analysis graph.
 */
data class SourceStructureAnalysis(
    val root: SourceBlock,
    /** Reachable canonical blocks represented by source nodes or proven compiler scaffolding. */
    val accountedBlocks: Set<BasicBlockId>,
    /** Physical blocks hidden only in this source projection because their source semantics are represented elsewhere. */
    val consumptions: List<SourceConsumption>,
    /** Projection debt that must remain visible until source ownership is reconstructed precisely. */
    val issues: List<SourceProjectionIssue> = emptyList(),
)

data class SourceBlock(
    val ownedBlocks: Set<BasicBlockId>,
    val nodes: List<SourceNode>,
)

sealed interface SourceNode {
    val provenance: SourceProvenance

    /** A canonical CFG block that has not been absorbed by a proven source construct. */
    data class BasicBlock(
        val block: BasicBlockId,
        override val provenance: SourceProvenance,
    ) : SourceNode

    /** A local marker for control flow that remains intentionally unresolved by current recognizers. */
    data class Unstructured(
        val block: BasicBlockId,
        val diagnostics: List<UnstructuredControlFlowDiagnostic>,
        override val provenance: SourceProvenance,
    ) : SourceNode

    /**
     * Reachable block whose lexical source owner is not known yet. This is deliberately distinct
     * from an unstructured-CFG diagnostic: the lower control-flow proof may still be completely valid.
     */
    data class ProjectionFallback(
        val block: BasicBlockId,
        val reason: SourceProjectionIssueReason,
        override val provenance: SourceProvenance,
    ) : SourceNode

    /** A proven StructuredRegion projected into semantic source parts. */
    data class Structured(
        val region: StructuredRegion,
        val parts: List<SourceRegionPart>,
        val diagnostics: List<UnstructuredControlFlowDiagnostic> = emptyList(),
        override val provenance: SourceProvenance,
    ) : SourceNode
}

data class SourceRegionPart(
    val kind: SourceRegionPartKind,
    val ordinal: Int = 0,
    val label: String? = null,
    val ownedBlocks: Set<BasicBlockId>,
    /** Optional physical instruction ranges selecting the source-bearing part of owned blocks. */
    val instructionRanges: List<IntRange> = emptyList(),
    val body: SourceBlock,
)

enum class SourceRegionPartKind {
    THEN,
    ELSE,
    LOOP_BODY,
    SWITCH_CASE,
    TRY_BODY,
    CATCH_BODY,
    FINALLY_BODY,
    SYNCHRONIZED_BODY,
}

data class SourceProvenance(
    val blocks: Set<BasicBlockId>,
    val instructionRanges: List<IntRange> = emptyList(),
)

data class SourceConsumption(
    val block: BasicBlockId,
    val reason: SourceConsumptionReason,
    val ownerHeader: BasicBlockId,
)

data class SourceProjectionIssue(
    val reason: SourceProjectionIssueReason,
    val blocks: Set<BasicBlockId>,
)

enum class SourceProjectionIssueReason {
    UNACCOUNTED_REACHABLE_BLOCK,
}

enum class SourceConsumptionReason {
    FINALLY_NORMAL_COPY,
    FINALLY_EXCEPTIONAL_SCAFFOLDING,
    SYNCHRONIZED_MONITOR_SCAFFOLDING,
}

class SourceStructureInconsistencyException(message: String) : IllegalStateException(message)
