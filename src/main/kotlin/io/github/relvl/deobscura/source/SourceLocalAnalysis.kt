package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredCondition
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType

/** Source-only rewrites of SSA locals that are proven from already-structured control flow. */
data class SourceLocalAnalysis(
    val conditionalValues: Map<ValueId, SourceConditionalValue> = emptyMap(),
    val conditionalAssignments: Map<ValueId, SourceConditionalAssignment> = emptyMap(),
    val twoArmAssignments: Map<ValueId, SourceTwoArmAssignment> = emptyMap(),
    val suppressedDefinitions: Set<ValueId> = emptySet(),
    val consumedIfHeaders: Set<BasicBlockId> = emptySet(),
)

data class SourceConditionalValue(
    val condition: StructuredCondition,
    val thenValue: ValueId,
    val elseValue: ValueId,
)

/** A local phi represented as an initializer followed by an assignment in one structured if arm. */
data class SourceConditionalAssignment(
    val initialValue: ValueId,
    val assignedValue: ValueId,
)

/** A local phi declared before an if/else and assigned independently in both arms. */
data class SourceTwoArmAssignment(
    val header: BasicBlockId,
    val thenValue: ValueId,
    val elseValue: ValueId,
)

/**
 * Reconstructs source-local forms only when already-structured control flow proves their placement.
 * Pure constant diamonds become conditional expressions; one-arm local phis become an explicit
 * declaration before the if and an assignment in the populated arm.
 *
 * Canonical SSA/Expression IR remains untouched.
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
        val materializedValueBlocks = expression.values.values.asSequence().filter { it.instructionIndices.isNotEmpty() && it.id !in expression.materialization.inlineValues }
            .mapNotNull { value -> instructionToBlock[value.instructionIndices.last()]?.let { value.id to it } }.toMap()
        val materializedValuesByBlock = materializedValueBlocks.entries.groupBy({ it.value }, { it.key })
        val statementsByBlock = expression.statements.mapNotNull { statement -> instructionToBlock[statement.instructionIndex]?.let { it to statement } }.groupBy({ it.first }, { it.second })
        val phisByBlock = expression.values.values.filter { it.node is ExpressionNode.Phi }.groupBy { (it.node as ExpressionNode.Phi).blockId }
        val nestedHeaders = structure.regions.mapTo(linkedSetOf()) { it.header }

        val conditionalValues = linkedMapOf<ValueId, SourceConditionalValue>()
        val conditionalAssignments = linkedMapOf<ValueId, SourceConditionalAssignment>()
        val twoArmAssignments = linkedMapOf<ValueId, SourceTwoArmAssignment>()
        val suppressedDefinitions = linkedSetOf<ValueId>()
        val consumedIfHeaders = linkedSetOf<BasicBlockId>()

        structure.regions.filterIsInstance<StructuredRegion.If>().forEach { region ->
            if (region.thenExit != null || region.elseExit != null) return@forEach

            if (region.thenBlocks.isNotEmpty() && region.elseBlocks.isNotEmpty()) {
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

                if (candidates.isNotEmpty()) {
                    val candidateInputs = candidates.values.flatMapTo(linkedSetOf()) { listOf(it.thenValue, it.elseValue) }
                    val armBlocks = region.thenBlocks + region.elseBlocks
                    val emittedArmValues = armBlocks.flatMap { materializedValuesByBlock[it].orEmpty() }.toSet()
                    val hasSemanticStatement = armBlocks.flatMap { statementsByBlock[it].orEmpty() }.any { it !is ExpressionStatement.Branch }
                    val hasArmPhi = armBlocks.any { phisByBlock[it].orEmpty().isNotEmpty() }
                    if (emittedArmValues == candidateInputs && !hasSemanticStatement && !hasArmPhi) {
                        conditionalValues.putAll(candidates)
                        suppressedDefinitions += candidateInputs
                        consumedIfHeaders += region.header
                        return@forEach
                    }
                }

                phisByBlock[region.continuation].orEmpty().forEach phiLoop@{ phiValue ->
                    if (!canDeclareSourceLocal(phiValue)) return@phiLoop
                    val phi = phiValue.node as ExpressionNode.Phi // A complete two-arm merge is source-local regardless of whether the JVM
                    // verifier carried the value in a local slot or on the operand stack. Both
                    // structured arms assign it before the common continuation.
                    if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop
                    val thenInput = phi.inputs.singleOrNull { it.predecessor in region.thenBlocks }?.value ?: return@phiLoop
                    val elseInput = phi.inputs.singleOrNull { it.predecessor in region.elseBlocks }?.value ?: return@phiLoop
                    if (materializedValueBlocks[thenInput] !in region.thenBlocks) return@phiLoop
                    if (materializedValueBlocks[elseInput] !in region.elseBlocks) return@phiLoop
                    if (!isSingleUsePhiInput(thenInput, phiValue.id, ssa, expression)) return@phiLoop
                    if (!isSingleUsePhiInput(elseInput, phiValue.id, ssa, expression)) return@phiLoop

                    twoArmAssignments[phiValue.id] = SourceTwoArmAssignment(region.header, thenInput, elseInput)
                }
                return@forEach
            }

            if (region.thenBlocks.isEmpty() == region.elseBlocks.isEmpty()) return@forEach
            val assignmentBlocks = if (region.thenBlocks.isEmpty()) region.elseBlocks else region.thenBlocks
            if (assignmentBlocks.any { it in nestedHeaders }) return@forEach

            phisByBlock[region.continuation].orEmpty().forEach phiLoop@{ phiValue ->
                if (!canDeclareSourceLocal(phiValue)) return@phiLoop
                val phi = phiValue.node as ExpressionNode.Phi
                if (phi.location !is SsaPhiLocation.Local) return@phiLoop
                if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop
                val initialInput = phi.inputs.singleOrNull { it.predecessor == region.header }?.value ?: return@phiLoop
                val assignedInput = phi.inputs.singleOrNull { it.predecessor in assignmentBlocks }?.value ?: return@phiLoop
                if (materializedValueBlocks[initialInput] != region.header) return@phiLoop
                if (materializedValueBlocks[assignedInput] !in assignmentBlocks) return@phiLoop
                if (!isSingleUsePhiInput(initialInput, phiValue.id, ssa, expression)) return@phiLoop
                if (!isSingleUsePhiInput(assignedInput, phiValue.id, ssa, expression)) return@phiLoop

                conditionalAssignments[phiValue.id] = SourceConditionalAssignment(initialInput, assignedInput)
            }
        }

        return SourceLocalAnalysis(conditionalValues, conditionalAssignments, twoArmAssignments, suppressedDefinitions, consumedIfHeaders)
    }

    private fun canDeclareSourceLocal(value: ExpressionValue): Boolean = when (val type = value.type) {
        is JvmValueType.Computational -> type.type != JvmComputationalType.RETURN_ADDRESS
        is JvmValueType.Reference -> type.referenceType is JvmReferenceType.Exact
    }

    private fun isSingleUsePhiInput(
        valueId: ValueId,
        phiOutput: ValueId,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
    ): Boolean {
        val value = expression.values[valueId] ?: return false
        if (value.node is ExpressionNode.Phi || value.instructionIndices.isEmpty()) return false
        if (valueId in expression.materialization.inlineValues) return false
        val uses = ssa.uses[valueId].orEmpty()
        return uses.size == 1 && (uses.single() as? SsaValueUse.Phi)?.output == phiOutput
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
