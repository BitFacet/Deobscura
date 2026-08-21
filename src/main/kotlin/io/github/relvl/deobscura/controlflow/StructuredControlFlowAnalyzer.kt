package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.normalize.LegacySubroutineProvenance

/** Recognizes conservative single-entry reducible `if`, terminal-arm `if`, and natural `while` regions. */
class StructuredControlFlowAnalyzer {
    private val loopRecognizer = StructuredLoopRecognizer()
    private val switchRecognizer = StructuredSwitchRecognizer()
    private val conditionalRecognizer = StructuredConditionalRecognizer()
    private val assertRecognizer = StructuredAssertRecognizer()
    private val exceptionRecognizer = StructuredExceptionRecognizer()

    fun analyze(
        graph: ControlFlowGraph, flow: SsaControlFlowGraph, expression: ExpressionAnalysis, legacySubroutineNormalized: Boolean = false, legacySubroutineProvenance: LegacySubroutineProvenance? = null
    ): StructuredControlFlowAnalysis {
        val facts = ControlFlowFacts.build(graph, flow, expression) ?: return StructuredControlFlowAnalysis(emptyList(), 0, 0)
        val outgoing = facts.outgoing
        val predecessors = facts.predecessors
        val instructionToBlock = facts.instructionToBlock
        val originalBranches = facts.originalBranches
        val switches = facts.switches
        val switchHeaders = switches.keys
        val folds = findBooleanConditionFolds(
            expression = expression,
            branches = originalBranches,
            outgoing = outgoing,
            instructionToBlock = instructionToBlock,
        )
        val foldedProducerHeaders = folds.mapTo(hashSetOf()) { it.producerHeader }
        val foldedConditions = folds.associate { it.consumerHeader to it.condition }
        val branches =
            originalBranches.asSequence().filter { (header, _) -> header !in foldedProducerHeaders }.associate { (header, branch) -> header to (foldedConditions[header]?.let { branch.copy(condition = it) } ?: branch) }
        val assertRecognition = assertRecognizer.recognize(
            facts = facts,
            branches = branches,
            expression = expression,
        )
        val loopRecognition = loopRecognizer.recognize(
            facts,
            branches.filterKeys { it !in assertRecognition.consumedHeaders },
        )
        val loopHeaders = loopRecognition.regions.mapTo(hashSetOf()) { it.header }
        val shortCircuitFolds = findShortCircuitConditionFolds(
            expression = expression,
            branches = branches,
            outgoing = outgoing,
            predecessors = predecessors,
            instructionToBlock = instructionToBlock,
            excludedHeaders = loopHeaders + assertRecognition.consumedHeaders,
        )
        val shortCircuitByRoot = shortCircuitFolds.associateBy { it.rootHeader }
        val shortCircuitFoldedHeaders = shortCircuitFolds.flatMapTo(hashSetOf()) { it.foldedHeaders }
        val loopContexts = loopRecognizer.contexts(loopRecognition.regions, facts, expression)
        val naturalLoopContexts = loopRecognizer.naturalContexts(facts, expression)
        val exceptionRecognition = exceptionRecognizer.recognize(
            graph = graph,
            facts = facts,
            legacySubroutineNormalized = legacySubroutineNormalized || legacySubroutineProvenance != null,
            legacySubroutineProvenance = legacySubroutineProvenance,
        )
        val switchRecognition = switchRecognizer.recognize(
            facts = facts,
            loopContexts = naturalLoopContexts,
        )
        val ifRecognition = conditionalRecognizer.recognize(
            facts = facts,
            branches = branches,
            excludedHeaders = loopHeaders + shortCircuitFoldedHeaders + assertRecognition.consumedHeaders,
            loopContexts = loopContexts.values.toList(),
            shortCircuitByRoot = shortCircuitByRoot,
            exceptionRegions = exceptionRecognition.regions,
        )
        val regions = (loopRecognition.regions + switchRecognition.regions + ifRecognition.regions + assertRecognition.regions + exceptionRecognition.regions).sortedWith(
            compareBy<StructuredRegion> { it.header.value }.thenBy {
                when (it) {
                    is StructuredRegion.While -> 0
                    is StructuredRegion.Assert -> 1
                    is StructuredRegion.TryCatch -> 2
                    is StructuredRegion.TryCatchFinally -> 3
                    is StructuredRegion.TryFinally -> 4
                    is StructuredRegion.Synchronized -> 5
                    is StructuredRegion.Switch -> 6
                    is StructuredRegion.If -> 7
                }
            },
        )
        val recognizedConditionalHeaders = linkedSetOf<BasicBlockId>().apply {
            addAll(loopRecognition.regions.map { it.header })
            addAll(ifRecognition.regions.map { it.header })
            addAll(assertRecognition.consumedHeaders)
            addAll(foldedProducerHeaders)
            addAll(shortCircuitFoldedHeaders)
        }
        val recognizedSwitchHeaders = switchRecognition.regions.mapTo(hashSetOf()) { it.header }

        val diagnostics = buildList {
            originalBranches.keys.sortedBy { it.value }.forEach { header ->
                if (header in recognizedConditionalHeaders) return@forEach
                val reason = loopRecognition.rejections[header] ?: ifRecognition.rejections[header] ?: UnstructuredControlFlowReason.UNSUPPORTED_SHAPE
                add(UnstructuredControlFlowDiagnostic(header, UnstructuredControlFlowKind.CONDITIONAL, reason))
            }
            switchHeaders.sortedBy { it.value }.forEach { header ->
                if (header in recognizedSwitchHeaders) return@forEach
                add(
                    UnstructuredControlFlowDiagnostic(
                        header, UnstructuredControlFlowKind.SWITCH, switchRecognition.rejections[header] ?: UnstructuredControlFlowReason.UNSUPPORTED_SHAPE
                    ),
                )
            }
            exceptionRecognition.rejections.entries
                .sortedWith(compareBy<Map.Entry<ExceptionRegionKey, UnstructuredControlFlowReason>> { it.key.protectedStartInstructionIndex }.thenBy { it.key.protectedEndInstructionIndexExclusive })
                .forEach { (key, reason) ->
                    val header = facts.instructionToBlock.getOrNull(key.protectedStartInstructionIndex) ?: return@forEach
                    add(
                        UnstructuredControlFlowDiagnostic(
                            header = header,
                            kind = UnstructuredControlFlowKind.EXCEPTION,
                            reason = reason,
                            protectedStartInstructionIndex = key.protectedStartInstructionIndex,
                            protectedEndInstructionIndexExclusive = key.protectedEndInstructionIndexExclusive,
                            detail = exceptionRecognition.legacyRejectionDetails[key],
                            exceptionResidualFamily = exceptionRecognition.residualFamilies[key],
                        ),
                    )
                }
        }
        return StructuredControlFlowAnalysis(
            regions = regions,
            conditionalBranchCount = originalBranches.size,
            switchCount = switchHeaders.size,
            exceptionRegionCount = exceptionRecognition.regionCount,
            booleanConditionFolds = folds,
            shortCircuitConditionFolds = shortCircuitFolds,
            emptyArmNormalizationCount = ifRecognition.emptyArmNormalizationCount,
            terminalIfRegionCount = ifRecognition.terminalIfRegionCount,
            continueIfRegionCount = ifRecognition.continueIfRegionCount,
            breakIfRegionCount = ifRecognition.breakIfRegionCount,
            loopBodyIfRegionCount = ifRecognition.loopBodyIfRegionCount,
            loopContinuationIfRegionCount = ifRecognition.loopContinuationIfRegionCount,
            unstructured = diagnostics,
        )
    }

