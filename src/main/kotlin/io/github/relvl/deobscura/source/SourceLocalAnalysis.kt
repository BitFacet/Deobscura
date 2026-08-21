package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredCondition
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.dominators as computeDominators
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawFieldInstruction
import io.github.relvl.deobscura.raw.RawInvokeDynamicInstruction
import io.github.relvl.deobscura.raw.RawInvokeInstruction

/** Source-only rewrites of SSA locals that are proven from already-structured control flow. */
data class SourceLocalAnalysis(
    val conditionalValues: Map<ValueId, SourceConditionalValue> = emptyMap(),
    val conditionalAssignments: Map<ValueId, SourceConditionalAssignment> = emptyMap(),
    val twoArmAssignments: Map<ValueId, SourceTwoArmAssignment> = emptyMap(),
    val loopAssignments: Map<ValueId, SourceLoopAssignment> = emptyMap(),
    val localFamilies: Map<ValueId, SourceLocalFamily> = emptyMap(),
    val booleanLocals: Set<ValueId> = emptySet(),
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
    /** Header before which a separate source local must be declared when the initial value has other uses. */
    val declarationHeader: BasicBlockId? = null,
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
        val definedValueBlocks = expression.values.values.asSequence().filter { it.instructionIndices.isNotEmpty() }
            .mapNotNull { value -> instructionToBlock[value.instructionIndices.last()]?.let { value.id to it } }.toMap()
        val definedValuesByBlock = definedValueBlocks.entries.groupBy({ it.value }, { it.key })
        val materializedValueBlocks = definedValueBlocks.filterKeys { it !in expression.materialization.inlineValues }
        val statementsByBlock = expression.statements.mapNotNull { statement -> instructionToBlock[statement.instructionIndex]?.let { it to statement } }.groupBy({ it.first }, { it.second })
        val phisByBlock = expression.values.values.filter { it.node is ExpressionNode.Phi }.groupBy { (it.node as ExpressionNode.Phi).blockId }
        val nestedHeaders = structure.regions.mapTo(linkedSetOf()) { it.header }
        val normalDominators by lazy(LazyThreadSafetyMode.NONE) {
            val entry = controlFlow.entryBlock ?: return@lazy emptyMap<BasicBlockId, Set<BasicBlockId>>()
            val predecessors = controlFlow.edges.asSequence()
                .filter { it.kind != io.github.relvl.deobscura.cfg.ControlFlowEdgeKind.EXCEPTION }
                .groupBy({ it.to }, { it.from })
            computeDominators(controlFlow.blocks, entry, predecessors)
        }

        val conditionalValues = linkedMapOf<ValueId, SourceConditionalValue>()
        val conditionalAssignments = linkedMapOf<ValueId, SourceConditionalAssignment>()
        val twoArmAssignments = linkedMapOf<ValueId, SourceTwoArmAssignment>()
        val loopAssignments = linkedMapOf<ValueId, SourceLoopAssignment>()
        val localFamilies = linkedMapOf<ValueId, SourceLocalFamily>()
        val suppressedDefinitions = linkedSetOf<ValueId>()
        val consumedIfHeaders = linkedSetOf<BasicBlockId>()

        // Some boolean materialization diamonds are immediately followed by another conditional
        // that consumes the phi. The structural recognizer can legitimately choose the latter
        // condition as the source-level if, leaving the tiny 0/1 diamond itself unstructured.
        // Recover that lost source expression directly from the normal CFG, but only when the
        // diamond is otherwise semantically empty and its two constants are single-use phi inputs.
        val structuredHeaders = structure.regions.mapTo(linkedSetOf()) { it.header }
        val normalOutgoingByBlock = controlFlow.edges.asSequence()
            .filter { it.kind != ControlFlowEdgeKind.EXCEPTION }
            .groupBy { it.from }
        val normalIncomingByBlock = controlFlow.edges.asSequence()
            .filter { it.kind != ControlFlowEdgeKind.EXCEPTION }
            .groupBy { it.to }

        phisByBlock.forEach { (phiBlock, phiValues) ->
            phiValues.forEach phiLoop@{ phiValue ->
                if (phiValue.id !in expression.materialization.booleanValues) return@phiLoop
                val phi = phiValue.node as ExpressionNode.Phi
                if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop
                if (phi.inputs.any { !isSingleUseConstant(it.value, phiValue.id, ssa, expression) }) return@phiLoop

                val armBlocks = phi.inputs.mapTo(linkedSetOf()) { it.predecessor!! }
                if (armBlocks.size != 2) return@phiLoop
                if (armBlocks.any { arm ->
                        val outgoing = normalOutgoingByBlock[arm].orEmpty()
                        outgoing.size != 1 || outgoing.single().to != phiBlock
                    }
                ) return@phiLoop

                val inputValues = phi.inputs.mapTo(linkedSetOf()) { it.value }
                val definedArmValues = armBlocks.flatMap { definedValuesByBlock[it].orEmpty() }.toSet()
                if (definedArmValues != inputValues) return@phiLoop
                if (armBlocks.any { phisByBlock[it].orEmpty().isNotEmpty() }) return@phiLoop
                if (armBlocks.flatMap { statementsByBlock[it].orEmpty() }.any { statement ->
                        statement !is ExpressionStatement.Branch || statement.condition != null
                    }
                ) return@phiLoop

                val commonHeaders = armBlocks
                    .map { arm -> normalIncomingByBlock[arm].orEmpty().mapTo(linkedSetOf()) { it.from } }
                    .reduce { left, right -> left.apply { retainAll(right) } }
                val header = commonHeaders.singleOrNull() ?: return@phiLoop
                if (header in structuredHeaders) return@phiLoop
                val outgoing = normalOutgoingByBlock[header].orEmpty()
                if (outgoing.size != 2 || outgoing.mapTo(linkedSetOf()) { it.to } != armBlocks) return@phiLoop
                val taken = outgoing.singleOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to ?: return@phiLoop
                val untaken = outgoing.singleOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH || it.kind == ControlFlowEdgeKind.JUMP }?.to ?: return@phiLoop
                if (taken == untaken) return@phiLoop

                val branch = statementsByBlock[header].orEmpty()
                    .filterIsInstance<ExpressionStatement.Branch>()
                    .singleOrNull { it.condition != null }
                    ?: return@phiLoop
                val takenValue = phi.inputs.singleOrNull { it.predecessor == taken }?.value ?: return@phiLoop
                val untakenValue = phi.inputs.singleOrNull { it.predecessor == untaken }?.value ?: return@phiLoop

                conditionalValues[phiValue.id] = SourceConditionalValue(
                    StructuredCondition.Atomic(branch.condition!!),
                    thenValue = takenValue,
                    elseValue = untakenValue,
                )
                suppressedDefinitions += inputValues
                consumedIfHeaders += header
            }
        }

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
                    // Constants used only by a phi are commonly marked inline by expression materialization.
                    // They still belong to the bytecode arms and prove a pure materialization diamond; requiring
                    // an emitted definition here leaves an empty if plus a raw phi in source output.
                    val definedArmValues = armBlocks.flatMap { definedValuesByBlock[it].orEmpty() }.toSet()
                    val hasSemanticStatement = armBlocks.flatMap { statementsByBlock[it].orEmpty() }.any { it !is ExpressionStatement.Branch }
                    val hasArmPhi = armBlocks.any { phisByBlock[it].orEmpty().isNotEmpty() }
                    if (definedArmValues == candidateInputs && !hasSemanticStatement && !hasArmPhi) {
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
            val hasNestedRegion = assignmentBlocks.any { it in nestedHeaders }

            // javac sometimes implements a conditional value by copying one already-computed
            // local in the populated arm. SSA eliminates that copy, leaving an empty structured
            // arm and a phi whose two inputs were both defined before the branch. Preserve their
            // eager evaluation and collapse only the now-empty control-flow shell.
            val assignmentArmHasDefinedValues = assignmentBlocks.any { definedValuesByBlock[it].orEmpty().isNotEmpty() }
            val assignmentArmHasSemanticStatement = assignmentBlocks
                .flatMap { statementsByBlock[it].orEmpty() }
                .any { it !is ExpressionStatement.Branch }
            val assignmentArmHasPhi = assignmentBlocks.any { phisByBlock[it].orEmpty().isNotEmpty() }
            if (!hasNestedRegion && !assignmentArmHasDefinedValues && !assignmentArmHasSemanticStatement && !assignmentArmHasPhi) {
                phisByBlock[region.continuation].orEmpty().forEach phiLoop@{ phiValue ->
                    val phi = phiValue.node as ExpressionNode.Phi
                    if (phi.inputs.size != 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop
                    val inheritedInput = phi.inputs.singleOrNull { it.predecessor == region.header }?.value ?: return@phiLoop
                    val selectedInput = phi.inputs.singleOrNull { it.predecessor in assignmentBlocks }?.value ?: return@phiLoop
                    if (materializedValueBlocks[inheritedInput] != region.header) return@phiLoop
                    if (materializedValueBlocks[selectedInput] != region.header) return@phiLoop

                    val populatedArmIsThen = region.thenBlocks.isNotEmpty()
                    conditionalValues[phiValue.id] = SourceConditionalValue(
                        region.condition,
                        thenValue = if (populatedArmIsThen) selectedInput else inheritedInput,
                        elseValue = if (populatedArmIsThen) inheritedInput else selectedInput,
                    )
                    consumedIfHeaders += region.header
                }
                if (region.header in consumedIfHeaders) return@forEach
            }

            phisByBlock[region.continuation].orEmpty().forEach phiLoop@{ phiValue ->
                if (!canDeclareSourceLocal(phiValue)) return@phiLoop
                val phi = phiValue.node as ExpressionNode.Phi
                // A one-arm merge is source-representable whether the value lives in a local or on the operand stack.
                // An explicit temporary declared before the if preserves either JVM representation.
                if (phi.inputs.size < 2 || phi.inputs.any { it.predecessor == null }) return@phiLoop
                val assignedInputs = phi.inputs.filter { it.predecessor in assignmentBlocks }
                if (assignedInputs.size != 1) return@phiLoop
                val assignedInput = assignedInputs.single().value
                val inheritedInputs = phi.inputs.filter { it.predecessor !in assignmentBlocks }
                val initialInput = inheritedInputs.map { it.value }.distinct().singleOrNull() ?: return@phiLoop
                if (inheritedInputs.isEmpty()) return@phiLoop
                val initialValue = expression.values[initialInput] ?: return@phiLoop
                val initialBlock = materializedValueBlocks[initialInput]
                val initialIsRoot = initialValue.node is ExpressionNode.Root
                // Boolean classification may inline the 0/1 initializer of a real JVM local.
                // Keep the richer source-local reconstruction in that case; unlike a stack phi,
                // the local slot itself proves that an explicit source variable existed.
                val initialIsInlineLocalConstant =
                    phi.location is SsaPhiLocation.Local &&
                        initialInput in expression.materialization.inlineValues &&
                        initialValue.node is ExpressionNode.Constant
                if (!initialIsRoot && !initialIsInlineLocalConstant &&
                    (initialBlock == null || (initialBlock != region.header && initialBlock !in normalDominators[region.header].orEmpty()))
                ) return@phiLoop

                val assignedConditional = conditionalValues[assignedInput]
                val assignedConditionalBlock = (expression.values[assignedInput]?.node as? ExpressionNode.Phi)?.blockId
                val assignedConditionalRegion = if (assignedConditionalBlock == null) {
                    null
                } else {
                    structure.regions.filterIsInstance<StructuredRegion.If>().singleOrNull { nested ->
                        nested.continuation == assignedConditionalBlock &&
                            nested.header in assignmentBlocks &&
                            (nested.thenBlocks + nested.elseBlocks).all { it in assignmentBlocks }
                    }
                }
                // Regions are visited in structural order, so an outer one-arm assignment can be
                // seen before the nested diamond that reconstructs its RHS. The nested region
                // itself is sufficient proof that this phi will become a SourceConditionalValue;
                // do not make recognition depend on conditionalValues insertion order.
                val assignedIsConditional = assignedConditional != null || assignedConditionalRegion != null
                val assignedBelongsToArm = materializedValueBlocks[assignedInput] in assignmentBlocks ||
                    (assignedIsConditional && (
                        assignedConditionalBlock in assignmentBlocks ||
                            assignedConditionalRegion != null
                        ))
                if (!assignedBelongsToArm) return@phiLoop
                if (hasNestedRegion && assignedConditionalRegion == null) return@phiLoop

                val initialIsSingleUse = isSingleUsePhiInput(initialInput, phiValue.id, ssa, expression)
                if (!initialIsSingleUse && !isPhiInput(initialInput, phiValue.id, ssa)) return@phiLoop
                if (!assignedIsConditional) {
                    if (!isSingleUsePhiInput(assignedInput, phiValue.id, ssa, expression)) return@phiLoop
                } else if (!isSingleUsePhiReference(assignedInput, phiValue.id, ssa)) {
                    return@phiLoop
                }

                conditionalAssignments[phiValue.id] = SourceConditionalAssignment(
                    initialInput,
                    assignedInput,
                    declarationHeader = if (initialIsSingleUse) null else region.header,
                )
            }
        }

        val operationsByIndex = ssa.operations.associateBy { it.instructionIndex }
        val booleanLocals = conditionalAssignments.filter { (phi, assignment) ->
            isBooleanCarrierValue(assignment.initialValue, expression, conditionalValues) &&
                isBooleanCarrierValue(assignment.assignedValue, expression, conditionalValues) &&
                (
                    phi in expression.materialization.booleanValues ||
                        ssa.uses[phi].orEmpty().let { uses ->
                            uses.isNotEmpty() && uses.all { isBooleanUse(it, operationsByIndex) }
                        }
                    )
        }.keys

        return SourceLocalAnalysis(
            conditionalValues,
            conditionalAssignments,
            twoArmAssignments,
            loopAssignments,
            localFamilies,
            booleanLocals,
            suppressedDefinitions,
            consumedIfHeaders,
        )
    }

    private fun isBooleanCarrierValue(
        valueId: ValueId,
        expression: ExpressionAnalysis,
        conditionalValues: Map<ValueId, SourceConditionalValue>,
        visited: Set<ValueId> = emptySet(),
    ): Boolean {
        if (valueId in visited) return false
        val value = expression.values[valueId] ?: return false
        if (value.type.isBoolean || valueId in expression.materialization.booleanValues) return true
        val constant = (value.node as? ExpressionNode.Constant)?.value
        if (constant?.equals(0) == true || constant?.equals(1) == true) return true
        val conditional = conditionalValues[valueId] ?: return false
        val nextVisited = visited + valueId
        return isBooleanCarrierValue(conditional.thenValue, expression, conditionalValues, nextVisited) &&
            isBooleanCarrierValue(conditional.elseValue, expression, conditionalValues, nextVisited)
    }

    private fun isBooleanUse(use: SsaValueUse, operationsByIndex: Map<Int, ValueOperation>): Boolean {
        val operationUse = use as? SsaValueUse.Operation ?: return false
        return when (val instruction = operationsByIndex[operationUse.instructionIndex]?.instruction) {
            is RawBranchInstruction -> instruction.opcode.mnemonic == "ifeq" || instruction.opcode.mnemonic == "ifne"
            is RawInvokeInstruction -> {
                val receiverOffset = if (instruction.opcode.mnemonic == "invokestatic") 0 else 1
                instruction.type.parameterTypes.getOrNull(operationUse.inputIndex - receiverOffset) == JvmType.BooleanType
            }
            is RawInvokeDynamicInstruction ->
                instruction.type.parameterTypes.getOrNull(operationUse.inputIndex) == JvmType.BooleanType
            is RawFieldInstruction -> when (instruction.opcode.mnemonic) {
                "putstatic" -> operationUse.inputIndex == 0 && instruction.type == JvmType.BooleanType
                "putfield" -> operationUse.inputIndex == 1 && instruction.type == JvmType.BooleanType
                else -> false
            }
            else -> false
        }
    }

    private fun canDeclareSourceLocal(value: ExpressionValue): Boolean = when (val type = value.type) {
        is JvmValueType.Computational -> type.type != JvmComputationalType.RETURN_ADDRESS
        is JvmValueType.Reference -> type.referenceType is JvmReferenceType.Exact
    }

    private fun isPhiInput(valueId: ValueId, phiOutput: ValueId, ssa: SsaAnalysis): Boolean =
        ssa.uses[valueId].orEmpty().any { (it as? SsaValueUse.Phi)?.output == phiOutput }

    private fun isSingleUsePhiInput(
        valueId: ValueId,
        phiOutput: ValueId,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
    ): Boolean {
        val value = expression.values[valueId] ?: return false
        if (value.node is ExpressionNode.Phi || value.instructionIndices.isEmpty()) return false
        if (valueId in expression.materialization.inlineValues) return false
        return isSingleUsePhiReference(valueId, phiOutput, ssa)
    }

    private fun isSingleUsePhiReference(valueId: ValueId, phiOutput: ValueId, ssa: SsaAnalysis): Boolean {
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
