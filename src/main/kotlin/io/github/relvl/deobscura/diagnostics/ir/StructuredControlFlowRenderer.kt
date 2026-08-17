package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.expression.ExpressionAnalysis

/** Compact diagnostic rendering of source-level regions proven from the canonical CFG. */
class StructuredControlFlowRenderer(
    private val expressionRenderer: ExpressionIrRenderer = ExpressionIrRenderer(),
) {
    fun render(structure: StructuredControlFlowAnalysis, expression: ExpressionAnalysis): String = buildString {
        if (structure.regions.isEmpty() && structure.booleanConditionFolds.isEmpty() && structure.unstructured.isEmpty()) {
            appendLine("    <no recognized regions>")
            return@buildString
        }

        structure.booleanConditionFolds.forEach { fold ->
            val condition = expressionRenderer.renderCondition(fold.condition, expression)
            append("    folded-boolean B${fold.producerHeader.value} -> B${fold.consumerHeader.value}")
            append(" via=v${fold.phiValue.value} condition=($condition)")
            appendLine(" materialization=${formatBlocks(fold.materializationBlocks)}")
        }

        structure.regions.forEach { region ->
            when (region) {
                is StructuredRegion.If -> {
                    val condition = expressionRenderer.renderCondition(region.condition, expression)
                    append("    if B${region.header.value} condition=($condition)")
                    append(" then=${formatBlocks(region.thenBlocks)}")
                    if (region.elseBlocks.isNotEmpty()) append(" else=${formatBlocks(region.elseBlocks)}")
                    appendLine(" join=B${region.join.value}")
                }

                is StructuredRegion.While -> {
                    val condition = expressionRenderer.renderCondition(region.condition, expression, region.negateCondition)
                    append("    while B${region.header.value} condition=($condition)")
                    append(" body=${formatBlocks(region.bodyBlocks)}")
                    append(" exit=B${region.exit.value}")
                    appendLine(" latches=${formatBlocks(region.latches)}")
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

    private fun formatBlocks(blocks: Set<io.github.relvl.deobscura.cfg.BasicBlockId>): String =
        blocks.sortedBy { it.value }.joinToString(prefix = "[", postfix = "]") { "B${it.value}" }
}
