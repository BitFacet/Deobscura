package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.SsaPhiLocation
import io.github.relvl.deobscura.analysis.SsaValueUse
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.isBoolean
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.dominators
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType

/**
 * Source-level variables introduced while destroying analysis SSA.
 *
 * This is the correctness-oriented source projection. It deliberately does not require a pretty
 * Java construct: source rewrites may turn suitable phis into ternaries/loop locals first, while
 * every remaining ordinary phi is lowered conservatively whenever all incoming assignments have a
 * semantics-preserving source placement.
 */
data class SourceVariableAnalysis(
    val variables: Map<ValueId, SourceVariable> = emptyMap(),
    /** SSA phi versions that should render as the corresponding source variable. */
    val valueBindings: Map<ValueId, ValueId> = emptyMap(),
    /** Declarations emitted before the physical/source node owning this block. */
    val declarationsBeforeBlock: Map<BasicBlockId, List<ValueId>> = emptyMap(),
    /** Assignments that implement incoming phi copies at proven source positions. */
    val assignments: List<SourceVariableAssignment> = emptyList(),
    val loweredPhiValues: Set<ValueId> = emptySet(),
    /** Ordinary phi nodes that still need structured/lexical placement or a higher-level rewrite. */
    val unresolvedNormalPhiValues: Set<ValueId> = emptySet(),
    /** Conservative handler/frame-origin merges. These await per-instruction exceptional SSA. */
    val exceptionalPhiValues: Set<ValueId> = emptySet(),
) {
    val assignmentsAtInstruction: Map<Int, List<SourceVariableAssignment>>
        get() = assignments.asSequence()
            .filter { it.site is SourceVariableAssignmentSite.Instruction }
            .groupBy { (it.site as SourceVariableAssignmentSite.Instruction).instructionIndex }

    val assignmentsAfterBlock: Map<BasicBlockId, List<SourceVariableAssignment>>
        get() = assignments.asSequence()
            .filter { it.site is SourceVariableAssignmentSite.BlockExit }
            .groupBy { (it.site as SourceVariableAssignmentSite.BlockExit).block }

    val assignmentsOnEdge: Map<Pair<BasicBlockId, BasicBlockId>, List<SourceVariableAssignment>>
        get() = assignments.asSequence()
            .filter { it.site is SourceVariableAssignmentSite.Edge }
            .groupBy { assignment ->
                val site = assignment.site as SourceVariableAssignmentSite.Edge
                site.from to site.to
            }

    val booleanVariables: Set<ValueId>
        get() = variables.values.asSequence().filter { it.isBoolean }.mapTo(linkedSetOf()) { it.id }
}

data class SourceVariable(
    val id: ValueId,
    val type: JvmValueType,
    val origin: SourceVariableOrigin,
    /** Phi versions represented by this one source variable. */
    val phiValues: Set<ValueId>,
    val declarationBlock: BasicBlockId,
    val isBoolean: Boolean = false,
)

sealed interface SourceVariableOrigin {
    /** A JVM local slot whose SSA versions are being projected back into one source variable. */
    data class Local(val slot: Int) : SourceVariableOrigin

    /** A source temporary needed to express a merge that existed only on the JVM operand stack. */
    data class SyntheticStack(val index: Int) : SourceVariableOrigin
}

/**
 * One copy required by SSA destruction.
 *
 * [phiBlock] is retained as provenance/diagnostic context. The actual source position is [site].
 */
data class SourceVariableAssignment(
    val variable: ValueId,
    val value: ValueId,
    val phiBlock: BasicBlockId,
    val site: SourceVariableAssignmentSite,
)

/** Proven placement of an SSA copy in source order. */
sealed interface SourceVariableAssignmentSite {
    /** The original JVM local write already provides the exact source-order assignment point. */
    data class Instruction(val instructionIndex: Int) : SourceVariableAssignmentSite

    /** Safe only when every normal path leaving [block] enters the corresponding phi block. */
    data class BlockExit(val block: BasicBlockId) : SourceVariableAssignmentSite

    /** Copy performed only when control follows one explicit source-visible CFG edge. */
    data class Edge(val from: BasicBlockId, val to: BasicBlockId) : SourceVariableAssignmentSite
}

/**
 * Performs the correctness-oriented part of SSA destruction.
 *
 * Existing [SourceRewriteAnalysis] rewrites remain the preferred cosmetic forms. This analyzer only
 * handles the phi nodes they did not claim, so adding a pretty reconstruction can never be required
 * merely to keep raw `phi(...)` out of otherwise representable source.
 */
class SourceVariableAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        ssa: SsaAnalysis,
        expression: ExpressionAnalysis,
        controlFlow: SsaControlFlowGraph,
        sourceStructure: SourceStructureAnalysis,
        preferredRewrites: SourceRewriteAnalysis,
    ): SourceVariableAnalysis {
        val entry = controlFlow.entryBlock ?: return SourceVariableAnalysis()
        val reachable = controlFlow.reachableBlocks()
        val normalEdges = controlFlow.edges.filter {
            it.kind != ControlFlowEdgeKind.EXCEPTION && it.from in reachable && it.to in reachable
        }
        val predecessors = normalEdges.groupBy({ it.to }, { it.from }).mapValues { (_, blocks) -> blocks.distinct() }
        val dominators = dominators(reachable, entry, predecessors)
        val claimed = preferredRewrites.projectedPhiValues

        val allPhis = expression.values.values
            .filter { it.node is ExpressionNode.Phi }
            .associateBy { it.id }
        val exceptional = allPhis.values.asSequence()
            .filter { it.id !in claimed }
            .filter { value -> (value.node as ExpressionNode.Phi).inputs.any { it.predecessor == null } }
            .mapTo(linkedSetOf()) { it.id }
        val candidates = allPhis.values.filter { value ->
            value.id !in claimed &&
                value.id !in exceptional &&
                (value.node as ExpressionNode.Phi).blockId in reachable &&
                canDeclareSourceVariable(value)
        }.associateBy { it.id }

        val assignmentPlacer = SourceVariableAssignmentPlacer(
            graph = graph,
            ssa = ssa,
            controlFlow = controlFlow,
            sourceStructure = sourceStructure,
            dominators = dominators,
        )
        val sourceBoundEdgeInputs = candidates.keys + preferredRewrites.sourceVariableBindings.keys

        val variables = linkedMapOf<ValueId, SourceVariable>()
        val bindings = linkedMapOf<ValueId, ValueId>()
        val declarations = linkedMapOf<BasicBlockId, MutableList<ValueId>>()
        val assignments = mutableListOf<SourceVariableAssignment>()
        val lowered = linkedSetOf<ValueId>()
        val visited = linkedSetOf<ValueId>()

        candidates.values.sortedBy { it.id.value }.forEach { seed ->
            if (seed.id in visited) return@forEach
            val web = collectPhiWeb(seed, candidates, ssa)
            visited += web
            val projection = projectWeb(
                web = web,
                candidates = candidates,
                dominators = dominators,
                expression = expression,
                assignmentPlacer = assignmentPlacer,
                sourceBoundEdgeInputs = sourceBoundEdgeInputs,
            ) ?: return@forEach

            variables[projection.variable.id] = projection.variable
            projection.variable.phiValues.forEach { value -> bindings[value] = projection.variable.id }
            lowered += projection.variable.phiValues
            declarations.getOrPut(projection.variable.declarationBlock) { mutableListOf() } += projection.variable.id
            assignments += projection.assignments
        }

        val ordinaryUnclaimed = allPhis.keys - claimed - exceptional
        return SourceVariableAnalysis(
            variables = variables,
            valueBindings = bindings,
            declarationsBeforeBlock = declarations.mapValues { (_, values) -> values.distinct() },
            assignments = assignments.distinct(),
            loweredPhiValues = lowered,
            unresolvedNormalPhiValues = ordinaryUnclaimed - lowered,
            exceptionalPhiValues = exceptional,
        )
    }

    private fun collectPhiWeb(
        seed: ExpressionValue,
        candidates: Map<ValueId, ExpressionValue>,
        ssa: SsaAnalysis,
    ): Set<ValueId> {
        val seedPhi = seed.node as ExpressionNode.Phi
        // JVM local slot identity is useful provenance. Stack phis are synthetic source temporaries
        // and intentionally remain one variable per merge until a later coalescer proves more.
        val slot = (seedPhi.location as? SsaPhiLocation.Local)?.slot ?: return setOf(seed.id)
        val result = linkedSetOf<ValueId>()
        val pending = ArrayDeque<ValueId>()
        pending += seed.id
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!result.add(id)) continue
            val phi = candidates[id]?.node as? ExpressionNode.Phi ?: continue
            if ((phi.location as? SsaPhiLocation.Local)?.slot != slot) continue

            phi.inputs.forEach { input ->
                val inputPhi = candidates[input.value]?.node as? ExpressionNode.Phi
                if ((inputPhi?.location as? SsaPhiLocation.Local)?.slot == slot) pending += input.value
            }
            ssa.uses[id].orEmpty().forEach { use ->
                val phiUse = use as? SsaValueUse.Phi ?: return@forEach
                val outputPhi = candidates[phiUse.output]?.node as? ExpressionNode.Phi
                if ((outputPhi?.location as? SsaPhiLocation.Local)?.slot == slot) pending += phiUse.output
            }
        }
        return result
    }

    private fun projectWeb(
        web: Set<ValueId>,
        candidates: Map<ValueId, ExpressionValue>,
        dominators: Map<BasicBlockId, Set<BasicBlockId>>,
        expression: ExpressionAnalysis,
        assignmentPlacer: SourceVariableAssignmentPlacer,
        sourceBoundEdgeInputs: Set<ValueId>,
    ): WebProjection? {
        val phiEntries = web.map { id -> id to (candidates[id]?.node as? ExpressionNode.Phi ?: return null) }
        val phis = phiEntries.map { it.second }
        if (phis.any { it.inputs.any { input -> input.predecessor == null } }) return null

        // A source variable needs one declaration type. Connected phi versions of one JVM slot can
        // still carry different verifier types (slot reuse or progressively wider reference merges),
        // so defer those webs until a dedicated source type/coalescing pass can prove a common type.
        val types = web.map { candidates.getValue(it).type }.distinct()
        if (types.size != 1) return null
        val variableType = types.single()

        val locations = phis.map { it.location }.distinct()
        val origin = when {
            locations.all { it is SsaPhiLocation.Local } -> {
                val slots = locations.map { (it as SsaPhiLocation.Local).slot }.distinct()
                if (slots.size != 1) return null
                SourceVariableOrigin.Local(slots.single())
            }

            web.size == 1 && locations.singleOrNull() is SsaPhiLocation.Stack ->
                SourceVariableOrigin.SyntheticStack((locations.single() as SsaPhiLocation.Stack).index)

            else -> return null
        }

        val boundary = phis.flatMap { phi ->
            phi.inputs.mapNotNull { input ->
                if (input.value in web) return@mapNotNull null
                Boundary(phi, input.value, input.predecessor ?: return null)
            }
        }.distinct()
        if (boundary.isEmpty()) return null
        if (boundary.any { input ->
                val inputType = expression.values[input.value]?.type ?: return null
                !canAssignSourceValue(variableType, inputType)
            }
        ) return null

        val targetId = phiEntries.minWithOrNull(
            compareBy<Pair<ValueId, ExpressionNode.Phi>> { dominators[it.second.blockId].orEmpty().size }
                .thenBy { it.first.value },
        )?.first ?: return null
        val rawAssignments = boundary.map { input ->
            val site = assignmentPlacer.place(
                origin = origin,
                value = input.value,
                predecessor = input.predecessor,
                phiBlock = input.phi.blockId,
            ) ?: return null
            // Edge copies are emitted beside explicit control transfer. Until we have a parallel-copy
            // scheduler, do not let a new edge copy read another projected source variable whose
            // value could itself be updated at the same transfer. Exact stores and whole-block exits
            // retain their existing source-order proofs; this restriction applies only to new edges.
            if (site.site is SourceVariableAssignmentSite.Edge && input.value in sourceBoundEdgeInputs) return null
            PlacedSourceAssignment(
                assignment = SourceVariableAssignment(
                    variable = targetId,
                    value = input.value,
                    phiBlock = input.phi.blockId,
                    site = site.site,
                ),
                block = site.block,
            )
        }

        // One source position cannot assign two different values to the same projected variable.
        if (rawAssignments.groupBy { it.assignment.site }.any { (_, atSite) ->
                atSite.map { it.assignment.value }.distinct().size > 1
            }
        ) return null
        // A connected phi web can mention the same incoming value at the same physical store/exit
        // for several internal joins. One source assignment updates the whole projected variable.
        val assignments = rawAssignments.distinctBy { it.assignment.site to it.assignment.value }

        val phiBlocks = phis.mapTo(linkedSetOf()) { it.blockId }
        val placementBlocks = rawAssignments.mapTo(linkedSetOf()) { it.block } + phiBlocks
        val commonDominators = placementBlocks
            .map { dominators[it].orEmpty() }
            .reduceOrNull { left, right -> left intersect right }
            .orEmpty()
        val declarationBlock = commonDominators.maxByOrNull { dominators[it].orEmpty().size } ?: return null
        if (placementBlocks.any { declarationBlock !in dominators[it].orEmpty() }) return null

        val boolean = web.any { it in expression.materialization.booleanValues } || variableType.isBoolean
        val variable = SourceVariable(
            id = targetId,
            type = variableType,
            origin = origin,
            phiValues = web,
            declarationBlock = declarationBlock,
            isBoolean = boolean,
        )
        return WebProjection(variable, assignments.map { it.assignment })
    }

    private fun canDeclareSourceVariable(value: ExpressionValue): Boolean = when (val type = value.type) {
        is JvmValueType.Computational -> type.type != JvmComputationalType.RETURN_ADDRESS
        is JvmValueType.Reference -> type.referenceType is JvmReferenceType.Exact
    }

    private fun canAssignSourceValue(target: JvmValueType, source: JvmValueType): Boolean = when {
        target == source -> true
        target is JvmValueType.Reference && source is JvmValueType.Reference ->
            source.referenceType != JvmReferenceType.Unknown

        target is JvmValueType.Computational && source is JvmValueType.Computational ->
            target.type == JvmComputationalType.BOOLEAN ||
                (target.type != JvmComputationalType.RETURN_ADDRESS &&
                    source.type != JvmComputationalType.BOOLEAN &&
                    source.type != JvmComputationalType.RETURN_ADDRESS)

        else -> false
    }

    private data class Boundary(
        val phi: ExpressionNode.Phi,
        val value: ValueId,
        val predecessor: BasicBlockId,
    )

    private data class PlacedSourceAssignment(
        val assignment: SourceVariableAssignment,
        val block: BasicBlockId,
    )

    private data class WebProjection(
        val variable: SourceVariable,
        val assignments: List<SourceVariableAssignment>,
    )
}
