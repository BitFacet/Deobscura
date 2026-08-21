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
    val loopAssignments: Map<ValueId, SourceLoopAssignment> = emptyMap(),
    val localFamilies: Map<ValueId, SourceLocalFamily> = emptyMap(),
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

/** A loop-carried local phi represented as one declaration before the loop and a back-edge assignment. */
data class SourceLoopAssignment(
    val header: BasicBlockId,
    val initialValue: ValueId,
    val updatedValue: ValueId,
)

/** Several SSA local phis that are different versions of one source local. */
data class SourceLocalFamily(
    val slot: Int,
    val target: ValueId,
    val phiValues: Set<ValueId>,
    val initialValue: ValueId,
    val assignments: Set<SourceLocalFamilyAssignment>,
)

/** A source-local assignment that occurs when control leaves one phi predecessor. */
data class SourceLocalFamilyAssignment(
    val predecessor: BasicBlockId,
    val value: ValueId,
)

/**
 * Reconstructs source-local forms only when already-structured control flow proves their placement.
 * Pure constant diamonds become conditional expressions; branch and loop-carried local phis become
 * explicit declarations and assignments at the original value-definition sites.
 *
 * Canonical SSA/Expression IR remains untouched.
 */
class SourceLocalAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
        structure: StructuredControlFlowAnalysis,
        controlFlow: SsaControlFlowGraph = SsaControlFlowGraph.from(graph),
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
        val loopAssignments = linkedMapOf<ValueId, SourceLoopAssignment>()
        val localFamilies = linkedMapOf<ValueId, SourceLocalFamily>()
        val suppressedDefinitions = linkedSetOf<ValueId>()
        val consumedIfHeaders = linkedSetOf<BasicBlockId>()

        structure.regions.filterIsInstance<StructuredRegion.While>().forEach { region ->
            phisByBlock[region.header].orEmpty().forEach familyLoop@{ seed ->
                val seedPhi = seed.node as ExpressionNode.Phi
                val location = seedPhi.location as? SsaPhiLocation.Local ?: return@familyLoop
                val phiIds = linkedSetOf<ValueId>()
                val pending = ArrayDeque<ValueId>()
                pending += seed.id
                while (pending.isNotEmpty()) {
                    val id = pending.removeFirst()
                    if (!phiIds.add(id)) continue
                    val value = expression.values[id] ?: return@familyLoop
                    val phi = value.node as? ExpressionNode.Phi ?: return@familyLoop
                    if ((phi.location as? SsaPhiLocation.Local)?.slot != location.slot) return@familyLoop
                    if (phi.blockId !in region.coveredBlocks || phi.inputs.any { it.predecessor == null }) return@familyLoop
                    phi.inputs.forEach { input ->
                        val inputPhi = expression.values[input.value]?.node as? ExpressionNode.Phi
                        if ((inputPhi?.location as? SsaPhiLocation.Local)?.slot == location.slot) pending += input.value
                    }
                }
                if (phiIds.size <= 1) return@familyLoop

                // Local phi inputs are already predecessor-addressed and canonicalized by SsaAnalyzer.
                // Comparing them with raw ValueFlowAnalysis ids here would mix pre-SSA and SSA identities.
                data class BoundaryInput(val phiBlock: BasicBlockId, val input: SsaPhiInput)
                val boundaryInputs = phiIds.flatMap { id ->
                    val phi = expression.values.getValue(id).node as ExpressionNode.Phi
                    phi.inputs.filter { it.value !in phiIds }.map { BoundaryInput(phi.blockId, it) }
                }.distinct()
                if (boundaryInputs.any { it.input.predecessor == null }) return@familyLoop
                val initialValues = boundaryInputs.filter { it.input.predecessor !in region.coveredBlocks }.map { it.input.value }.distinct()
                val assignmentInputs = boundaryInputs.filter { it.input.predecessor in region.bodyBlocks }
                val assignedValues = assignmentInputs.map { it.input.value }.distinct()
                if (initialValues.size != 1 || assignmentInputs.isEmpty() || initialValues.size + assignedValues.size != boundaryInputs.map { it.input.value }.distinct().size) return@familyLoop
                val assignments = assignmentInputs.map assignmentLoop@{ boundary ->
                    val predecessor = boundary.input.predecessor ?: return@familyLoop
                    val normalOutgoing = controlFlow.edges.filter { it.from == predecessor && it.kind != io.github.relvl.deobscura.cfg.ControlFlowEdgeKind.EXCEPTION }
                    if (normalOutgoing.size != 1 || normalOutgoing.single().to != boundary.phiBlock) return@familyLoop
                    SourceLocalFamilyAssignment(predecessor, boundary.input.value)
                }.toSet()
                val initial = initialValues.single()
                if (materializedValueBlocks[initial] == null || materializedValueBlocks[initial] in region.coveredBlocks) return@familyLoop
                if (assignedValues.any { materializedValueBlocks[it] !in region.bodyBlocks }) return@familyLoop
                if (assignedValues.any { expression.values[it]?.type != seed.type }) return@familyLoop
                val initialType = expression.values[initial]?.type ?: return@familyLoop
                if (initialType != seed.type && initialType !is JvmValueType.Reference) return@familyLoop

                localFamilies[seed.id] = SourceLocalFamily(location.slot, seed.id, phiIds, initial, assignments)
            }

            phisByBlock[region.header].orEmpty().forEach phiLoop@{ phiValue ->
                if (localFamilies.values.any { phiValue.id in it.phiValues }) return@phiLoop
                if (!canDeclareSourceLocal(phiValue)) return@phiLoop
                val phi = phiValue.node as ExpressionNode.Phi
                if (phi.location !is SsaPhiLocation.Local) return@phiLoop
                if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop

                val loopBlocks = region.coveredBlocks
                val initialInput = phi.inputs.singleOrNull { it.predecessor !in loopBlocks }?.value ?: return@phiLoop
                val updatedInput = phi.inputs.singleOrNull { it.predecessor in region.bodyBlocks }?.value ?: return@phiLoop
                if (materializedValueBlocks[initialInput] == null) return@phiLoop
                if (materializedValueBlocks[updatedInput] !in region.bodyBlocks) return@phiLoop
                if (!isSingleUsePhiInput(initialInput, phiValue.id, ssa, expression)) return@phiLoop
                if (!isSingleUsePhiInput(updatedInput, phiValue.id, ssa, expression)) return@phiLoop

                loopAssignments[phiValue.id] = SourceLoopAssignment(region.header, initialInput, updatedInput)
            }
        }

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

        return SourceLocalAnalysis(conditionalValues, conditionalAssignments, twoArmAssignments, loopAssignments, localFamilies, suppressedDefinitions, consumedIfHeaders)
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
