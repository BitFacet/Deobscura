package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.expression.BranchCondition

/**
 * Source-oriented control-flow regions recognized from the canonical SSA CFG.
 *
 * This layer is deliberately non-destructive: the canonical block graph remains the source of
 * truth and regions merely describe reducible structures that have been proven from it. This lets
 * later source reconstruction consume structured regions while still falling back to labels/gotos
 * for anything not recognized yet.
 */
data class StructuredControlFlowAnalysis(
    val regions: List<StructuredRegion>,
    val conditionalBranchCount: Int,
    val switchCount: Int,
    /** Compiler-generated boolean materialization diamonds folded into their consuming condition. */
    val booleanConditionFolds: List<BooleanConditionFold> = emptyList(),
    /** Headers intentionally left in block/goto form, with the first proven rejection reason. */
    val unstructured: List<UnstructuredControlFlowDiagnostic> = emptyList(),
) {
    val regionHeaders: Set<BasicBlockId> = regions.mapTo(linkedSetOf()) { it.header }
    val unstructuredConditionalCount: Int
        get() = unstructured.count { it.kind == UnstructuredControlFlowKind.CONDITIONAL }
}

/**
 * A source condition that was materialized as a 0/1 diamond and immediately consumed by another
 * branch. The producer diamond remains present in the canonical CFG but is not treated as a source
 * `if` region.
 */
data class BooleanConditionFold(
    val producerHeader: BasicBlockId,
    val consumerHeader: BasicBlockId,
    val phiValue: ValueId,
    val condition: BranchCondition,
    val materializationBlocks: Set<BasicBlockId>,
)

enum class UnstructuredControlFlowKind {
    CONDITIONAL,
    SWITCH,
}

enum class UnstructuredControlFlowReason(val diagnosticName: String) {
    SWITCH_DEFERRED("switch-deferred"),
    MISSING_BRANCH_EDGES("missing-conditional-or-fallthrough-edge"),
    IDENTICAL_SUCCESSORS("identical-successors"),
    NO_COMMON_POST_DOMINATOR("no-common-post-dominator"),
    INVALID_JOIN("invalid-join"),
    TERMINAL_ARM("terminal-arm"),
    ARM_REENTERS_HEADER("arm-reenters-header"),
    ARM_LEAVES_REACHABLE_FLOW("arm-leaves-reachable-flow"),
    ARM_HAS_OTHER_EXIT("arm-has-non-join-exit"),
    OVERLAPPING_ARMS("overlapping-arms"),
    EXTERNAL_ARM_ENTRY("arm-has-external-entry"),
    LOOP_NOT_TWO_SUCCESSORS("loop-header-not-two-successors"),
    LOOP_BODY_EXIT_SHAPE("loop-body-exit-shape"),
    LOOP_HAS_ADDITIONAL_EXIT("loop-has-additional-exit"),
    LOOP_HAS_EXTERNAL_ENTRY("loop-body-has-external-entry"),
    LOOP_MISSING_CONDITIONAL_EDGE("loop-missing-conditional-edge"),
    UNSUPPORTED_SHAPE("unsupported-shape"),
}

data class UnstructuredControlFlowDiagnostic(
    val header: BasicBlockId,
    val kind: UnstructuredControlFlowKind,
    val reason: UnstructuredControlFlowReason,
)

sealed interface StructuredRegion {
    val header: BasicBlockId
    val coveredBlocks: Set<BasicBlockId>

    data class If(
        override val header: BasicBlockId,
        val condition: BranchCondition,
        val thenEntry: BasicBlockId?,
        val thenBlocks: Set<BasicBlockId>,
        val elseEntry: BasicBlockId?,
        val elseBlocks: Set<BasicBlockId>,
        val join: BasicBlockId,
    ) : StructuredRegion {
        override val coveredBlocks: Set<BasicBlockId> = linkedSetOf<BasicBlockId>().apply {
            add(header)
            addAll(thenBlocks)
            addAll(elseBlocks)
        }
    }

    data class While(
        override val header: BasicBlockId,
        val condition: BranchCondition,
        /** True when the JVM branch condition denotes leaving the loop rather than entering it. */
        val negateCondition: Boolean,
        val bodyEntry: BasicBlockId,
        val bodyBlocks: Set<BasicBlockId>,
        val exit: BasicBlockId,
        val latches: Set<BasicBlockId>,
    ) : StructuredRegion {
        override val coveredBlocks: Set<BasicBlockId> = linkedSetOf<BasicBlockId>().apply {
            add(header)
            addAll(bodyBlocks)
        }
    }
}

class StructuredControlFlowInconsistencyException(message: String) : IllegalStateException(message)
