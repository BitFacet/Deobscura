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
    /** Linear JVM branch chains folded into source-level short-circuit boolean conditions. */
    val shortCircuitConditionFolds: List<ShortCircuitConditionFold> = emptyList(),
    /** Regular if regions whose empty conditional arm was normalized by inverting the condition. */
    val emptyArmNormalizationCount: Int = 0,
    /** If regions recognized because one source arm terminates instead of reaching a common join. */
    val terminalIfRegionCount: Int = 0,
    /** If regions whose source arm is proven to continue the innermost containing loop. */
    val continueIfRegionCount: Int = 0,
    /** If regions whose source arm is proven to break from the innermost containing loop. */
    val breakIfRegionCount: Int = 0,
    /** If regions reconstructed from sequential regions inside a natural-loop body. */
    val loopBodyIfRegionCount: Int = 0,
    /** Loop-transfer ifs whose normal continuation was recovered from bytecode region layout. */
    val loopContinuationIfRegionCount: Int = 0,
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

/**
 * A linear chain of conditional blocks that implements Java short-circuit evaluation. The
 * canonical CFG is preserved, while the structured view treats the root as one compound branch.
 */
data class ShortCircuitConditionFold(
    val rootHeader: BasicBlockId,
    val foldedHeaders: Set<BasicBlockId>,
    val condition: StructuredCondition,
    val conditionalTarget: BasicBlockId,
    val fallthroughTarget: BasicBlockId,
)

sealed interface StructuredCondition {
    data class Atomic(val condition: BranchCondition) : StructuredCondition
    data class And(val terms: List<StructuredCondition>) : StructuredCondition
    data class Or(val terms: List<StructuredCondition>) : StructuredCondition
}

enum class UnstructuredControlFlowKind {
    CONDITIONAL,
    SWITCH,
}

enum class UnstructuredControlFlowReason(val diagnosticName: String) {
    SWITCH_MISSING_EDGES("switch-missing-edges"),
    SWITCH_NO_CONTINUATION("switch-no-common-continuation"),
    SWITCH_EXTERNAL_ENTRY("switch-case-has-external-entry"),
    SWITCH_OVERLAPPING_CASES("switch-overlapping-case-regions"),
    SWITCH_UNSUPPORTED_EXIT("switch-unsupported-exit-shape"),
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

enum class StructuredArmExitKind {
    RETURN_OR_THROW,
    CONTINUE,
    BREAK,
}

enum class StructuredRegionTransferKind {
    /** Transfer from this case body into another case body. */
    CASE_FALLTHROUGH,
    /** Explicit `break` from this switch to its continuation. */
    BREAK_SWITCH,
    /** Natural completion of this case at the switch continuation. */
    NORMAL_SWITCH_COMPLETION,
    /** Transfer from this switch to the canonical exit of an enclosing loop. */
    BREAK_LOOP,
    /** Transfer from this switch to a proven continue target of an enclosing loop. */
    CONTINUE_LOOP,
    /** A return or throw terminates this path. */
    RETURN_OR_THROW,
}

data class StructuredRegionTransfer(
    val from: BasicBlockId,
    val target: BasicBlockId? = null,
    val kind: StructuredRegionTransferKind,
)

/**
 * Legacy single-exit projection kept while downstream rendering migrates to region transfers.
 * A case with multiple boundary transfers intentionally has no single `exit`.
 */
enum class StructuredSwitchCaseExitKind {
    BREAK,
    NORMAL,
    FALLTHROUGH,
    CONTINUE,
    RETURN_OR_THROW,
}

data class StructuredSwitchCaseExit(
    val kind: StructuredSwitchCaseExitKind,
    val target: BasicBlockId? = null,
)

data class StructuredSwitchCase(
    val labels: List<Int>,
    val isDefault: Boolean,
    val entry: BasicBlockId,
    val blocks: Set<BasicBlockId>,
    val transfers: List<StructuredRegionTransfer> = emptyList(),
) {
    val exit: StructuredSwitchCaseExit?
        get() = transfers.singleOrNull()?.toLegacySwitchExit()
}

private fun StructuredRegionTransfer.toLegacySwitchExit(): StructuredSwitchCaseExit? = when (kind) {
    StructuredRegionTransferKind.CASE_FALLTHROUGH -> StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.FALLTHROUGH, target)
    StructuredRegionTransferKind.BREAK_SWITCH -> StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.BREAK, target)
    StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION -> StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.NORMAL, target)
    StructuredRegionTransferKind.CONTINUE_LOOP -> StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.CONTINUE, target)
    StructuredRegionTransferKind.RETURN_OR_THROW -> StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.RETURN_OR_THROW, target)
    StructuredRegionTransferKind.BREAK_LOOP -> null
}

data class StructuredArmExit(
    val kind: StructuredArmExitKind,
    /** Control-flow target represented by CONTINUE/BREAK; null for return/throw. */
    val target: BasicBlockId? = null,
)

sealed interface StructuredRegion {
    val header: BasicBlockId
    val coveredBlocks: Set<BasicBlockId>

    data class If(
        override val header: BasicBlockId,
        val condition: StructuredCondition,
        val thenEntry: BasicBlockId?,
        val thenBlocks: Set<BasicBlockId>,
        val elseEntry: BasicBlockId?,
        val elseBlocks: Set<BasicBlockId>,
        /** First block executed after the if when control continues normally. */
        val continuation: BasicBlockId,
        /** Non-fallthrough source transfer performed by the then arm, if any. */
        val thenExit: StructuredArmExit? = null,
        /** Non-fallthrough source transfer performed by the else arm, if any. */
        val elseExit: StructuredArmExit? = null,
        /** True when this local continuation was reconstructed relative to a containing loop. */
        val loopBodyRegional: Boolean = false,
        /** True when the loop's normal continuation was selected from the forward region layout. */
        val loopContinuationSpine: Boolean = false,
    ) : StructuredRegion {
        override val coveredBlocks: Set<BasicBlockId> = linkedSetOf<BasicBlockId>().apply {
            add(header)
            addAll(thenBlocks)
            addAll(elseBlocks)
        }
    }

    data class Switch(
        override val header: BasicBlockId,
        val selector: ValueId,
        val cases: List<StructuredSwitchCase>,
        /** First block executed after the switch when control continues normally. */
        val continuation: BasicBlockId?,
    ) : StructuredRegion {
        override val coveredBlocks: Set<BasicBlockId> = linkedSetOf<BasicBlockId>().apply {
            add(header)
            cases.forEach { addAll(it.blocks) }
        }
    }

    data class While(
        override val header: BasicBlockId,
        val condition: StructuredCondition,
        /** True when the JVM branch condition denotes leaving the loop rather than entering it. */
        val negateCondition: Boolean,
        val bodyEntry: BasicBlockId,
        val bodyBlocks: Set<BasicBlockId>,
        val exit: BasicBlockId,
        val latches: Set<BasicBlockId>,
        /** Body edges that leave the loop through its canonical source-level exit. */
        val breakEdges: Set<Pair<BasicBlockId, BasicBlockId>> = emptySet(),
    ) : StructuredRegion {
        override val coveredBlocks: Set<BasicBlockId> = linkedSetOf<BasicBlockId>().apply {
            add(header)
            addAll(bodyBlocks)
        }
    }
}

class StructuredControlFlowInconsistencyException(message: String) : IllegalStateException(message)
