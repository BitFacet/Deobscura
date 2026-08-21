package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.source.SourceVariableAssignment
import io.github.relvl.deobscura.source.SourceVariableAssignmentSite
import io.github.relvl.deobscura.source.SourceVariableOrigin

/** Human-readable, deterministic dump of the current technical IR. */
class TechnicalIrRenderer(
    private val expressionRenderer: ExpressionIrRenderer = ExpressionIrRenderer(),
    private val structuredControlFlowRenderer: StructuredControlFlowRenderer = StructuredControlFlowRenderer(expressionRenderer),
    private val sourceStructureRenderer: SourceStructureRenderer = SourceStructureRenderer(),
) {
    fun renderClassHeader(rawClass: RawClass): String = buildString {
        appendLine("# Deobscura technical IR v${TechnicalIrService.FORMAT_VERSION}")
        appendLine("class ${rawClass.internalName}")
        appendLine("version ${rawClass.majorVersion}.${rawClass.minorVersion}")
        appendLine("super ${rawClass.superName ?: "<none>"}")
        if (rawClass.interfaces.isNotEmpty()) appendLine("interfaces ${rawClass.interfaces.joinToString()}")
        appendLine()
    }

    fun renderMethod(method: RawMethod, analysis: MethodAnalysis): String = buildString {
        appendLine("method ${method.name}${method.descriptor}")
        appendLine("  normalized-jsr-ret: ${analysis.normalization.changed}")
        val stats = analysis.optimization.stats
        appendLine("  optimizer-iterations: ${stats.iterationCount}")
        appendLine(
            "  ssa-size: " + "initial=${analysis.initialSsa.values.size} values/${analysis.initialSsa.operations.size} ops/${analysis.initialSsa.phiNodes.size} phi, " + "final=${analysis.ssa.values.size} values/${analysis.ssa.operations.size} ops/${analysis.ssa.phiNodes.size} phi",
        )
        appendLine(
            "  optimization: cfg-pruned-ops=${stats.removedOperationCount}, dead-ops=${stats.deadOperationCount}, " + "dead-values=${stats.deadValueCount}, passthrough-blocks=${stats.canonicalizedPassthroughBlockCount}, " + "control-flow-ops=${stats.removedControlFlowOperationCount}",
        )
        appendLine()

        appendLine("  normalized-code:")
        analysis.method.code?.instructions?.forEachIndexed { index, instruction ->
            appendLine("    @$index ${formatInstruction(instruction)}")
        }
        appendLine()

        appendLine("  frames:")
        for (block in analysis.graph.blocks) {
            val entry = analysis.frames.entryFrames[block.id]
            if (entry != null) appendLine("    B${block.id.value} entry ${formatFrame(entry)}")
        }
        appendLine()

        appendLine("  control-flow:")
        val finalFlow = analysis.optimization.controlFlow
        val outgoingByBlock = finalFlow.edges.groupBy { it.from }
        for (block in finalFlow.blocks.sortedBy { it.value }) {
            val outgoing = outgoingByBlock[block].orEmpty()
            append("    B${block.value}")
            if (block == finalFlow.entryBlock) append(" [entry]")
            appendLine()
            outgoing.forEach { appendLine("      -> ${formatEdge(it)}") }
        }
        appendLine()

        appendLine("  ssa:")
        val phiByBlock = analysis.ssa.phiNodes.groupBy { it.blockId }
        val instructionToBlock = arrayOfNulls<io.github.relvl.deobscura.cfg.BasicBlockId>(
            analysis.graph.code.instructions.size,
        )
        analysis.graph.blocks.forEach { block ->
            for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                instructionToBlock[index] = block.id
            }
        }
        val operationsByBlock = analysis.ssa.operations.groupBy { operation ->
            instructionToBlock.getOrNull(operation.instructionIndex)
        }
        for (block in finalFlow.blocks.sortedBy { it.value }) {
            appendLine("    B${block.value}:")
            phiByBlock[block].orEmpty().forEach { phi ->
                appendLine(
                    "      v${phi.output.value}:${formatValueType(analysis.ssa.typeOf(phi.output))} = phi ${formatPhiLocation(phi.location)} " + phi.inputs.joinToString(prefix = "[", postfix = "]") { input ->
                        input.predecessor?.let { "B${it.value}=v${input.value.value}" } ?: "origin=v${input.value.value}"
                    },
                )
            }
            operationsByBlock[block].orEmpty().sortedBy { it.instructionIndex }.forEach { operation ->
                val output = operation.output?.let { "v${it.value}:${formatValueType(analysis.ssa.typeOf(it))} = " } ?: ""
                val inputs = operation.inputs.joinToString(prefix = "(", postfix = ")") { "v${it.value}" }
                appendLine("      @${operation.instructionIndex} $output${formatInstruction(operation.instruction)} $inputs")
            }
        }
        appendLine()

        appendLine("  expression-ir:")
        append(expressionRenderer.render(analysis))
        appendLine()

        appendLine("  structured-control-flow:")
        append(structuredControlFlowRenderer.render(analysis.structuredControlFlow, analysis.expression))
        appendLine()

        appendLine("  source-structure:")
        append(sourceStructureRenderer.render(analysis.sourceStructure))
        appendLine()

        appendLine("  source-variables:")
        if (analysis.sourceVariables.variables.isEmpty()) {
            appendLine("    <none>")
        } else {
            analysis.sourceVariables.variables.values.sortedBy { it.id.value }.forEach { variable ->
                val origin = when (val value = variable.origin) {
                    is SourceVariableOrigin.Local -> "local[${value.slot}]"
                    is SourceVariableOrigin.SyntheticStack -> "stack[${value.index}]"
                }
                appendLine(
                    "    v${variable.id.value}:${formatValueType(variable.type)} <- $origin " +
                        "phis=${variable.phiValues.sortedBy { it.value }.joinToString(prefix = "[", postfix = "]") { "v${it.value}" }} " +
                        "declare=B${variable.declarationBlock.value}${if (variable.isBoolean) " boolean" else ""}",
                )
            }
            analysis.sourceVariables.assignments.sortedWith(
                compareBy<SourceVariableAssignment> { assignment ->
                    when (val site = assignment.site) {
                        is SourceVariableAssignmentSite.Instruction -> site.instructionIndex
                        is SourceVariableAssignmentSite.BlockExit -> Int.MAX_VALUE - 1
                        is SourceVariableAssignmentSite.Edge -> Int.MAX_VALUE
                    }
                }.thenBy { it.variable.value }.thenBy { it.value.value },
            ).forEach { assignment ->
                val site = when (val value = assignment.site) {
                    is SourceVariableAssignmentSite.Instruction -> "@${value.instructionIndex}"
                    is SourceVariableAssignmentSite.BlockExit -> "B${value.block.value}:exit"
                    is SourceVariableAssignmentSite.Edge -> "B${value.from.value}->B${value.to.value}"
                }
                appendLine("      $site: v${assignment.variable.value} <- v${assignment.value.value} -> B${assignment.phiBlock.value}")
            }
        }
        if (analysis.sourceVariables.unresolvedNormalPhiValues.isNotEmpty()) {
            appendLine(
                "    unresolved-normal-phi: " + analysis.sourceVariables.unresolvedNormalPhiValues.sortedBy { it.value }
                    .joinToString { "v${it.value}" },
            )
        }
        if (analysis.sourceVariables.exceptionalPhiValues.isNotEmpty()) {
            appendLine(
                "    exceptional-phi: " + analysis.sourceVariables.exceptionalPhiValues.sortedBy { it.value }
                    .joinToString { "v${it.value}" },
            )
        }
        appendLine()

        appendLine("  roots:")
        analysis.ssa.values.values.filterIsInstance<SsaValueDefinition.Root>().sortedBy { it.id.value }.forEach { appendLine("    v${it.id.value}: ${formatValueType(it.type)} ${it.origin}") }
        appendLine()

        if (analysis.ssa.constants.isNotEmpty()) {
            appendLine("  constants:")
            analysis.ssa.constants.entries.sortedBy { it.key.value }.forEach { (id, constant) ->
                appendLine("    v${id.value} = $constant")
            }
            appendLine()
        }

        appendLine("end method")
        appendLine()
    }

    fun renderFailure(method: RawMethod, exception: MethodAnalysisException): String = buildString {
        appendLine("method ${method.name}${method.descriptor}")
        appendLine("  ANALYSIS FAILED at ${exception.stage}: ${(exception.cause ?: exception).message}")
        appendLine("  raw-instructions:")
        method.code?.instructions?.forEachIndexed { index, instruction ->
            appendLine("    @$index ${formatInstruction(instruction)}")
        }
        appendLine("end method")
        appendLine()
    }

    private fun formatFrame(frame: FrameState): String = buildString {
        append("locals=")
        append(frame.locals.joinToString(prefix = "[", postfix = "]") { it?.let(::formatFrameValue) ?: "TOP" })
        append(" stack=")
        append(frame.stack.joinToString(prefix = "[", postfix = "]", transform = ::formatFrameValue))
    }

    private fun formatFrameValue(value: FrameValue): String = formatValueType(value.type)

    private fun formatValueType(type: JvmValueType): String = type.formatTypeName({ it }, nullTypeName = "null", unknownReferenceName = "reference?")

    private fun formatEdge(edge: ControlFlowEdge): String = buildString {
        append("B${edge.to.value} ${edge.kind}")
        edge.switchValue?.let { append(" case=$it") }
        edge.catchType?.let { append(" catch=$it") }
    }

    private fun formatConstant(value: Any): String = value.toString().replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

    private fun formatInstruction(instruction: RawInstruction): String = when (instruction) {
        is RawConstantInstruction -> "${instruction.opcode.mnemonic} ${formatConstant(instruction.value)}"
        is RawLocalInstruction -> "${instruction.opcode.mnemonic} local=${instruction.slot}"
        is RawIncrementInstruction -> "${instruction.opcode.mnemonic} local=${instruction.slot} amount=${instruction.amount}"
        is RawArrayInstruction -> instruction.opcode.mnemonic
        is RawOperatorInstruction -> instruction.opcode.mnemonic
        is RawConversionInstruction -> instruction.opcode.mnemonic
        is RawStackInstruction -> instruction.opcode.mnemonic
        is RawBranchInstruction -> "${instruction.opcode.mnemonic} L${instruction.target.value}"
        is RawSwitchInstruction -> buildString {
            append(instruction.opcode.mnemonic)
            append(" default=L${instruction.defaultTarget.value}")
            instruction.cases.forEach { append(" ${it.value}:L${it.target.value}") }
        }

        is RawFieldInstruction -> "${instruction.opcode.mnemonic} ${instruction.owner}.${instruction.name}:${instruction.descriptor}"

        is RawInvokeInstruction -> "${instruction.opcode.mnemonic} ${instruction.owner}.${instruction.name}${instruction.descriptor}"

        is RawInvokeDynamicInstruction -> "${instruction.opcode.mnemonic} ${instruction.name}${instruction.descriptor}"

        is RawNewObjectInstruction -> "${instruction.opcode.mnemonic} ${instruction.internalName}"
        is RawNewArrayInstruction -> "${instruction.opcode.mnemonic} ${instruction.componentType.descriptor}"
        is RawNewMultiArrayInstruction -> "${instruction.opcode.mnemonic} ${instruction.arrayType.descriptor} dimensions=${instruction.dimensions}"

        is RawTypeCheckInstruction -> "${instruction.opcode.mnemonic} ${instruction.type.descriptor}"
        is RawReturnInstruction -> instruction.opcode.mnemonic
        is RawMonitorInstruction -> instruction.opcode.mnemonic
        is RawThrowInstruction -> instruction.opcode.mnemonic
        is RawNopInstruction -> instruction.opcode.mnemonic
        is RawRetInstruction -> "${instruction.opcode.mnemonic} local=${instruction.slot}"
        is RawUnknownInstruction -> "${instruction.opcode.mnemonic} <unknown:${instruction.classFileInstructionType}>"
    }
}
