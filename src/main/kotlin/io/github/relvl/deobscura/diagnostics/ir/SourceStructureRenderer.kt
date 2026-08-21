package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowDiagnostic
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.source.*

/** Compact diagnostic projection of proven control-flow regions into their prospective source nesting. */
class SourceStructureRenderer {
    fun render(structure: SourceStructureAnalysis): String = buildString {
        renderBlock(structure.root, 2)
        if (structure.issues.isNotEmpty()) {
            appendLine("    projection-issues:")
            structure.issues.forEach { issue ->
                appendLine(
                    "      ${issue.reason.name.lowercase().replace('_', '-')} blocks=${formatBlocks(issue.blocks)}",
                )
            }
        }
        if (structure.consumptions.isNotEmpty()) {
            appendLine("    consumed-physical-blocks:")
            structure.consumptions
                .sortedWith(compareBy<SourceConsumption> { it.block.value }.thenBy { it.reason.name })
                .forEach { consumption ->
                    appendLine(
                        "      B${consumption.block.value} ${consumption.reason.name.lowercase().replace('_', '-')} " +
                            "owner=B${consumption.ownerHeader.value}",
                    )
                }
        }
    }

    private fun StringBuilder.renderBlock(block: SourceBlock, indent: Int) {
        block.nodes.forEach { node ->
            val prefix = " ".repeat(indent)
            when (node) {
                is SourceNode.BasicBlock -> appendLine("${prefix}block B${node.block.value}${formatRanges(node.provenance)}")
                is SourceNode.Unstructured -> {
                    appendLine("${prefix}unstructured B${node.block.value}${formatRanges(node.provenance)}")
                    node.diagnostics.forEach { diagnostic -> appendLine("$prefix  ${renderDiagnostic(diagnostic)}") }
                }

                is SourceNode.ProjectionFallback -> appendLine(
                    "${prefix}projection-fallback B${node.block.value} " +
                        "reason=${node.reason.name.lowercase().replace('_', '-')}${formatRanges(node.provenance)}",
                )

                is SourceNode.Structured -> {
                    appendLine("$prefix${renderRegion(node.region)} blocks=${formatBlocks(node.provenance.blocks)}")
                    node.diagnostics.forEach { diagnostic -> appendLine("$prefix  unresolved-at-header: ${renderDiagnostic(diagnostic)}") }
                    node.parts.forEach { part ->
                        val label = part.label?.let { " $it" }.orEmpty()
                        val ranges = if (part.instructionRanges.isEmpty()) "" else
                            part.instructionRanges.joinToString(prefix = " ranges=[", postfix = "]") { "@${it.first}..@${it.last}" }
                        appendLine("$prefix  ${part.kind.name.lowercase().replace('_', '-')}$label blocks=${formatBlocks(part.ownedBlocks)}$ranges")
                        renderBlock(part.body, indent + 4)
                    }
                }
            }
        }
    }

    private fun renderRegion(region: StructuredRegion): String = when (region) {
        is StructuredRegion.If -> "if B${region.header.value}"
        is StructuredRegion.While -> "while B${region.header.value}"
        is StructuredRegion.Switch -> "switch B${region.header.value}"
        is StructuredRegion.TryCatch -> "try/catch B${region.header.value}"
        is StructuredRegion.TryFinally -> "try/finally B${region.header.value}"
        is StructuredRegion.TryCatchFinally -> "try/catch/finally B${region.header.value}"
        is StructuredRegion.Synchronized -> "synchronized B${region.header.value}"
    }

    private fun renderDiagnostic(diagnostic: UnstructuredControlFlowDiagnostic): String = buildString {
        append(diagnostic.kind.name.lowercase())
        append(": ")
        append(diagnostic.reason.diagnosticName)
        if (diagnostic.kind == UnstructuredControlFlowKind.EXCEPTION) {
            append(" protected=${diagnostic.protectedStartInstructionIndex}..<${diagnostic.protectedEndInstructionIndexExclusive}")
        }
        diagnostic.detail?.let { append(" [$it]") }
    }

    private fun formatBlocks(blocks: Set<io.github.relvl.deobscura.cfg.BasicBlockId>): String =
        blocks.sortedBy { it.value }.joinToString(prefix = "[", postfix = "]") { "B${it.value}" }

    private fun formatRanges(provenance: SourceProvenance): String = if (provenance.instructionRanges.isEmpty()) "" else
        provenance.instructionRanges.joinToString(prefix = " ranges=[", postfix = "]") { "@${it.first}..@${it.last}" }
}
