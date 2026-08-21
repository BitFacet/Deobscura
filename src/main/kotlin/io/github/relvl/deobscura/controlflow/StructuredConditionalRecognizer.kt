package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.BranchCondition
import io.github.relvl.deobscura.expression.ComparisonOperator
import io.github.relvl.deobscura.expression.ExpressionStatement
import java.util.*

/** Recognizes conditional regions once method-wide facts and enclosing loops are known. */
internal class StructuredConditionalRecognizer {
    fun recognize(
        facts: ControlFlowFacts,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        excludedHeaders: Set<BasicBlockId>,
        loopContexts: List<LoopFlowContext>,
        shortCircuitByRoot: Map<BasicBlockId, ShortCircuitConditionFold>,
        exceptionRegions: List<StructuredRegion>,
    ): IfRecognition {
        val blocks = facts.blocks
        val outgoing = facts.outgoing
        val predecessors = facts.predecessors
        val postDominators = facts.postDominators
        val explicitTerminalBlocks = facts.explicitTerminalBlocks
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

            val containingLoop = loopContexts.asSequence().filter { header in it.loop.bodyBlocks }.minByOrNull { it.loop.bodyBlocks.size }
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
                val terminalContinuationRegion = terminalRegion ?: recognizeTerminalContinuationIf(
                    header = header,
                    condition = condition,
                    conditionalTarget = conditionalTarget,
                    fallthroughTarget = fallthroughTarget,
                    blocks = blocks,
                    outgoing = outgoing,
                    predecessors = predecessors,
                    explicitTerminalBlocks = explicitTerminalBlocks,
                    ignoredPredecessors = ignoredArmPredecessors,
                    exceptionRegions = exceptionRegions,
                )
                if (terminalContinuationRegion != null) {
                    regions += terminalContinuationRegion
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
            if (!singleEntryArm(thenBlocks, header, predecessors, ignoredArmPredecessors) || !singleEntryArm(elseBlocks, header, predecessors, ignoredArmPredecessors)) {
                rejections[header] = UnstructuredControlFlowReason.EXTERNAL_ARM_ENTRY
                return@forEach
            }

            if (thenBlocks.isEmpty() && elseBlocks.isNotEmpty()) { // Prefer a non-empty `then` arm in the source view. This removes shapes such as
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
        start: BasicBlockId, target: BasicBlockId, loop: StructuredRegion.While, outgoing: Map<BasicBlockId, List<ControlFlowEdge>>, transferTargets: Set<BasicBlockId>
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
        val loop = context.loop // Prefer an explicit edge to the canonical loop exit over describing the opposite arm as
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
            val conditionalTransfers = conditionalArm is ArmCollection.Success && singleEntryArm(conditionalArm.blocks, header, predecessors, ignoredPredecessors)

            val fallthroughArm = collectTransferArm(
                start = fallthroughTarget,
                continuation = conditionalTarget,
                transferTargets = transferTargets,
                header = header,
                allowedBlocks = loop.bodyBlocks,
                outgoing = outgoing,
            )
            val fallthroughTransfers = fallthroughArm is ArmCollection.Success && singleEntryArm(fallthroughArm.blocks, header, predecessors, ignoredPredecessors)

            var effectiveConditionalTransfers = conditionalTransfers
            var effectiveFallthroughTransfers = fallthroughTransfers
            var usedContinuationSpine = false
            if (conditionalTransfers && fallthroughTransfers) {
                if (exit.kind != StructuredArmExitKind.CONTINUE) continue
                when (selectLoopContinuation(
                    conditionalTarget = conditionalTarget,
                    conditionalArm = conditionalArm.blocks,
                    fallthroughTarget = fallthroughTarget,
                    fallthroughArm = fallthroughArm.blocks,
                    context = context,
                )) {
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
            return if (conditionalIsNaturalTail) LoopContinuation.CONDITIONAL
            else LoopContinuation.FALLTHROUGH
        }
        if (conditionalIsNaturalTail) return LoopContinuation.AMBIGUOUS

        val conditionalEndsBeforeFallthrough = armIsForwardPrefix(conditionalTarget, conditionalArm, fallthroughTarget)
        val fallthroughEndsBeforeConditional = armIsForwardPrefix(fallthroughTarget, fallthroughArm, conditionalTarget)
        return when {
            conditionalEndsBeforeFallthrough && !fallthroughEndsBeforeConditional -> LoopContinuation.FALLTHROUGH_SPINE
            fallthroughEndsBeforeConditional && !conditionalEndsBeforeFallthrough -> LoopContinuation.CONDITIONAL_SPINE
            else -> LoopContinuation.AMBIGUOUS
        }
    }

    private fun armIsForwardPrefix(entry: BasicBlockId, blocks: Set<BasicBlockId>, continuation: BasicBlockId): Boolean {
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
     * Recognizes an `if` whose linear continuation is itself terminal while the other arm can either
     * reach that continuation or terminate earlier. This covers source shapes such as
     * `if (condition) { if (nested) return A } return B`, where the outer header has no common
     * post-dominator even though its source ownership is still single-entry and well-defined.
     */
    private fun recognizeTerminalContinuationIf(
        header: BasicBlockId,
        condition: StructuredCondition,
        conditionalTarget: BasicBlockId,
        fallthroughTarget: BasicBlockId,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        predecessors: Map<BasicBlockId, List<BasicBlockId>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
        ignoredPredecessors: Set<BasicBlockId>,
        exceptionRegions: List<StructuredRegion>,
    ): StructuredRegion.If? {
        val candidates = buildList {
            if (conditionalTarget in explicitTerminalBlocks) {
                add(Triple(fallthroughTarget, conditionalTarget, condition.negated()))
            }
            if (fallthroughTarget in explicitTerminalBlocks) {
                add(Triple(conditionalTarget, fallthroughTarget, condition))
            }
        }
        for ((armStart, continuation, sourceCondition) in candidates) {
            val arm = collectArmWithTerminalSideExits(
                start = armStart,
                continuation = continuation,
                header = header,
                blocks = blocks,
                outgoing = outgoing,
                explicitTerminalBlocks = explicitTerminalBlocks,
            )
            if (arm !is ArmCollection.Success || arm.blocks.isEmpty()) continue
            if (!singleEntryArm(arm.blocks, header, predecessors, ignoredPredecessors)) continue
            val coveredBlocks = arm.blocks + header
            if (exceptionRegions.any { coveredBlocks.crosses(it.coveredBlocks) }) continue
            return StructuredRegion.If(
                header = header,
                condition = sourceCondition,
                thenEntry = armStart,
                thenBlocks = arm.blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = continuation,
            )
        }
        return null
    }

    private fun collectArmWithTerminalSideExits(
        start: BasicBlockId,
        continuation: BasicBlockId,
        header: BasicBlockId,
        blocks: Set<BasicBlockId>,
        outgoing: Map<BasicBlockId, List<ControlFlowEdge>>,
        explicitTerminalBlocks: Set<BasicBlockId>,
    ): ArmCollection {
        val result = linkedSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        var continuationSeen = false
        var terminalSideExitSeen = false
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            if (block == continuation) {
                continuationSeen = true
                continue
            }
            if (block == header) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_REENTERS_HEADER)
            if (block !in blocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_LEAVES_REACHABLE_FLOW)
            if (!result.add(block)) continue

            val successors = outgoing[block].orEmpty().distinctTargets().map { it.to }
            if (successors.isEmpty()) {
                if (block !in explicitTerminalBlocks) return ArmCollection.Rejected(UnstructuredControlFlowReason.TERMINAL_ARM)
                terminalSideExitSeen = true
                continue
            }
            successors.forEach(queue::addLast)
        }
        if (!continuationSeen || !terminalSideExitSeen) return ArmCollection.Rejected(UnstructuredControlFlowReason.UNSUPPORTED_SHAPE)
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
        ).takeIf { it is ArmCollection.Success && singleEntryArm(it.blocks, header, predecessors, ignoredPredecessors) } as? ArmCollection.Success

        val fallthroughArm = collectTerminalArm(
            start = fallthroughTarget,
            continuation = conditionalTarget,
            header = header,
            blocks = blocks,
            outgoing = outgoing,
            explicitTerminalBlocks = explicitTerminalBlocks,
        ).takeIf { it is ArmCollection.Success && singleEntryArm(it.blocks, header, predecessors, ignoredPredecessors) } as? ArmCollection.Success

        if (conditionalArm == null && fallthroughArm == null) return null

        // When both successors terminate, keep the smaller/local terminal path inside the `if` and
        // let the larger path remain linear source continuation. This avoids source shapes such as
        // `if (!guard) { ...whole method... } return fallback` that depend on bytecode branch layout.
        val useFallthroughArm = when {
            conditionalArm == null -> true
            fallthroughArm == null -> false
            fallthroughTarget in explicitTerminalBlocks && conditionalTarget !in explicitTerminalBlocks -> true
            conditionalTarget in explicitTerminalBlocks && fallthroughTarget !in explicitTerminalBlocks -> false
            fallthroughArm.blocks.size < conditionalArm.blocks.size -> true
            else -> false // Equivalent choices keep the original condition polarity.
        }

        return if (useFallthroughArm) {
            StructuredRegion.If(
                header = header,
                condition = condition.negated(),
                thenEntry = fallthroughTarget,
                thenBlocks = requireNotNull(fallthroughArm).blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = conditionalTarget,
                thenExit = StructuredArmExit(StructuredArmExitKind.RETURN_OR_THROW),
            )
        } else {
            StructuredRegion.If(
                header = header,
                condition = condition,
                thenEntry = conditionalTarget,
                thenBlocks = requireNotNull(conditionalArm).blocks,
                elseEntry = null,
                elseBlocks = emptySet(),
                continuation = fallthroughTarget,
                thenExit = StructuredArmExit(StructuredArmExitKind.RETURN_OR_THROW),
            )
        }
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
        if (result.any { block -> outgoing[block].orEmpty().distinctTargets().any { it.to !in result && it.to != join } }) return ArmCollection.Rejected(UnstructuredControlFlowReason.ARM_HAS_OTHER_EXIT)
        return ArmCollection.Success(result)
    }

    private fun singleEntryArm(
        arm: Set<BasicBlockId>, header: BasicBlockId, predecessors: Map<BasicBlockId, List<BasicBlockId>>, ignoredPredecessors: Set<BasicBlockId> = emptySet()
    ): Boolean = arm.all { block -> predecessors[block].orEmpty().all { it == header || it in arm || it in ignoredPredecessors } }

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

    private enum class LoopContinuation {
        CONDITIONAL, FALLTHROUGH, CONDITIONAL_SPINE, FALLTHROUGH_SPINE, AMBIGUOUS,
    }

    private data class LoopTransferRecognition(
        val region: StructuredRegion.If,
        val exit: StructuredArmExit,
        val usedContinuationSpine: Boolean,
    )

    private fun Set<BasicBlockId>.crosses(other: Set<BasicBlockId>): Boolean {
        if (intersect(other).isEmpty()) return false
        return !containsAll(other) && !other.containsAll(this)
    }

    private sealed interface ArmCollection {
        data class Success(val blocks: Set<BasicBlockId>) : ArmCollection
        data class Rejected(val reason: UnstructuredControlFlowReason) : ArmCollection
    }
}

internal data class IfRecognition(
    val regions: List<StructuredRegion.If>,
    val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
    val emptyArmNormalizationCount: Int,
    val terminalIfRegionCount: Int,
    val continueIfRegionCount: Int,
    val breakIfRegionCount: Int,
    val loopBodyIfRegionCount: Int,
    val loopContinuationIfRegionCount: Int,
)
