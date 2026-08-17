package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.BranchCondition
import io.github.relvl.deobscura.expression.BranchOperand
import io.github.relvl.deobscura.expression.ComparisonOperator
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import java.util.ArrayDeque

/** Recognizes conservative single-entry reducible `if` and natural `while` regions. */
class StructuredControlFlowAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        flow: SsaControlFlowGraph,
        expression: ExpressionAnalysis,
    ): StructuredControlFlowAnalysis {
        val blocks = flow.reachableBlocks()
        if (blocks.isEmpty()) return StructuredControlFlowAnalysis(emptyList(), 0, 0)

        val normalEdges = flow.edges.filter { it.kind != ControlFlowEdgeKind.EXCEPTION && it.from in blocks && it.to in blocks }
        val outgoing = normalEdges.groupBy { it.from }
        val predecessors = normalEdges.groupBy { it.to }.mapValues { (_, edges) -> edges.map { it.from }.distinct() }
        val instructionToBlock = instructionToBlock(graph)
        val originalBranches = branchesByBlock(graph, expression, blocks, instructionToBlock)
        val switchHeaders = switchesByBlock(expression, blocks, instructionToBlock)
        val folds = findBooleanConditionFolds(
            expression = expression,
            branches = originalBranches,
            outgoing = outgoing,
            instructionToBlock = instructionToBlock,
        )
        val foldedProducerHeaders = folds.mapTo(hashSetOf()) { it.producerHeader }
        val foldedConditions = folds.associate { it.consumerHeader to it.condition }
        val branches = originalBranches.asSequence()
            .filter { (header, _) -> header !in foldedProducerHeaders }
            .associate { (header, branch) -> header to (foldedConditions[header]?.let { branch.copy(condition = it) } ?: branch) }

        if (branches.isEmpty()) {
            val diagnostics = switchHeaders.map {
                UnstructuredControlFlowDiagnostic(it, UnstructuredControlFlowKind.SWITCH, UnstructuredControlFlowReason.SWITCH_DEFERRED)
            }
            return StructuredControlFlowAnalysis(emptyList(), originalBranches.size, switchHeaders.size, folds, diagnostics)
        }

        val dominators = dominators(blocks, requireNotNull(flow.entryBlock), predecessors)
        val loopRecognition = findNaturalLoops(normalEdges, outgoing, predecessors, branches, dominators)
        val loopHeaders = loopRecognition.regions.mapTo(hashSetOf()) { it.header }
        val postDominators = postDominators(blocks, outgoing)
        val ifRecognition = findIfRegions(blocks, outgoing, predecessors, branches, postDominators, loopHeaders)

        val regions = (loopRecognition.regions + ifRecognition.regions).sortedWith(
            compareBy<StructuredRegion> { it.header.value }
                .thenBy { if (it is StructuredRegion.While) 0 else 1 },
        )
        val recognizedHeaders = regions.mapTo(hashSetOf()) { it.header }
        recognizedHeaders += foldedProducerHeaders

        val diagnostics = buildList {
            originalBranches.keys.sortedBy { it.value }.forEach { header ->
                if (header in recognizedHeaders) return@forEach
                val reason = loopRecognition.rejections[header]
                    ?: ifRecognition.rejections[header]
                    ?: UnstructuredControlFlowReason.UNSUPPORTED_SHAPE
                add(UnstructuredControlFlowDiagnostic(header, UnstructuredControlFlowKind.CONDITIONAL, reason))
            }
            switchHeaders.sortedBy { it.value }.forEach { header ->
                add(UnstructuredControlFlowDiagnostic(header, UnstructuredControlFlowKind.SWITCH, UnstructuredControlFlowReason.SWITCH_DEFERRED))
            }
        }
        return StructuredControlFlowAnalysis(regions, originalBranches.size, switchHeaders.size, folds, diagnostics)
    }

    private fun instructionToBlock(graph: ControlFlowGraph): Array<BasicBlockId?> =
        arrayOfNulls<BasicBlockId>(graph.code.instructions.size).also { result ->
            graph.blocks.forEach { block ->
                for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                    result[index] = block.id
                }
            }
        }

    private fun branchesByBlock(
        graph: ControlFlowGraph,
        expression: ExpressionAnalysis,
        blocks: Set<BasicBlockId>,
        instructionToBlock: Array<BasicBlockId?> = instructionToBlock(graph),
    ): Map<BasicBlockId, ExpressionStatement.Branch> = expression.statements.asSequence()
        .filterIsInstance<ExpressionStatement.Branch>()
        .filter { it.condition != null }
        .mapNotNull { statement ->
            val block = instructionToBlock.getOrNull(statement.instructionIndex) ?: return@mapNotNull null
            block.takeIf { it in blocks }?.let { it to statement }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (block, statements) ->
            if (statements.size != 1) {
                throw StructuredControlFlowInconsistencyException(
                    "Basic block B${block.value} contains ${statements.size} conditional branch statements.",
                )
            }
            statements.single()
        }

    private fun switchesByBlock(
        expression: ExpressionAnalysis,
        blocks: Set<BasicBlockId>,
        instructionToBlock: Array<BasicBlockId?>,
    ): Set<BasicBlockId> = expression.statements.asSequence()
        .filterIsInstance<ExpressionStatement.Switch>()
        .mapNotNull { instructionToBlock.getOrNull(it.instructionIndex) }
        .filter { it in blocks }
        .toCollection(linkedSetOf())

    /**
     * Folds the javac-style `cond -> true/false blocks -> boolean phi -> if(phi)` shape into the
     * original condition. This is a source-view transformation only; SSA and canonical CFG remain
     * untouched for provenance and later low-level diagnostics.
     */
    private fun findBooleanConditionFolds(
        expression: ExpressionAnalysis,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        instructionToBlock: Array<BasicBlockId?>,
    ): List<BooleanConditionFold> {
        if (expression.materialization.booleanValues.isEmpty()) return emptyList()
        val valuesByBlock = expression.values.values.asSequence()
            .filter { it.instructionIndices.isNotEmpty() }
            .groupBy { instructionToBlock.getOrNull(it.instructionIndices.last()) }
        val statementsByBlock = expression.statements.groupBy { instructionToBlock.getOrNull(it.instructionIndex) }
        val result = mutableListOf<BooleanConditionFold>()
        val occupiedConsumers = hashSetOf<BasicBlockId>()
        val branchConsumersByValue = branches.values.mapNotNull { it.condition?.left }.groupingBy { it }.eachCount()

        branches.forEach { (producerHeader, producerBranch) ->
            val sourceCondition = producerBranch.condition ?: return@forEach
            val producerEdges = outgoing[producerHeader].orEmpty()
            val conditional = producerEdges.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL } ?: return@forEach
            val fallthrough = producerEdges.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH } ?: return@forEach
            if (conditional.to == fallthrough.to) return@forEach

            val conditionalSuccessor = singleForwardTarget(conditional.to, outgoing) ?: return@forEach
            val fallthroughSuccessor = singleForwardTarget(fallthrough.to, outgoing) ?: return@forEach
            if (conditionalSuccessor != fallthroughSuccessor) return@forEach
            val consumerHeader = conditionalSuccessor
            if (consumerHeader in occupiedConsumers) return@forEach
            if (!isTransparentBooleanArm(conditional.to, valuesByBlock, statementsByBlock, expression)) return@forEach
            if (!isTransparentBooleanArm(fallthrough.to, valuesByBlock, statementsByBlock, expression)) return@forEach

            val consumerBranch = branches[consumerHeader] ?: return@forEach
            val consumerCondition = consumerBranch.condition ?: return@forEach
            val phiValue = consumerCondition.left
            if (phiValue !in expression.materialization.booleanValues) return@forEach
            if (branchConsumersByValue[phiValue] != 1) return@forEach
            if (consumerCondition.right != BranchOperand.Zero) return@forEach
            if (consumerCondition.operator != ComparisonOperator.EQ && consumerCondition.operator != ComparisonOperator.NE) return@forEach
            val phi = expression.values[phiValue]?.node as? ExpressionNode.Phi ?: return@forEach
            if (phi.blockId != consumerHeader) return@forEach

            val byPredecessor = phi.inputs.mapNotNull { input -> input.predecessor?.let { it to input.value } }.toMap()
            val conditionalTruth = booleanConstant(byPredecessor[conditional.to], expression) ?: return@forEach
            val fallthroughTruth = booleanConstant(byPredecessor[fallthrough.to], expression) ?: return@forEach
            if (conditionalTruth == fallthroughTruth) return@forEach
            if (phi.inputs.any { it.predecessor != null && it.predecessor !in setOf(conditional.to, fallthrough.to) }) return@forEach

            // C is true on the CONDITIONAL edge. P either equals C or !C depending on phi constants.
            val phiNegatesSource = !conditionalTruth && fallthroughTruth
            // The consumer branch is true for P with ifne, false for P with ifeq.
            val consumerNegatesPhi = consumerCondition.operator == ComparisonOperator.EQ
            val effectiveCondition = if (phiNegatesSource.xor(consumerNegatesPhi)) sourceCondition.negated() else sourceCondition

            result += BooleanConditionFold(
                producerHeader = producerHeader,
                consumerHeader = consumerHeader,
                phiValue = phiValue,
                condition = effectiveCondition,
                materializationBlocks = linkedSetOf(conditional.to, fallthrough.to),
            )
            occupiedConsumers += consumerHeader
        }
        return result
    }

    private fun singleForwardTarget(
        block: BasicBlockId,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): BasicBlockId? {
        val targets = outgoing[block].orEmpty().distinctTargets().map { it.to }
        return targets.singleOrNull()
    }

    private fun isTransparentBooleanArm(
        block: BasicBlockId,
        valuesByBlock: Map<BasicBlockId?, List<io.github.relvl.deobscura.expression.ExpressionValue>>,
        statementsByBlock: Map<BasicBlockId?, List<ExpressionStatement>>,
        expression: ExpressionAnalysis,
    ): Boolean {
        if (valuesByBlock[block].orEmpty().any { it.id !in expression.materialization.inlineValues }) return false
        return statementsByBlock[block].orEmpty().all { it is ExpressionStatement.Branch && it.condition == null }
    }

    private fun booleanConstant(id: ValueId?, expression: ExpressionAnalysis): Boolean? {
        val constant = id?.let { expression.values[it]?.node } as? ExpressionNode.Constant ?: return null
        return when {
            constant.value.equals(0) -> false
            constant.value.equals(1) -> true
            else -> null
        }
    }

    private fun findNaturalLoops(
        edges: List<ControlFlowEdge>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        dominators: Map<BasicBlockId, Set<BasicBlockId>>,
    ): LoopRecognition {
        val regions = mutableListOf<StructuredRegion.While>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()
        val backEdges = edges.filter { edge -> edge.to in dominators[edge.from].orEmpty() }
        backEdges.groupBy { it.to }.forEach { (header, latchesEdges) ->
            val branch = branches[header] ?: return@forEach
            val condition = branch.condition ?: return@forEach
            val successors = outgoing[header].orEmpty().distinctTargets()
            if (successors.size != 2) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_NOT_TWO_SUCCESSORS
                return@forEach
            }

            val loopBlocks = linkedSetOf(header)
            val queue = ArrayDeque<BasicBlockId>()
            latchesEdges.map { it.from }.distinct().forEach {
                if (loopBlocks.add(it)) queue.addLast(it)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                predecessors[current].orEmpty().forEach { predecessor ->
                    if (loopBlocks.add(predecessor) && predecessor != header) queue.addLast(predecessor)
                }
            }
            if (!loopBlocks.all { header in dominators[it].orEmpty() }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_BODY_EXIT_SHAPE
                return@forEach
            }

            val bodySuccessors = successors.filter { it.to in loopBlocks && it.to != header }
            val exitSuccessors = successors.filter { it.to !in loopBlocks }
            if (bodySuccessors.size != 1 || exitSuccessors.size != 1) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_BODY_EXIT_SHAPE
                return@forEach
            }
            val bodyEdge = bodySuccessors.single()
            val exitEdge = exitSuccessors.single()

            val externalExits = loopBlocks.asSequence()
                .flatMap { outgoing[it].orEmpty().asSequence() }
                .filter { it.to !in loopBlocks }
                .toList()
            if (externalExits.any { it.from != header || it.to != exitEdge.to }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_HAS_ADDITIONAL_EXIT
                return@forEach
            }

            val body = loopBlocks - header
            if (body.any { block -> predecessors[block].orEmpty().any { it !in loopBlocks } }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_HAS_EXTERNAL_ENTRY
                return@forEach
            }

            val conditionalTarget = outgoing[header].orEmpty().firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to
            if (conditionalTarget == null) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_MISSING_CONDITIONAL_EDGE
                return@forEach
            }
            val negate = conditionalTarget != bodyEdge.to
            regions += StructuredRegion.While(
                header = header,
                condition = condition,
                negateCondition = negate,
                bodyEntry = bodyEdge.to,
                bodyBlocks = body.sortedBy { it.value }.toCollection(linkedSetOf()),
                exit = exitEdge.to,
                latches = latchesEdges.mapTo(linkedSetOf()) { it.from },
            )
        }
        return LoopRecognition(regions, rejections)
    }

    private fun findIfRegions(
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
        excludedHeaders: Set<BasicBlockId>,
    ): IfRecognition {
        val regions = mutableListOf<StructuredRegion.If>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()
        branches.forEach { (header, branch) ->
            if (header in excludedHeaders) return@forEach
            val condition = branch.condition ?: return@forEach
            val edges = outgoing[header].orEmpty()
            val conditional = edges.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }
            val fallthrough = edges.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH }
            if (conditional == null || fallthrough == null) {
                rejections[header] = UnstructuredControlFlowReason.MISSING_BRANCH_EDGES
                return@forEach
            }
            if (conditional.to == fallthrough.to) {
                rejections[header] = UnstructuredControlFlowReason.IDENTICAL_SUCCESSORS
                return@forEach
            }

            val join = immediatePostDominator(header, postDominators)
            if (join == null) {
                rejections[header] = UnstructuredControlFlowReason.NO_COMMON_POST_DOMINATOR
                return@forEach
            }
            if (join == header) {
                rejections[header] = UnstructuredControlFlowReason.INVALID_JOIN
                return@forEach
            }

            val thenAttempt = collectArm(conditional.to, join, header, blocks, outgoing)
            if (thenAttempt is ArmCollection.Rejected) {
                rejections[header] = thenAttempt.reason
                return@forEach
            }
            val elseAttempt = collectArm(fallthrough.to, join, header, blocks, outgoing)
            if (elseAttempt is ArmCollection.Rejected) {
                rejections[header] = elseAttempt.reason
                return@forEach
            }
            val thenBlocks = (thenAttempt as ArmCollection.Success).blocks
            val elseBlocks = (elseAttempt as ArmCollection.Success).blocks
            if (thenBlocks.intersect(elseBlocks).isNotEmpty()) {
                rejections[header] = UnstructuredControlFlowReason.OVERLAPPING_ARMS
                return@forEach
            }
            if (!singleEntryArm(thenBlocks, header, predecessors) || !singleEntryArm(elseBlocks, header, predecessors)) {
                rejections[header] = UnstructuredControlFlowReason.EXTERNAL_ARM_ENTRY
                return@forEach
            }

            regions += StructuredRegion.If(
                header = header,
                condition = condition,
                thenEntry = conditional.to.takeUnless { it == join },
                thenBlocks = thenBlocks,
                elseEntry = fallthrough.to.takeUnless { it == join },
                elseBlocks = elseBlocks,
                join = join,
            )
        }
        return IfRecognition(regions, rejections)
    }

    private fun collectArm(
        start: BasicBlockId,
        join: BasicBlockId,
        header: BasicBlockId,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): ArmCollection {
        if (start == join) return ArmCollection.Success(emptySet())
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == join) continue
            if (block == header) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in blocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue
            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
            successors.forEach { successor ->
                if (successor != join) queue.addLast(successor)
            }
        }
        if (result.any { block ->
                outgoing[block].orEmpty().distinctTargets().any { it.to !in result && it.to != join }
            }
        ) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_HAS_OTHER_EXIT)
        return ArmCollection.Success(result)
    }

    private fun singleEntryArm(
        arm: Set<BasicBlockId>,
        header: BasicBlockId,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
    ): Boolean = arm.all { block -> predecessors[block].orEmpty().all { it == header || it in arm } }

    private fun dominators(
        blocks: Set<BasicBlockId>,
        entry: BasicBlockId,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
    ): Map<BasicBlockId, Set<BasicBlockId>> {
        val all = blocks.toSet()
        val result = blocks.associateWithTo(linkedMapOf()) { block -> if (block == entry) setOf(entry) else all }
        var changed: Boolean
        do {
            changed = false
            for (block in blocks) {
                if (block == entry) continue
                val preds = predecessors[block].orEmpty().filter { it in blocks }
                val intersection = if (preds.isEmpty()) emptySet() else preds.map { result.getValue(it) }.reduce(Set<BasicBlockId>::intersect)
                val next = linkedSetOf(block).apply { addAll(intersection) }
                if (next != result[block]) {
                    result[block] = next
                    changed = true
                }
            }
        } while (changed)
        return result
    }

    private fun postDominators(
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): Map<BasicBlockId, Set<BasicBlockId>> {
        val all = blocks.toSet()
        val successors = blocks.associateWith { block -> outgoing[block].orEmpty().distinctTargets().map { it.to } }
        val exits = blocks.filterTo(hashSetOf()) { successors[it].orEmpty().isEmpty() }
        if (exits.isEmpty()) return blocks.associateWith { setOf(it) }
        val result = blocks.associateWithTo(linkedMapOf()) { block -> if (block in exits) setOf(block) else all }
        var changed: Boolean
        do {
            changed = false
            for (block in blocks) {
                if (block in exits) continue
                val succs = successors[block].orEmpty()
                if (succs.isEmpty()) continue
                val intersection = succs.map { result.getValue(it) }.reduce(Set<BasicBlockId>::intersect)
                val next = linkedSetOf(block).apply { addAll(intersection) }
                if (next != result[block]) {
                    result[block] = next
                    changed = true
                }
            }
        } while (changed)
        return result
    }

    private fun immediatePostDominator(
        block: BasicBlockId,
        postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
    ): BasicBlockId? {
        val strict = postDominators[block].orEmpty() - block
        return strict.firstOrNull { candidate ->
            strict.none { other -> other != candidate && candidate in postDominators[other].orEmpty() }
        }
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

    private fun List<ControlFlowEdge>.distinctTargets(): List<ControlFlowEdge> = distinctBy { it.to }

    private data class LoopRecognition(
        val regions: List<StructuredRegion.While>,
        val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
    )

    private data class IfRecognition(
        val regions: List<StructuredRegion.If>,
        val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
    )

    private sealed interface ArmCollection {
        data class Success(val blocks: Set<BasicBlockId>) : ArmCollection
        data class Rejected(val reason: UnstructuredControlFlowReason) : ArmCollection
    }
}