    private fun findBooleanConditionFolds(
        expression: ExpressionAnalysis,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        instructionToBlock: Array<BasicBlockId?>,
    ): List<BooleanConditionFold> {
        if (expression.materialization.booleanValues.isEmpty()) return emptyList()
        val valuesByBlock = expression.values.values.asSequence().filter { it.instructionIndices.isNotEmpty() }.groupBy { instructionToBlock.getOrNull(it.instructionIndices.last()) }
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
            val phiNegatesSource = !conditionalTruth && fallthroughTruth // The consumer branch is true for P with ifne, false for P with ifeq.
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
        valuesByBlock: Map<BasicBlockId?, List<ExpressionValue>>,
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

    /**
     * Recognizes linear short-circuit chains such as `a || b || c` or their negated `&&` form.
     * Intermediate condition-only blocks stay in the canonical CFG, but are hidden from the
     * structured view and the root branch receives a compound source condition.
     */
    private fun findShortCircuitConditionFolds(
        expression: ExpressionAnalysis,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        instructionToBlock: Array<BasicBlockId?>,
        excludedHeaders: Set<BasicBlockId> = emptySet(),
    ): List<ShortCircuitConditionFold> {
        if (branches.size < 2) return emptyList()
        val valuesByBlock = expression.values.values.asSequence().filter { it.instructionIndices.isNotEmpty() }.groupBy { instructionToBlock.getOrNull(it.instructionIndices.last()) }
        val statementsByBlock = expression.statements.groupBy { instructionToBlock.getOrNull(it.instructionIndex) }
        val occupied = hashSetOf<BasicBlockId>()
        val result = mutableListOf<ShortCircuitConditionFold>()

        for (root in branches.keys.sortedBy { it.value }) {
            if (root in occupied || root in excludedHeaders) continue
            val rootTargets = branchTargets(root, outgoing) ?: continue
            var best: ShortCircuitConditionFold? = null
            for (commonTarget in listOf(rootTargets.first, rootTargets.second).distinct()) {
                val terms = mutableListOf<StructuredCondition>()
                val folded = linkedSetOf<BasicBlockId>()
                var current = root
                var previous: BasicBlockId? = null
                var finalOther: BasicBlockId? = null
                var valid = true

                while (true) {
                    val branch = branches[current]
                    if (branch == null) {
                        valid = false; break
                    }
                    val condition = branch.condition
                    if (condition == null) {
                        valid = false; break
                    }
                    val targets = branchTargets(current, outgoing)
                    if (targets == null) {
                        valid = false; break
                    }
                    val toCommon = when (commonTarget) {
                        targets.first -> StructuredCondition.Atomic(condition)
                        targets.second -> StructuredCondition.Atomic(condition.negated())
                        else -> null
                    }
                    if (toCommon == null) {
                        valid = false; break
                    }
                    terms += toCommon
                    val other = if (targets.first == commonTarget) targets.second else targets.first

                    val nextBranch = branches[other]
                    val nextTargets = nextBranch?.let { branchTargets(other, outgoing) }
                    val canContinue =
                        nextBranch != null && nextTargets != null && commonTarget in listOf(nextTargets.first, nextTargets.second) && other !in occupied && other !in excludedHeaders && predecessors[other].orEmpty()
                            .distinct() == listOf(current) && isTransparentConditionHeader(other, valuesByBlock, statementsByBlock, expression)
                    if (!canContinue) {
                        finalOther = other
                        break
                    }
                    if (previous != null) folded += current
                    previous = current
                    current = other
                    if (current == root || folded.size > 64) {
                        valid = false
                        break
                    }
                }

                // At least two branch headers must participate. The root itself is retained; every
                // later header is source-transparent and is recorded as folded.
                if (!valid || terms.size < 2 || finalOther == null) continue
                folded.clear()
                var cursor = root
                repeat(terms.size - 1) {
                    val targets = branchTargets(cursor, outgoing) ?: return@repeat
                    val other = if (targets.first == commonTarget) targets.second else targets.first
                    folded += other
                    cursor = other
                }
                if (folded.any { it in occupied }) continue
                val candidate = ShortCircuitConditionFold(
                    rootHeader = root,
                    foldedHeaders = folded,
                    condition = StructuredCondition.Or(terms),
                    conditionalTarget = commonTarget,
                    fallthroughTarget = finalOther,
                )
                if (best == null || candidate.foldedHeaders.size > best.foldedHeaders.size) best = candidate
            }
            best?.let { fold ->
                result += fold
                occupied += fold.foldedHeaders
            }
        }
        return result
    }

    private fun branchTargets(
        header: BasicBlockId,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): Pair<BasicBlockId, BasicBlockId>? {
        val edges = outgoing[header].orEmpty()
        val conditional = edges.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to ?: return null
        val fallthrough = edges.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH }?.to ?: return null
        if (conditional == fallthrough) return null
        return conditional to fallthrough
    }

    private fun isTransparentConditionHeader(
        block: BasicBlockId, valuesByBlock: Map<BasicBlockId?, List<ExpressionValue>>, statementsByBlock: Map<BasicBlockId?, List<ExpressionStatement>>, expression: ExpressionAnalysis
    ): Boolean {
        if (valuesByBlock[block].orEmpty().any { it.id !in expression.materialization.inlineValues }) return false
        val statements = statementsByBlock[block].orEmpty()
        return statements.size == 1 && statements.single() is ExpressionStatement.Branch && (statements.single() as ExpressionStatement.Branch).condition != null
    }

    private fun StructuredCondition.negated(): StructuredCondition = when (this) {
        is StructuredCondition.Atomic -> copy(condition = condition.negated())
        is StructuredCondition.And -> StructuredCondition.Or(terms.map { it.negated() })
        is StructuredCondition.Or -> StructuredCondition.And(terms.map { it.negated() })
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

}
