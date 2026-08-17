package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.StructuredCondition
import io.github.relvl.deobscura.controlflow.StructuredArmExit
import io.github.relvl.deobscura.controlflow.StructuredArmExitKind
import io.github.relvl.deobscura.controlflow.StructuredSwitchCaseExit
import io.github.relvl.deobscura.controlflow.StructuredSwitchCaseExitKind
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.BranchCondition
import io.github.relvl.deobscura.expression.ComparisonOperator

/** Compact diagnostic rendering of source-level regions proven from the canonical CFG. */
class StructuredControlFlowRenderer(
    private val expressionRenderer: ExpressionIrRenderer = ExpressionIrRenderer(),
) {
    fun render(structure: StructuredControlFlowAnalysis, expression: ExpressionAnalysis): String = buildString {
        if (structure.regions.isEmpty() && structure.booleanConditionFolds.isEmpty() &&
            structure.shortCircuitConditionFolds.isEmpty() && structure.unstructured.isEmpty()
        ) {
            appendLine("    <no recognized regions>")
            return@buildString
        }

        structure.booleanConditionFolds.forEach { fold ->
            val condition = expressionRenderer.renderCondition(fold.condition, expression)
            append("    folded-boolean B${fold.producerHeader.value} -> B${fold.consumerHeader.value}")
            append(" via=v${fold.phiValue.value} condition=($condition)")
            appendLine(" materialization=${formatBlocks(fold.materializationBlocks)}")
        }

        structure.shortCircuitConditionFolds.forEach { fold ->
            val condition = renderCondition(fold.condition, expression)
            append("    folded-short-circuit B${fold.rootHeader.value}")
            append(" condition=($condition)")
            append(" folded=${formatBlocks(fold.foldedHeaders)}")
            appendLine(" targets=[B${fold.conditionalTarget.value}, B${fold.fallthroughTarget.value}]")
        }

        structure.regions.forEach { region ->
            when (region) {
                is StructuredRegion.If -> {
                    val condition = renderCondition(region.condition, expression)
                    append("    if B${region.header.value} condition=($condition)")
                    append(" then=${formatBlocks(region.thenBlocks)}")
                    if (region.elseBlocks.isNotEmpty()) append(" else=${formatBlocks(region.elseBlocks)}")
                    append(" continuation=B${region.continuation.value}")
                    if (region.loopBodyRegional) append(" loop-body")
                    if (region.loopContinuationSpine) append(" loop-spine")
                    region.thenExit?.let { append(" then-${renderExit(it)}") }
                    region.elseExit?.let { append(" else-${renderExit(it)}") }
                    appendLine()
                }

                is StructuredRegion.Switch -> {
                    append("    switch B${region.header.value} selector=v${region.selector.value}")
                    region.continuation?.let { append(" continuation=B${it.value}") }
                    appendLine()
                    region.cases.forEach { case ->
                        append("      case ")
                        val labels = buildList {
                            addAll(case.labels.map(Int::toString))
                            if (case.isDefault) add("default")
                        }
                        append(labels.joinToString(","))
                        append(" entry=B${case.entry.value}")
                        append(" blocks=${formatBlocks(case.blocks)}")
                        case.exit?.let { append(" ${renderSwitchExit(it)}") }
                        appendLine()
                    }
                }

                is StructuredRegion.While -> {
                    val condition = renderCondition(if (region.negateCondition) region.condition.negated() else region.condition, expression)
                    append("    while B${region.header.value} condition=($condition)")
                    append(" body=${formatBlocks(region.bodyBlocks)}")
                    append(" exit=B${region.exit.value}")
                    append(" latches=${formatBlocks(region.latches)}")
                    if (region.breakEdges.isNotEmpty()) {
                        append(" breaks=")
                        append(region.breakEdges.joinToString(prefix = "[", postfix = "]") { (from, to) -> "B${from.value}->B${to.value}" })
                    }
                    appendLine()
                }
            }
        }

        if (structure.unstructured.isNotEmpty()) {
            appendLine("    unstructured:")
            structure.unstructured.forEach { diagnostic ->
                val kind = when (diagnostic.kind) {
                    UnstructuredControlFlowKind.CONDITIONAL -> "conditional"
                    UnstructuredControlFlowKind.SWITCH -> "switch"
                }
                appendLine("      B${diagnostic.header.value} $kind: ${diagnostic.reason.diagnosticName}")
            }
        }
    }

    private fun renderCondition(condition: StructuredCondition, expression: ExpressionAnalysis): String = when (condition) {
        is StructuredCondition.Atomic -> expressionRenderer.renderCondition(condition.condition, expression)
        is StructuredCondition.And -> condition.terms.joinToString(" && ") { term ->
            val rendered = renderCondition(term, expression)
            if (term is StructuredCondition.Or) "($rendered)" else rendered
        }
        is StructuredCondition.Or -> condition.terms.joinToString(" || ") { term ->
            val rendered = renderCondition(term, expression)
            if (term is StructuredCondition.And) "($rendered)" else rendered
        }
    }

    private fun StructuredCondition.negated(): StructuredCondition = when (this) {
        is StructuredCondition.Atomic -> copy(condition = condition.negated())
        is StructuredCondition.And -> StructuredCondition.Or(terms.map { it.negated() })
        is StructuredCondition.Or -> StructuredCondition.And(terms.map { it.negated() })
    }

    private fun BranchCondition.negated(): BranchCondition = copy(operator = operator.negated())

    private fun ComparisonOperator.negated(): ComparisonOperator = when (this) {
        ComparisonOperator.EQ -> ComparisonOperator.NE
        ComparisonOperator.NE -> ComparisonOperator.EQ
        ComparisonOperator.LT -> ComparisonOperator.GE
        ComparisonOperator.LE -> ComparisonOperator.GT
        ComparisonOperator.GT -> ComparisonOperator.LE
        ComparisonOperator.GE -> ComparisonOperator.LT
    }

    private fun renderExit(exit: StructuredArmExit): String = when (exit.kind) {
        StructuredArmExitKind.RETURN_OR_THROW -> "terminates"
        StructuredArmExitKind.CONTINUE -> "continue=B${requireNotNull(exit.target).value}"
        StructuredArmExitKind.BREAK -> "break=B${requireNotNull(exit.target).value}"
    }

    private fun renderSwitchExit(exit: StructuredSwitchCaseExit): String = when (exit.kind) {
        StructuredSwitchCaseExitKind.BREAK -> "break=B${requireNotNull(exit.target).value}"
        StructuredSwitchCaseExitKind.NORMAL -> "normal=B${requireNotNull(exit.target).value}"
        StructuredSwitchCaseExitKind.FALLTHROUGH -> "fallthrough=B${requireNotNull(exit.target).value}"
        StructuredSwitchCaseExitKind.CONTINUE -> "continue=B${requireNotNull(exit.target).value}"
        StructuredSwitchCaseExitKind.RETURN_OR_THROW -> "terminates"
    }

    private fun formatBlocks(blocks: Set<io.github.relvl.deobscura.cfg.BasicBlockId>): String =
        blocks.sortedBy { it.value }.joinToString(prefix = "[", postfix = "]") { "B${it.value}" }
}
