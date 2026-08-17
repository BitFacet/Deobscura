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

/** Recognizes conservative single-entry reducible `if`, terminal-arm `if`, and natural `while` regions. */
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
        val incoming = normalEdges.groupBy { it.to }
        val predecessors = incoming.mapValues { (_, edges) -> edges.map { it.from }.distinct() }
        val instructionToBlock = instructionToBlock(graph)
        val originalBranches = branchesByBlock(graph, expression, blocks, instructionToBlock)
        val switches = switchesByBlock(expression, blocks, instructionToBlock)
        val switchHeaders = switches.keys
        val explicitTerminalBlocks = explicitTerminalBlocks(expression, blocks, instructionToBlock, outgoing)
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
        val dominators = dominators(blocks, requireNotNull(flow.entryBlock), predecessors)
        val loopRecognition = findNaturalLoops(normalEdges, outgoing, predecessors, branches, dominators)
        val loopHeaders = loopRecognition.regions.mapTo(hashSetOf()) { it.header }
        val shortCircuitFolds = findShortCircuitConditionFolds(
            expression = expression,
            branches = branches,
            outgoing = outgoing,
            predecessors = predecessors,
            instructionToBlock = instructionToBlock,
            excludedHeaders = loopHeaders,
        )
        val shortCircuitByRoot = shortCircuitFolds.associateBy { it.rootHeader }
        val shortCircuitFoldedHeaders = shortCircuitFolds.flatMapTo(hashSetOf()) { it.foldedHeaders }
        val loopContexts = loopRecognition.regions.associate { loop ->
            loop.header to LoopFlowContext(
                loop = loop,
                continueTargets = transparentLoopContinueTargets(
                    loop = loop,
                    outgoing = outgoing,
                    expression = expression,
                    instructionToBlock = instructionToBlock,
                ),
            )
        }
        val postDominators = postDominators(blocks, outgoing)
        val switchRecognition = findSwitchRegions(
            switches = switches,
            blocks = blocks,
            outgoing = outgoing,
            predecessors = predecessors,
            incoming = incoming,
            postDominators = postDominators,
            explicitTerminalBlocks = explicitTerminalBlocks,
            loopContexts = loopContexts.values.toList(),
        )
        val ifRecognition = findIfRegions(
            blocks = blocks,
            outgoing = outgoing,
            predecessors = predecessors,
            branches = branches,
            postDominators = postDominators,
            excludedHeaders = loopHeaders + shortCircuitFoldedHeaders,
            explicitTerminalBlocks = explicitTerminalBlocks,
            loopContexts = loopContexts.values.toList(),
            shortCircuitByRoot = shortCircuitByRoot,
        )

        val regions = (loopRecognition.regions + switchRecognition.regions + ifRecognition.regions).sortedWith(
            compareBy<StructuredRegion> { it.header.value }
                .thenBy {
                    when (it) {
                        is StructuredRegion.While -> 0
                        is StructuredRegion.Switch -> 1
                        is StructuredRegion.If -> 2
                    }
                },
        )
        val recognizedHeaders = regions.mapTo(hashSetOf()) { it.header }
        recognizedHeaders += foldedProducerHeaders
        recognizedHeaders += shortCircuitFoldedHeaders

        val diagnostics = buildList {
            originalBranches.keys.sortedBy { it.value }.forEach { header ->
                if (header in recognizedHeaders) return@forEach
                val reason = loopRecognition.rejections[header]
                    ?: ifRecognition.rejections[header]
                    ?: UnstructuredControlFlowReason.UNSUPPORTED_SHAPE
                add(UnstructuredControlFlowDiagnostic(header, UnstructuredControlFlowKind.CONDITIONAL, reason))
            }
            switchHeaders.sortedBy { it.value }.forEach { header ->
                if (header in recognizedHeaders) return@forEach
                add(
                    UnstructuredControlFlowDiagnostic(
                        header,
                        UnstructuredControlFlowKind.SWITCH,
                        switchRecognition.rejections[header] ?: UnstructuredControlFlowReason.UNSUPPORTED_SHAPE,
                    ),
                )
            }
        }
        return StructuredControlFlowAnalysis(
            regions = regions,
            conditionalBranchCount = originalBranches.size,
            switchCount = switchHeaders.size,
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
    ): Map<BasicBlockId, ExpressionStatement.Switch> = expression.statements.asSequence()
        .filterIsInstance<ExpressionStatement.Switch>()
        .mapNotNull { statement ->
            val block = instructionToBlock.getOrNull(statement.instructionIndex) ?: return@mapNotNull null
            block.takeIf { it in blocks }?.let { it to statement }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (block, statements) ->
            if (statements.size != 1) {
                throw StructuredControlFlowInconsistencyException(
                    "Basic block B${block.value} contains ${statements.size} switch statements.",
                )
            }
            statements.single()
        }

    /** Blocks whose normal flow ends in an explicit source-level return or throw. */
    private fun explicitTerminalBlocks(
        expression: ExpressionAnalysis,
        blocks: Set<BasicBlockId>,
        instructionToBlock: Array<BasicBlockId?>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): Set<BasicBlockId> = expression.statements.asSequence()
        .filter { it is ExpressionStatement.Return || it is ExpressionStatement.Throw }
        .mapNotNull { instructionToBlock.getOrNull(it.instructionIndex) }
        .filter { it in blocks && outgoing[it].orEmpty().isEmpty() }
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
        val valuesByBlock = expression.values.values.asSequence()
            .filter { it.instructionIndices.isNotEmpty() }
            .groupBy { instructionToBlock.getOrNull(it.instructionIndices.last()) }
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
                    if (branch == null) { valid = false; break }
                    val condition = branch.condition
                    if (condition == null) { valid = false; break }
                    val targets = branchTargets(current, outgoing)
                    if (targets == null) { valid = false; break }
                    val toCommon = when (commonTarget) {
                        targets.first -> StructuredCondition.Atomic(condition)
                        targets.second -> StructuredCondition.Atomic(condition.negated())
                        else -> null
                    }
                    if (toCommon == null) { valid = false; break }
                    terms += toCommon
                    val other = if (targets.first == commonTarget) targets.second else targets.first

                    val nextBranch = branches[other]
                    val nextTargets = nextBranch?.let { branchTargets(other, outgoing) }
                    val canContinue = nextBranch != null &&
                        nextTargets != null &&
                        commonTarget in listOf(nextTargets.first, nextTargets.second) &&
                        other !in occupied &&
                        other !in excludedHeaders &&
                        predecessors[other].orEmpty().distinct() == listOf(current) &&
                        isTransparentConditionHeader(other, valuesByBlock, statementsByBlock, expression)
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
        block: BasicBlockId,
        valuesByBlock: Map<BasicBlockId?, List<io.github.relvl.deobscura.expression.ExpressionValue>>,
        statementsByBlock: Map<BasicBlockId?, List<ExpressionStatement>>,
        expression: ExpressionAnalysis,
    ): Boolean {
        if (valuesByBlock[block].orEmpty().any { it.id !in expression.materialization.inlineValues }) return false
        val statements = statementsByBlock[block].orEmpty()
        return statements.size == 1 && statements.single() is ExpressionStatement.Branch &&
            (statements.single() as ExpressionStatement.Branch).condition != null
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
            // An edge from the loop body to the same exit selected by the header is a source-level
            // `break`, not a reason to reject the natural loop. Exits to any other block remain
            // unsupported because they may represent labeled transfers or a more complex region.
            if (externalExits.any { it.to != exitEdge.to }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_HAS_ADDITIONAL_EXIT
                return@forEach
            }
            val breakEdges = externalExits.asSequence()
                .filter { it.from != header }
                .map { it.from to it.to }
                .toCollection(linkedSetOf())

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
                condition = StructuredCondition.Atomic(condition),
                negateCondition = negate,
                bodyEntry = bodyEdge.to,
                bodyBlocks = body.sortedBy { it.value }.toCollection(linkedSetOf()),
                exit = exitEdge.to,
                latches = latchesEdges.mapTo(linkedSetOf()) { it.from },
                breakEdges = breakEdges,
            )
        }
        return LoopRecognition(regions, rejections)
    }


    private fun findSwitchRegions(
        switches: Map<BasicBlockId, ExpressionStatement.Switch>,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        incoming: Map<BasicBlockId, List<ControlFlowEdge>>,
        postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        loopContexts: List<LoopFlowContext>,
    ): SwitchRecognition {
        val regions = mutableListOf<StructuredRegion.Switch>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()

        switches.forEach { (header, statement) ->
            val switchEdges = outgoing[header].orEmpty().filter { it.kind == ControlFlowEdgeKind.SWITCH }
            if (switchEdges.isEmpty() || switchEdges.none { it.switchValue == null }) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_MISSING_EDGES
                return@forEach
            }

            val entries = switchEdges.mapTo(linkedSetOf()) { it.to }
            val continuation = switchContinuation(
                header = header,
                entries = entries,
                incoming = incoming,
                outgoing = outgoing,
                postDominators = postDominators,
                explicitTerminalBlocks = explicitTerminalBlocks,
            )
            val containingLoop = loopContexts
                .filter { header in it.loop.bodyBlocks }
                .minByOrNull { it.loop.bodyBlocks.size }

            val collected = linkedMapOf<BasicBlockId, Set<BasicBlockId>>()
            for (entry in entries) {
                if (entry == continuation) {
                    collected[entry] = emptySet()
                    continue
                }
                when (val result = collectSwitchCase(
                    start = entry,
                    header = header,
                    continuation = continuation,
                    caseEntries = entries,
                    blocks = blocks,
                    outgoing = outgoing,
                    explicitTerminalBlocks = explicitTerminalBlocks,
                    continueTargets = containingLoop?.continueTargets.orEmpty(),
                )) {
                    is SwitchCaseCollection.Success -> collected[entry] = result.blocks
                    is SwitchCaseCollection.Rejected -> {
                        rejections[header] = result.reason
                        break
                    }
                }
            }
            if (header in rejections) return@forEach

            detachSharedTerminalBlocks(
                collected = collected,
                header = header,
                continuation = continuation,
                caseEntries = entries,
                predecessors = predecessors,
                explicitTerminalBlocks = explicitTerminalBlocks,
            )

            val nonEmptyCaseBlocks = collected.values.filter { it.isNotEmpty() }
            for (i in nonEmptyCaseBlocks.indices) {
                for (j in i + 1 until nonEmptyCaseBlocks.size) {
                    if (nonEmptyCaseBlocks[i].intersect(nonEmptyCaseBlocks[j]).isNotEmpty()) {
                        rejections[header] = UnstructuredControlFlowReason.SWITCH_OVERLAPPING_CASES
                        return@forEach
                    }
                }
            }

            val allCaseBlocks = collected.values.flatten().toSet()
            val allowedEntryPredecessors = allCaseBlocks + header
            val hasExternalEntry = collected.any { (entry, caseBlocks) ->
                caseBlocks.any { block ->
                    predecessors[block].orEmpty().any { predecessor ->
                        predecessor !in allowedEntryPredecessors && !(block == entry && predecessor in entries)
                    }
                }
            }
            if (hasExternalEntry) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_EXTERNAL_ENTRY
                return@forEach
            }

            val cases = switchEdges.groupBy { it.to }.map { (entry, edges) ->
                val labels = edges.mapNotNull { it.switchValue }.distinct().sorted()
                val isDefault = edges.any { it.switchValue == null }
                val caseBlocks = collected.getValue(entry)
                val exit = classifySwitchCaseExit(
                    caseBlocks = caseBlocks,
                    entry = entry,
                    continuation = continuation,
                    caseEntries = entries,
                    outgoing = outgoing,
                    explicitTerminalBlocks = explicitTerminalBlocks,
                    continueTargets = containingLoop?.continueTargets.orEmpty(),
                ) ?: if (caseBlocks.isEmpty() && entry == continuation) {
                    StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.NORMAL, continuation)
                } else {
                    rejections[header] = UnstructuredControlFlowReason.SWITCH_UNSUPPORTED_EXIT
                    return@forEach
                }
                StructuredSwitchCase(
                    labels = labels,
                    isDefault = isDefault,
                    entry = entry,
                    blocks = caseBlocks,
                    exit = exit,
                )
            }.sortedWith(compareBy<StructuredSwitchCase> { it.entry.value }.thenBy { it.labels.firstOrNull() ?: Int.MAX_VALUE })

            if (header in rejections) return@forEach
            if (continuation == null && !switchCasesNeedNoContinuation(cases)) {
                rejections[header] = UnstructuredControlFlowReason.SWITCH_NO_CONTINUATION
                return@forEach
            }

            regions += StructuredRegion.Switch(
                header = header,
                selector = statement.selector,
                cases = cases,
                continuation = continuation,
            )
        }

        return SwitchRecognition(regions, rejections)
    }

    /**
     * A switch does not need a common continuation when every case transfers control out of the
     * switch: directly through return/throw/continue, or through a chain of source fallthroughs
     * that eventually reaches such a case. This is common when the final/default case terminates.
     */
    private fun switchCasesNeedNoContinuation(cases: List<StructuredSwitchCase>): Boolean {
        val byEntry = cases.associateBy { it.entry }
        val memo = mutableMapOf<BasicBlockId, Boolean>()

        fun exitsSwitch(entry: BasicBlockId, visiting: MutableSet<BasicBlockId>): Boolean {
            memo[entry]?.let { return it }
            if (!visiting.add(entry)) return false
            val exit = byEntry[entry]?.exit
            val result = when (exit?.kind) {
                StructuredSwitchCaseExitKind.RETURN_OR_THROW,
                StructuredSwitchCaseExitKind.CONTINUE,
                -> true

                StructuredSwitchCaseExitKind.FALLTHROUGH ->
                    exit.target?.let { target -> target in byEntry && exitsSwitch(target, visiting) } == true

                StructuredSwitchCaseExitKind.BREAK,
                StructuredSwitchCaseExitKind.NORMAL,
                null,
                -> false
            }
            visiting.remove(entry)
            memo[entry] = result
            return result
        }

        return cases.all { exitsSwitch(it.entry, linkedSetOf()) }
    }

    private fun switchContinuation(
        header: BasicBlockId,
        entries: Set<BasicBlockId>,
        incoming: Map<BasicBlockId, List<ControlFlowEdge>>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ): BasicBlockId? {
        immediatePostDominator(header, postDominators)?.let { return it }

        // A switch target that is also reachable without passing through the switch header cannot
        // be an ordinary source-level case body. It is a shared continuation reached both by the
        // switch default/empty case and by surrounding control flow. This shape is common after
        // chains of early-return tests followed by a switch with an empty default arm.
        val reachableFromHeader = reachableFrom(header, outgoing)
        val externallySharedEntries = entries.filter { candidate ->
            incoming[candidate].orEmpty().any { edge ->
                edge.from != header && edge.from !in reachableFromHeader
            }
        }
        if (externallySharedEntries.size == 1) return externallySharedEntries.single()

        // Local return/throw paths inside a case prevent an otherwise ordinary post-switch join
        // from post-dominating that case entry. Prefer the earliest join reached by multiple cases
        // before considering joins with surrounding control flow: a later outer join is not the
        // switch continuation when the cases already reconverged locally.
        val reachableSupport = linkedMapOf<BasicBlockId, Int>()
        entries.forEach { entry ->
            reachableUntilCaseOrTerminal(
                start = entry,
                caseEntries = entries,
                outgoing = outgoing,
                explicitTerminalBlocks = explicitTerminalBlocks,
            ).forEach { candidate ->
                if (candidate != header && candidate !in entries) {
                    reachableSupport[candidate] = reachableSupport.getOrDefault(candidate, 0) + 1
                }
            }
        }
        val maxReachableSupport = reachableSupport.values.maxOrNull() ?: 0
        if (maxReachableSupport >= 2) {
            val candidates = reachableSupport.filterValues { it == maxReachableSupport }.keys
            val earliest = candidates.filter { candidate ->
                candidates.all { other -> other == candidate || other in reachableFrom(candidate, outgoing) }
            }
            if (earliest.size == 1) return earliest.single()
        }

        // The continuation does not have to be a switch target. A nested switch frequently has
        // one continuing case and a terminal default, while the continuing path joins surrounding
        // control flow immediately after the switch. That join is visible as a reachable block
        // with a predecessor that cannot be reached from this switch header. This is weaker evidence
        // than a join shared by multiple cases, so it is considered only afterwards.
        val externallySharedJoins = reachableFromHeader.filter { candidate ->
            candidate != header &&
                candidate !in entries &&
                candidate !in explicitTerminalBlocks &&
                header !in reachableFrom(candidate, outgoing) &&
                incoming[candidate].orEmpty().any { edge -> edge.from !in reachableFromHeader }
        }
        if (externallySharedJoins.size == 1) return externallySharedJoins.single()

        // A terminal case prevents the post-switch continuation from post-dominating the switch
        // header. Recover the join from the largest subset of case entries that does continue.
        // The continuation may itself be a switch target for an empty `case: break;`; accept such
        // entries only when another case reaches them through an explicit jump, so ordinary source
        // fallthrough into a later case is not mistaken for the post-switch continuation.
        val support = linkedMapOf<BasicBlockId, Int>()
        entries.forEach { entry ->
            postDominators[entry].orEmpty().forEach { candidate ->
                val isDirectSwitchEntry = candidate in entries
                val hasBreakLikeIncoming = incoming[candidate].orEmpty().any { edge ->
                    edge.from != header && edge.kind == ControlFlowEdgeKind.JUMP
                }
                if (candidate != header && (!isDirectSwitchEntry || hasBreakLikeIncoming)) {
                    support[candidate] = support.getOrDefault(candidate, 0) + 1
                }
            }
        }
        val maxSupport = support.values.maxOrNull() ?: return null
        if (maxSupport < 2) return null
        val candidates = support.filterValues { it == maxSupport }.keys
        return candidates.firstOrNull { candidate ->
            candidates.none { other ->
                other != candidate && candidate in postDominators[other].orEmpty()
            }
        }
    }

    private fun reachableUntilCaseOrTerminal(
        start: BasicBlockId,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block != start && block in caseEntries) continue
            if (!result.add(block)) continue
            if (block in explicitTerminalBlocks) continue
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return result
    }

    private fun reachableFrom(
        start: BasicBlockId,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): Set<BasicBlockId> {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (!result.add(block)) continue
            outgoing[block].orEmpty().forEach { edge -> queue.addLast(edge.to) }
        }
        return result
    }

    private fun collectSwitchCase(
        start: BasicBlockId,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        continueTargets: Set<BasicBlockId>,
    ): SwitchCaseCollection {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == continuation || (block != start && block in caseEntries) || block in continueTargets) continue
            if (block == header) return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in blocks) return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue

            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) {
                if (block !in explicitTerminalBlocks) {
                    return SwitchCaseCollection.Rejected(UnstructuredControlFlowReason.SWITCH_UNSUPPORTED_EXIT)
                }
                continue
            }
            successors.forEach { successor ->
                if (
                    successor != continuation &&
                    successor !in continueTargets &&
                    (successor == start || successor !in caseEntries)
                ) {
                    queue.addLast(successor)
                }
            }
        }
        return SwitchCaseCollection.Success(result)
    }

    /**
     * Keeps terminal blocks inside a case when they are part of that case's own control flow, but
     * factors out terminal tails that are genuinely shared between cases or with surrounding flow.
     * The latter are source-level transfers rather than owned case-body blocks.
     */
    private fun detachSharedTerminalBlocks(
        collected: MutableMap<BasicBlockId, Set<BasicBlockId>>,
        header: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ) {
        val allCaseBlocks = collected.values.flatten().toSet()
        val owners = linkedMapOf<BasicBlockId, MutableSet<BasicBlockId>>()
        collected.forEach { (entry, caseBlocks) ->
            caseBlocks.forEach { block ->
                if (block in explicitTerminalBlocks) {
                    owners.getOrPut(block) { linkedSetOf() } += entry
                }
            }
        }

        val sharedTerminalBlocks = owners.filter { (block, blockOwners) ->
            block != continuation &&
                block !in caseEntries &&
                (
                    blockOwners.size > 1 ||
                        predecessors[block].orEmpty().any { predecessor ->
                            predecessor != header && predecessor !in allCaseBlocks
                        }
                    )
        }.keys
        if (sharedTerminalBlocks.isEmpty()) return

        collected.entries.forEach { entry ->
            if (entry.value.any { it in sharedTerminalBlocks }) {
                entry.setValue(entry.value.filterTo(linkedSetOf()) { it !in sharedTerminalBlocks })
            }
        }
    }

    private fun classifySwitchCaseExit(
        caseBlocks: Set<BasicBlockId>,
        entry: BasicBlockId,
        continuation: BasicBlockId?,
        caseEntries: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        continueTargets: Set<BasicBlockId>,
    ): StructuredSwitchCaseExit? {
        if (caseBlocks.isEmpty()) return null
        val exits = caseBlocks.flatMap { block ->
            outgoing[block].orEmpty().distinctTargets().filter { edge -> edge.to !in caseBlocks }
        }
        val targets = exits.mapTo(linkedSetOf()) { it.to }

        if (targets.isEmpty()) {
            return if (caseBlocks.any { it in explicitTerminalBlocks }) {
                StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.RETURN_OR_THROW)
            } else null
        }
        if (targets.size != 1) return null
        val target = targets.single()

        if (target in caseEntries && target != entry) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.FALLTHROUGH, target)
        }
        if (target in continueTargets) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.CONTINUE, target)
        }
        if (target == continuation) {
            val kind = if (exits.all { it.kind == ControlFlowEdgeKind.JUMP }) {
                StructuredSwitchCaseExitKind.BREAK
            } else {
                StructuredSwitchCaseExitKind.NORMAL
            }
            return StructuredSwitchCaseExit(kind, target)
        }
        if (target in explicitTerminalBlocks) {
            return StructuredSwitchCaseExit(StructuredSwitchCaseExitKind.RETURN_OR_THROW, target)
        }
        return null
    }

    private fun findIfRegions(
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        postDominators: Map<BasicBlockId, Set<BasicBlockId>>,
        excludedHeaders: Set<BasicBlockId>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        loopContexts: List<LoopFlowContext>,
        shortCircuitByRoot: Map<BasicBlockId, ShortCircuitConditionFold>,
    ): IfRecognition {
        val regions = mutableListOf<StructuredRegion.If>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()
        var emptyArmNormalizationCount = 0
        var terminalIfRegionCount = 0
        var continueIfRegionCount = 0
        var breakIfRegionCount = 0
        var loopBodyIfRegionCount = 0
        var loopContinuationIfRegionCount = 0
        branches.forEach { (header, branch) ->
            if (header in excludedHeaders) return@forEach
            val branchCondition = branch.condition ?: return@forEach
            val shortCircuitFold = shortCircuitByRoot[header]
            val condition = shortCircuitFold?.condition ?: StructuredCondition.Atomic(branchCondition)
            val edges = outgoing[header].orEmpty()
            val conditional = edges.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }
            val fallthrough = edges.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH }
            if (conditional == null || fallthrough == null) {
                rejections[header] = UnstructuredControlFlowReason.MISSING_BRANCH_EDGES
                return@forEach
            }
            val conditionalTarget = shortCircuitFold?.conditionalTarget ?: conditional.to
            val fallthroughTarget = shortCircuitFold?.fallthroughTarget ?: fallthrough.to
            val ignoredArmPredecessors = shortCircuitFold?.foldedHeaders.orEmpty()
            if (conditionalTarget == fallthroughTarget) {
                rejections[header] = UnstructuredControlFlowReason.IDENTICAL_SUCCESSORS
                return@forEach
            }

            val containingLoop = loopContexts.asSequence()
                .filter { header in it.loop.bodyBlocks }
                .minByOrNull { it.loop.bodyBlocks.size }
            if (containingLoop != null) {
                val loopTransferRegion = recognizeLoopTransferIf(
                    header = header,
                    condition = condition,
                    conditionalTarget = conditionalTarget,
                    fallthroughTarget = fallthroughTarget,
                    context = containingLoop,
                    outgoing = outgoing,
                    predecessors = predecessors,
                    ignoredPredecessors = ignoredArmPredecessors,
                )
                if (loopTransferRegion != null) {
                    regions += loopTransferRegion.region
                    when (loopTransferRegion.exit.kind) {
                        StructuredArmExitKind.CONTINUE -> continueIfRegionCount++
                        StructuredArmExitKind.BREAK -> breakIfRegionCount++
                        StructuredArmExitKind.RETURN_OR_THROW -> error("Unexpected terminal exit in loop-transfer recognition")
                    }
                    if (loopTransferRegion.usedContinuationSpine) loopContinuationIfRegionCount++
                    return@forEach
                }

                val regionalIf = recognizeLoopBodyRegionalIf(
                    header = header,
                    condition = condition,
                    conditionalTarget = conditionalTarget,
                    fallthroughTarget = fallthroughTarget,
                    context = containingLoop,
                    outgoing = outgoing,
                    predecessors = predecessors,
                    ignoredPredecessors = ignoredArmPredecessors,
                )
                if (regionalIf != null) {
                    regions += regionalIf
                    loopBodyIfRegionCount++
                    return@forEach
                }
            }

            val join = immediatePostDominator(header, postDominators)
            if (join == null) {
                val terminalRegion = recognizeTerminalIf(
                    header = header,
                    condition = condition,
                    conditionalTarget = conditionalTarget,
                    fallthroughTarget = fallthroughTarget,
                    blocks = blocks,
                    outgoing = outgoing,
                    predecessors = predecessors,
                    explicitTerminalBlocks = explicitTerminalBlocks,
                    ignoredPredecessors = ignoredArmPredecessors,
                )
                if (terminalRegion != null) {
                    regions += terminalRegion
                    terminalIfRegionCount++
                } else {
                    rejections[header] = UnstructuredControlFlowReason.NO_COMMON_POST_DOMINATOR
                }
                return@forEach
            }
            if (join == header) {
                rejections[header] = UnstructuredControlFlowReason.INVALID_JOIN
                return@forEach
            }

            val thenAttempt = collectArm(conditionalTarget, join, header, blocks, outgoing)
            if (thenAttempt is ArmCollection.Rejected) {
                rejections[header] = thenAttempt.reason
                return@forEach
            }
            val elseAttempt = collectArm(fallthroughTarget, join, header, blocks, outgoing)
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
            if (!singleEntryArm(thenBlocks, header, predecessors, ignoredArmPredecessors) ||
                !singleEntryArm(elseBlocks, header, predecessors, ignoredArmPredecessors)
            ) {
                rejections[header] = UnstructuredControlFlowReason.EXTERNAL_ARM_ENTRY
                return@forEach
            }

            if (thenBlocks.isEmpty() && elseBlocks.isNotEmpty()) {
                // Prefer a non-empty `then` arm in the source view. This removes shapes such as
                // `if (!condition) {} else { body }` without touching the canonical CFG.
                regions += StructuredRegion.If(
                    header = header,
                    condition = condition.negated(),
                    thenEntry = fallthroughTarget.takeUnless { it == join },
                    thenBlocks = elseBlocks,
                    elseEntry = null,
                    elseBlocks = emptySet(),
                    continuation = join,
                )
                emptyArmNormalizationCount++
            } else {
                regions += StructuredRegion.If(
                    header = header,
                    condition = condition,
                    thenEntry = conditionalTarget.takeUnless { it == join },
                    thenBlocks = thenBlocks,
                    elseEntry = fallthroughTarget.takeUnless { it == join },
                    elseBlocks = elseBlocks,
                    continuation = join,
                )
            }
        }
        return IfRecognition(
            regions,
            rejections,
            emptyArmNormalizationCount,
            terminalIfRegionCount,
            continueIfRegionCount,
            breakIfRegionCount,
            loopBodyIfRegionCount,
            loopContinuationIfRegionCount,
        )
    }

    /**
     * Finds source-transparent loop-tail blocks that are semantically equivalent to reaching the
     * loop header. A compiler may target such a latch instead of the header directly for
     * `continue`; accepting only transparent tails keeps the source transfer semantics sound.
     */
    private fun transparentLoopContinueTargets(
        loop: StructuredRegion.While,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        expression: ExpressionAnalysis,
        instructionToBlock: Array<BasicBlockId?>,
    ): Set<BasicBlockId> {
        val valuesByBlock = expression.values.values.asSequence()
            .filter { it.instructionIndices.isNotEmpty() }
            .groupBy { instructionToBlock.getOrNull(it.instructionIndices.last()) }
        val statementsByBlock = expression.statements.groupBy { instructionToBlock.getOrNull(it.instructionIndex) }
        val result = linkedSetOf(loop.header)
        var changed: Boolean
        do {
            changed = false
            for (block in loop.bodyBlocks) {
                if (block in result || !isTransparentTransferBlock(block, valuesByBlock, statementsByBlock, expression)) continue
                val targets = outgoing[block].orEmpty().distinctTargets().map { it.to }
                if (targets.size == 1 && targets.single() in result) {
                    result += block
                    changed = true
                }
            }
        } while (changed)
        return result
    }

    private fun isTransparentTransferBlock(
        block: BasicBlockId,
        valuesByBlock: Map<BasicBlockId?, List<io.github.relvl.deobscura.expression.ExpressionValue>>,
        statementsByBlock: Map<BasicBlockId?, List<ExpressionStatement>>,
        expression: ExpressionAnalysis,
    ): Boolean {
        if (valuesByBlock[block].orEmpty().any { it.id !in expression.materialization.inlineValues }) return false
        // An unconditional JVM branch is only the physical transfer to the loop header/latch.
        // It has no source-level statement semantics and must not make an otherwise empty tail
        // visible to structured control-flow reconstruction.
        return statementsByBlock[block].orEmpty().all {
            it is ExpressionStatement.Branch && it.condition == null
        }
    }

    /**
     * Reconstructs an if nested in a natural-loop body when one successor is the next sequential
     * region and the other arm may also contain a proven loop transfer. This covers compiler CFGs
     * such as `if (...) { if (...) continue; body; } if (...) ...`, where the loop header is the
     * global post-dominator but is too coarse to be the source-level continuation of the first if.
     */
    private fun recognizeLoopBodyRegionalIf(
        header: BasicBlockId,
        condition: StructuredCondition,
        conditionalTarget: BasicBlockId,
        fallthroughTarget: BasicBlockId,
        context: LoopFlowContext,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        ignoredPredecessors: Set<BasicBlockId>,
    ): StructuredRegion.If? {
        val loop = context.loop
        val transferTargets = context.continueTargets + loop.exit
        val candidates = buildList {
            if (reachableWithinLoop(conditionalTarget, fallthroughTarget, loop, outgoing, transferTargets)) {
                add(Triple(conditionalTarget, fallthroughTarget, condition))
            }
            if (reachableWithinLoop(fallthroughTarget, conditionalTarget, loop, outgoing, transferTargets)) {
                add(Triple(fallthroughTarget, conditionalTarget, condition.negated()))
            }
        }
        for ((armStart, continuation, sourceCondition) in candidates) {
            val arm = collectLoopBodyRegionalArm(
                start = armStart,
                continuation = continuation,
                header = header,
                loop = loop,
                outgoing = outgoing,
                transferTargets = transferTargets,
            )
            if (arm !is ArmCollection.Success || arm.blocks.isEmpty()) continue
            if (!singleEntryArm(arm.blocks, header, predecessors, ignoredPredecessors)) continue
            return StructuredRegion.If(
                header = header,
                condition = sourceCondition,
                thenEntry = armStart,
                thenBlocks = arm.blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = continuation,
                loopBodyRegional = true,
            )
        }
        return null
    }

    private fun reachableWithinLoop(
        start: BasicBlockId,
        target: BasicBlockId,
        loop: StructuredRegion.While,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        transferTargets: Set<BasicBlockId>,
    ): Boolean {
        if (start == target) return true
        val seen = hashSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (!seen.add(block)) continue
            if (block != start && block in transferTargets) continue
            if (block !in loop.bodyBlocks) continue
            for (next in outgoing[block].orEmpty().distinctTargets().map { it.to }) {
                if (next == target) return true
                if (next in loop.bodyBlocks && next !in transferTargets) queue.addLast(next)
            }
        }
        return false
    }

    private fun collectLoopBodyRegionalArm(
        start: BasicBlockId,
        continuation: BasicBlockId,
        header: BasicBlockId,
        loop: StructuredRegion.While,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        transferTargets: Set<BasicBlockId>,
    ): ArmCollection {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        var continuationSeen = false
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == continuation) {
                continuationSeen = true
                continue
            }
            if (block in transferTargets) continue
            if (block == header) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in loop.bodyBlocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue
            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
            successors.forEach(queue::addLast)
        }
        if (!continuationSeen) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_HAS_OTHER_EXIT)
        return ArmCollection.Success(result)
    }

    /**
     * Recognizes an if arm that exits the innermost containing loop with `continue` or `break`.
     * The other successor becomes the normal continuation of the if. This is deliberately limited
     * to the loop header and the loop's canonical exit so labeled/non-local transfers remain
     * block-based.
     */
    private fun recognizeLoopTransferIf(
        header: BasicBlockId,
        condition: StructuredCondition,
        conditionalTarget: BasicBlockId,
        fallthroughTarget: BasicBlockId,
        context: LoopFlowContext,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        ignoredPredecessors: Set<BasicBlockId> = emptySet(),
    ): LoopTransferRecognition? {
        val loop = context.loop
        // Prefer an explicit edge to the canonical loop exit over describing the opposite arm as
        // `continue`; `if (x) break` is the direct source shape and leaves the remaining body as
        // the normal continuation.
        val candidates = listOf(
            StructuredArmExit(StructuredArmExitKind.BREAK, loop.exit),
            StructuredArmExit(StructuredArmExitKind.CONTINUE, loop.header),
        )
        for (exit in candidates) {
            val transferTargets = when (exit.kind) {
                StructuredArmExitKind.BREAK -> setOf(loop.exit)
                StructuredArmExitKind.CONTINUE -> context.continueTargets.ifEmpty { setOf(loop.header) }
                StructuredArmExitKind.RETURN_OR_THROW -> error("Unexpected terminal loop transfer")
            }
            val conditionalArm = collectTransferArm(
                start = conditionalTarget,
                continuation = fallthroughTarget,
                transferTargets = transferTargets,
                header = header,
                allowedBlocks = loop.bodyBlocks,
                outgoing = outgoing,
            )
            val conditionalTransfers = conditionalArm is ArmCollection.Success &&
                singleEntryArm(conditionalArm.blocks, header, predecessors, ignoredPredecessors)

            val fallthroughArm = collectTransferArm(
                start = fallthroughTarget,
                continuation = conditionalTarget,
                transferTargets = transferTargets,
                header = header,
                allowedBlocks = loop.bodyBlocks,
                outgoing = outgoing,
            )
            val fallthroughTransfers = fallthroughArm is ArmCollection.Success &&
                singleEntryArm(fallthroughArm.blocks, header, predecessors, ignoredPredecessors)

            var effectiveConditionalTransfers = conditionalTransfers
            var effectiveFallthroughTransfers = fallthroughTransfers
            var usedContinuationSpine = false
            if (conditionalTransfers && fallthroughTransfers) {
                if (exit.kind != StructuredArmExitKind.CONTINUE) continue
                when (
                    selectLoopContinuation(
                        conditionalTarget = conditionalTarget,
                        conditionalArm = (conditionalArm as ArmCollection.Success).blocks,
                        fallthroughTarget = fallthroughTarget,
                        fallthroughArm = (fallthroughArm as ArmCollection.Success).blocks,
                        context = context,
                    )
                ) {
                    LoopContinuation.CONDITIONAL -> effectiveConditionalTransfers = false
                    LoopContinuation.FALLTHROUGH -> effectiveFallthroughTransfers = false
                    LoopContinuation.AMBIGUOUS -> continue
                    LoopContinuation.CONDITIONAL_SPINE -> {
                        effectiveConditionalTransfers = false
                        usedContinuationSpine = true
                    }
                    LoopContinuation.FALLTHROUGH_SPINE -> {
                        effectiveFallthroughTransfers = false
                        usedContinuationSpine = true
                    }
                }
            }

            if (effectiveConditionalTransfers) {
                val arm = conditionalArm as ArmCollection.Success
                return LoopTransferRecognition(
                    StructuredRegion.If(
                        header = header,
                        condition = condition,
                        thenEntry = conditionalTarget,
                        thenBlocks = arm.blocks,
                        elseEntry = null,
                        elseBlocks = emptySet(),
                        continuation = fallthroughTarget,
                        thenExit = exit,
                        loopContinuationSpine = usedContinuationSpine,
                    ),
                    exit,
                    usedContinuationSpine,
                )
            }

            if (effectiveFallthroughTransfers) {
                val arm = fallthroughArm as ArmCollection.Success
                return LoopTransferRecognition(
                    StructuredRegion.If(
                        header = header,
                        condition = condition.negated(),
                        thenEntry = fallthroughTarget,
                        thenBlocks = arm.blocks,
                        elseEntry = null,
                        elseBlocks = emptySet(),
                        continuation = conditionalTarget,
                        thenExit = exit,
                        loopContinuationSpine = usedContinuationSpine,
                    ),
                    exit,
                    usedContinuationSpine,
                )
            }
        }
        return null
    }

    /**
     * Chooses the ordinary continuation when both successors eventually reach the same loop-end
     * transfer. Direct transparent tails are authoritative. Otherwise, use the physical forward
     * layout only when one complete transfer arm lies before the opposite successor: this is the
     * canonical JVM shape of `if (...) { ...; continue; } nextStatement`. The transformation is
     * semantics-preserving even when the original source used an equivalent two-arm form, while
     * avoiding arbitrary choices for symmetric transfer arms.
     */
    private fun selectLoopContinuation(
        conditionalTarget: BasicBlockId,
        conditionalArm: Set<BasicBlockId>,
        fallthroughTarget: BasicBlockId,
        fallthroughArm: Set<BasicBlockId>,
        context: LoopFlowContext,
    ): LoopContinuation {
        val conditionalIsNaturalTail = conditionalTarget != context.loop.header && conditionalTarget in context.continueTargets
        val fallthroughIsNaturalTail = fallthroughTarget != context.loop.header && fallthroughTarget in context.continueTargets
        if (conditionalIsNaturalTail != fallthroughIsNaturalTail) {
            return if (conditionalIsNaturalTail) LoopContinuation.CONDITIONAL else LoopContinuation.FALLTHROUGH
        }
        if (conditionalIsNaturalTail && fallthroughIsNaturalTail) return LoopContinuation.AMBIGUOUS

        val conditionalEndsBeforeFallthrough = armIsForwardPrefix(conditionalTarget, conditionalArm, fallthroughTarget)
        val fallthroughEndsBeforeConditional = armIsForwardPrefix(fallthroughTarget, fallthroughArm, conditionalTarget)
        return when {
            conditionalEndsBeforeFallthrough && !fallthroughEndsBeforeConditional -> LoopContinuation.FALLTHROUGH_SPINE
            fallthroughEndsBeforeConditional && !conditionalEndsBeforeFallthrough -> LoopContinuation.CONDITIONAL_SPINE
            else -> LoopContinuation.AMBIGUOUS
        }
    }

    private fun armIsForwardPrefix(
        entry: BasicBlockId,
        blocks: Set<BasicBlockId>,
        continuation: BasicBlockId,
    ): Boolean {
        if (blocks.isEmpty() || entry !in blocks) return false
        if (entry.value >= continuation.value) return false
        return blocks.all { it.value >= entry.value && it.value < continuation.value }
    }

    private fun collectTransferArm(
        start: BasicBlockId,
        continuation: BasicBlockId,
        transferTargets: Set<BasicBlockId>,
        header: BasicBlockId,
        allowedBlocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
    ): ArmCollection {
        if (start == continuation) return ArmCollection.Rejected(UnstructuredControlFlowReason.IDENTICAL_SUCCESSORS)
        if (start in transferTargets) return ArmCollection.Success(emptySet())
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        var transferSeen = false
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block in transferTargets) {
                transferSeen = true
                continue
            }
            if (block == continuation) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_HAS_OTHER_EXIT)
            if (block == header) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in allowedBlocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue

            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
            successors.forEach(queue::addLast)
        }
        if (!transferSeen) return ArmCollection.Rejected(UnstructuredControlFlowReason.UNSUPPORTED_SHAPE)
        return ArmCollection.Success(result)
    }

    /**
     * Recognizes `if (condition) { return/throw ... } continuation` when the two successors cannot
     * have a common post-dominator precisely because one side is a closed terminal region.
     */
    private fun recognizeTerminalIf(
        header: BasicBlockId,
        condition: StructuredCondition,
        conditionalTarget: BasicBlockId,
        fallthroughTarget: BasicBlockId,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        ignoredPredecessors: Set<BasicBlockId> = emptySet(),
    ): StructuredRegion.If? {
        val conditionalArm = collectTerminalArm(
            start = conditionalTarget,
            continuation = fallthroughTarget,
            header = header,
            blocks = blocks,
            outgoing = outgoing,
            explicitTerminalBlocks = explicitTerminalBlocks,
        )
        if (conditionalArm is ArmCollection.Success && singleEntryArm(conditionalArm.blocks, header, predecessors, ignoredPredecessors)) {
            return StructuredRegion.If(
                header = header,
                condition = condition,
                thenEntry = conditionalTarget,
                thenBlocks = conditionalArm.blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = fallthroughTarget,
                thenExit = StructuredArmExit(StructuredArmExitKind.RETURN_OR_THROW),
            )
        }

        val fallthroughArm = collectTerminalArm(
            start = fallthroughTarget,
            continuation = conditionalTarget,
            header = header,
            blocks = blocks,
            outgoing = outgoing,
            explicitTerminalBlocks = explicitTerminalBlocks,
        )
        if (fallthroughArm is ArmCollection.Success && singleEntryArm(fallthroughArm.blocks, header, predecessors, ignoredPredecessors)) {
            return StructuredRegion.If(
                header = header,
                condition = condition.negated(),
                thenEntry = fallthroughTarget,
                thenBlocks = fallthroughArm.blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = conditionalTarget,
                thenExit = StructuredArmExit(StructuredArmExitKind.RETURN_OR_THROW),
            )
        }
        return null
    }

    private fun collectTerminalArm(
        start: BasicBlockId,
        continuation: BasicBlockId,
        header: BasicBlockId,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ): ArmCollection {
        if (start == continuation) return ArmCollection.Rejected(UnstructuredControlFlowReason.IDENTICAL_SUCCESSORS)
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        var explicitTerminalSeen = false
        queue.add(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == continuation) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_HAS_OTHER_EXIT)
            if (block == header) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in blocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue

            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) {
                if (block !in explicitTerminalBlocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
                explicitTerminalSeen = true
                continue
            }
            successors.forEach(queue::addLast)
        }
        if (!explicitTerminalSeen) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
        return ArmCollection.Success(result)
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
        ignoredPredecessors: Set<BasicBlockId> = emptySet(),
    ): Boolean = arm.all { block ->
        predecessors[block].orEmpty().all { it == header || it in arm || it in ignoredPredecessors }
    }

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

    private fun List<ControlFlowEdge>.distinctTargets(): List<ControlFlowEdge> = distinctBy { it.to }

    private data class LoopRecognition(
        val regions: List<StructuredRegion.While>,
        val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
    )

    private data class LoopFlowContext(
        val loop: StructuredRegion.While,
        val continueTargets: Set<BasicBlockId>,
    )

    private enum class LoopContinuation {
        CONDITIONAL,
        FALLTHROUGH,
        CONDITIONAL_SPINE,
        FALLTHROUGH_SPINE,
        AMBIGUOUS,
    }

    private data class LoopTransferRecognition(
        val region: StructuredRegion.If,
        val exit: StructuredArmExit,
        val usedContinuationSpine: Boolean,
    )

    private data class SwitchRecognition(
        val regions: List<StructuredRegion.Switch>,
        val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
    )

    private sealed interface SwitchCaseCollection {
        data class Success(val blocks: Set<BasicBlockId>) : SwitchCaseCollection
        data class Rejected(val reason: UnstructuredControlFlowReason) : SwitchCaseCollection
    }

    private data class IfRecognition(
        val regions: List<StructuredRegion.If>,
        val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
        val emptyArmNormalizationCount: Int,
        val terminalIfRegionCount: Int,
        val continueIfRegionCount: Int,
        val breakIfRegionCount: Int,
        val loopBodyIfRegionCount: Int,
        val loopContinuationIfRegionCount: Int,
    )

    private sealed interface ArmCollection {
        data class Success(val blocks: Set<BasicBlockId>) : ArmCollection
        data class Rejected(val reason: UnstructuredControlFlowReason) : ArmCollection
    }
}
