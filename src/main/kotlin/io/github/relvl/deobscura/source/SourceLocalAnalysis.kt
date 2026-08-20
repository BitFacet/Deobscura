package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaValueUse
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredCondition
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement

/** Source-only rewrites of SSA locals that are proven from already-structured control flow. */
data class SourceLocalAnalysis(
    val conditionalValues: Map<ValueId, SourceConditionalValue> = emptyMap(),
    val suppressedDefinitions: Set<ValueId> = emptySet(),
    val consumedIfHeaders: Set<BasicBlockId> = emptySet(),
)

data class SourceConditionalValue(
    val condition: StructuredCondition,
    val thenValue: ValueId,
    val elseValue: ValueId,
)

/**
 * Recognizes the smallest safe source-local pattern first: a structured if/else whose only payload
 * is producing constants for predecessor-addressed phi values at the common continuation.
 *
 * The canonical SSA/Expression IR remains untouched. The source layer can therefore render a
 * conditional expression and hide the compiler materialization diamond without changing analysis.
 */
class SourceLocalAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
        structure: StructuredControlFlowAnalysis,
    ): SourceLocalAnalysis {
        val instructionToBlock = buildMap<Int, BasicBlockId> {
            graph.blocks.forEach { block ->
                for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
            }
        }
        val materializedValuesByBlock = expression.values.values
            .asSequence()
            .filter { it.instructionIndices.isNotEmpty() && it.id !in expression.materialization.inlineValues }
            .mapNotNull { value -> instructionToBlock[value.instructionIndices.last()]?.let { it to value.id } }
            .groupBy({ it.first }, { it.second })
        val statementsByBlock = expression.statements
            .mapNotNull { statement -> instructionToBlock[statement.instructionIndex]?.let { it to statement } }
            .groupBy({ it.first }, { it.second })
        val phisByBlock = expression.values.values
            .filter { it.node is ExpressionNode.Phi }
            .groupBy { (it.node as ExpressionNode.Phi).blockId }
        val nestedHeaders = structure.regions.mapTo(linkedSetOf()) { it.header }

        val conditionalValues = linkedMapOf<ValueId, SourceConditionalValue>()
        val suppressedDefinitions = linkedSetOf<ValueId>()
        val consumedIfHeaders = linkedSetOf<BasicBlockId>()

        structure.regions.filterIsInstance<StructuredRegion.If>().forEach { region ->
            if (region.thenExit != null || region.elseExit != null) return@forEach
            if (region.thenBlocks.isEmpty() || region.elseBlocks.isEmpty()) return@forEach
            if (((region.thenBlocks + region.elseBlocks) - region.header).any { it in nestedHeaders }) return@forEach

            val candidates = phisByBlock[region.continuation].orEmpty().mapNotNull { phiValue ->
                val phi = phiValue.node as ExpressionNode.Phi
                if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@mapNotNull null
                val thenInput = phi.inputs.singleOrNull { it.predecessor in region.thenBlocks }?.value ?: return@mapNotNull null
                val elseInput = phi.inputs.singleOrNull { it.predecessor in region.elseBlocks }?.value ?: return@mapNotNull null
                if (!isSingleUseConstant(thenInput, phiValue.id, ssa, expression)) return@mapNotNull null
                if (!isSingleUseConstant(elseInput, phiValue.id, ssa, expression)) return@mapNotNull null
                phiValue.id to SourceConditionalValue(region.condition, thenInput, elseInput)
            }.toMap()
            if (candidates.isEmpty()) return@forEach

            val candidateInputs = candidates.values.flatMapTo(linkedSetOf()) { listOf(it.thenValue, it.elseValue) }
            val armBlocks = region.thenBlocks + region.elseBlocks
            val emittedArmValues = armBlocks.flatMap { materializedValuesByBlock[it].orEmpty() }.toSet()
            if (emittedArmValues != candidateInputs) return@forEach
            val hasSemanticStatement = armBlocks
                .flatMap { statementsByBlock[it].orEmpty() }
                .any { it !is ExpressionStatement.Branch }
            if (hasSemanticStatement) return@forEach
            if (armBlocks.any { phisByBlock[it].orEmpty().isNotEmpty() }) return@forEach

            conditionalValues.putAll(candidates)
            suppressedDefinitions += candidateInputs
            consumedIfHeaders += region.header
        }

        return SourceLocalAnalysis(conditionalValues, suppressedDefinitions, consumedIfHeaders)
    }

    private fun isSingleUseConstant(
        valueId: ValueId,
        phiOutput: ValueId,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
    ): Boolean {
        if (expression.values[valueId]?.node !is ExpressionNode.Constant) return false
        val uses = ssa.uses[valueId].orEmpty()
        return uses.size == 1 && (uses.single() as? SsaValueUse.Phi)?.output == phiOutput
    }
}
