package io.github.relvl.deobscura.expression

import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaOperationSemantics
import io.github.relvl.deobscura.analysis.SsaValueUse
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.raw.RawBranchInstruction

/**
 * Plans source-like expression materialization without changing SSA identity.
 *
 * Only effect-free single-use instruction values are inlined. Values feeding phi nodes remain
 * explicit because moving their evaluation to the join would change control-flow semantics.
 * Effectful Java statement-expressions with an unused result are rendered without a synthetic
 * assignment while retaining their ValueId in the underlying expression graph.
 */
class ExpressionMaterializer(
    private val semantics: SsaOperationSemantics = SsaOperationSemantics(),
) {
    fun materialize(ssa: SsaAnalysis, expression: ExpressionAnalysis): ExpressionMaterialization {
        val operationsByOutput = ssa.operations.mapNotNull { operation -> operation.output?.let { it to operation } }.toMap()
        val inlineValues = linkedSetOf<ValueId>()
        val discardedResultValues = linkedSetOf<ValueId>()
        val booleanValues = findBooleanPhiValues(ssa, expression)

        expression.values.values.forEach { value ->
            val operation = operationsByOutput[value.id] ?: return@forEach
            val uses = ssa.uses[value.id].orEmpty()

            if (uses.isEmpty() && value.node.isJavaStatementExpression()) {
                discardedResultValues += value.id
                return@forEach
            }

            if (uses.size != 1) return@forEach
            val use = uses.single()
            if (use is SsaValueUse.Phi && use.output in booleanValues && value.node.isZeroOrOneConstant()) {
                inlineValues += value.id
                return@forEach
            }
            if (use !is SsaValueUse.Operation) return@forEach
            if (!semantics.canDiscardWhenResultUnused(operation.instruction)) return@forEach
            inlineValues += value.id
        }

        return ExpressionMaterialization(inlineValues, discardedResultValues, booleanValues)
    }

    private fun findBooleanPhiValues(ssa: SsaAnalysis, expression: ExpressionAnalysis): Set<ValueId> {
        val operationsByIndex = ssa.operations.associateBy { it.instructionIndex }
        return expression.values.values.asSequence()
            .filter { value ->
                val phi = value.node as? ExpressionNode.Phi ?: return@filter false
                val meaningfulInputs = phi.inputs.asSequence().map { it.value }.filter { it != value.id }.distinct().toList()
                if (meaningfulInputs.isEmpty() || meaningfulInputs.any { !expression.value(it).node.isZeroOrOneConstant() }) return@filter false

                val uses = ssa.uses[value.id].orEmpty()
                uses.isNotEmpty() && uses.all { use ->
                    val operationUse = use as? SsaValueUse.Operation ?: return@all false
                    val branch = operationsByIndex[operationUse.instructionIndex]?.instruction as? RawBranchInstruction ?: return@all false
                    branch.opcode.mnemonic == "ifeq" || branch.opcode.mnemonic == "ifne"
                }
            }
            .map { it.id }
            .toSet()
    }

    private fun ExpressionNode.isZeroOrOneConstant(): Boolean =
        this is ExpressionNode.Constant && (value.equals(0) || value.equals(1))

    private fun ExpressionNode.isJavaStatementExpression(): Boolean = when (this) {
        is ExpressionNode.Call,
        is ExpressionNode.DynamicCall,
        is ExpressionNode.ConstructObject,
            -> true

        else -> false
    }
}
