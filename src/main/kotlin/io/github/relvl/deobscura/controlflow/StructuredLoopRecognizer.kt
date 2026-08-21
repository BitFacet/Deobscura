package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionStatement
import java.util.*

/** Recognizes natural loops and derives loop-relative transfer context for nested regions. */
internal class StructuredLoopRecognizer {
    fun recognize(facts: ControlFlowFacts, branches: Map<BasicBlockId, ExpressionStatement.Branch>): LoopRecognition {
        val regions = mutableListOf<StructuredRegion.While>()
        val rejections = linkedMapOf<BasicBlockId, UnstructuredControlFlowReason>()
        val backEdges = facts.normalEdges.filter { edge -> edge.to in facts.dominators[edge.from].orEmpty() }
        backEdges.groupBy { it.to }.forEach { (header, latchesEdges) ->
            val branch = branches[header] ?: return@forEach
            val condition = branch.condition ?: return@forEach
            val successors = facts.outgoing[header].orEmpty().distinctTargets()
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
                facts.predecessors[current].orEmpty().forEach { predecessor ->
                    if (loopBlocks.add(predecessor) && predecessor != header) queue.addLast(predecessor)
                }
            }
            if (!loopBlocks.all { header in facts.dominators[it].orEmpty() }) {
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

            val externalExits = loopBlocks.asSequence().flatMap { facts.outgoing[it].orEmpty().asSequence() }.filter { it.to !in loopBlocks }.toList()
            if (externalExits.any { it.to != exitEdge.to }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_HAS_ADDITIONAL_EXIT
                return@forEach
            }
            val breakEdges = externalExits.asSequence().filter { it.from != header }.map { it.from to it.to }.toCollection(linkedSetOf())

            val body = loopBlocks - header
            if (body.any { block -> facts.predecessors[block].orEmpty().any { it !in loopBlocks } }) {
                rejections[header] = UnstructuredControlFlowReason.LOOP_HAS_EXTERNAL_ENTRY
                return@forEach
            }

            val conditionalTarget = facts.outgoing[header].orEmpty().firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to
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

    fun contexts(regions: List<StructuredRegion.While>, facts: ControlFlowFacts, expression: ExpressionAnalysis): Map<BasicBlockId, LoopFlowContext> = regions.associate { loop ->
        loop.header to LoopFlowContext(
            loop = loop,
            continueTargets = transparentLoopContinueTargets(loop, facts, expression),
        )
    }

    fun naturalContexts(facts: ControlFlowFacts, expression: ExpressionAnalysis): List<NaturalLoopFlowContext> {
        val backEdges = facts.normalEdges.filter { edge -> edge.to in facts.dominators[edge.from].orEmpty() }
        return backEdges.groupBy { it.to }.mapNotNull { (header, latchEdges) ->
            val loopBlocks = naturalLoopBlocks(header, latchEdges.map { it.from }, facts) ?: return@mapNotNull null
            val exitTargets = loopBlocks.asSequence().flatMap { facts.outgoing[it].orEmpty().asSequence() }.filter { it.to !in loopBlocks }.map { it.to }.distinct().toList()
            val headerExitTargets = facts.outgoing[header].orEmpty().asSequence().map { it.to }.filter { it !in loopBlocks }.distinct().toList()
            NaturalLoopFlowContext(
                header = header,
                blocks = loopBlocks,
                exit = headerExitTargets.singleOrNull() ?: exitTargets.singleOrNull(),
                continueTargets = transparentLoopContinueTargets(
                    header = header,
                    bodyBlocks = loopBlocks - header,
                    facts = facts,
                    expression = expression,
                ),
            )
        }
    }

    private fun naturalLoopBlocks(header: BasicBlockId, latches: Collection<BasicBlockId>, facts: ControlFlowFacts): Set<BasicBlockId>? {
        val loopBlocks = linkedSetOf(header)
        val queue = ArrayDeque<BasicBlockId>()
        latches.distinct().forEach {
            if (loopBlocks.add(it)) queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            facts.predecessors[current].orEmpty().forEach { predecessor ->
                if (loopBlocks.add(predecessor) && predecessor != header) queue.addLast(predecessor)
            }
        }
        return loopBlocks.takeIf { blocks -> blocks.all { header in facts.dominators[it].orEmpty() } }
    }

    private fun transparentLoopContinueTargets(loop: StructuredRegion.While, facts: ControlFlowFacts, expression: ExpressionAnalysis): Set<BasicBlockId> = transparentLoopContinueTargets(
        header = loop.header,
        bodyBlocks = loop.bodyBlocks,
        facts = facts,
        expression = expression,
    )

    private fun transparentLoopContinueTargets(header: BasicBlockId, bodyBlocks: Set<BasicBlockId>, facts: ControlFlowFacts, expression: ExpressionAnalysis): Set<BasicBlockId> {
        val valuesByBlock = expression.values.values.asSequence().filter { it.instructionIndices.isNotEmpty() }.groupBy { facts.instructionToBlock.getOrNull(it.instructionIndices.last()) }
        val statementsByBlock = expression.statements.groupBy { facts.instructionToBlock.getOrNull(it.instructionIndex) }
        val result = linkedSetOf(header)
        var changed: Boolean
        do {
            changed = false
            for (block in bodyBlocks) {
                if (block in result || !isTransparentTransferBlock(block, valuesByBlock, statementsByBlock, expression)) continue
                val targets = facts.outgoing[block].orEmpty().distinctTargets().map { it.to }
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
        return statementsByBlock[block].orEmpty().all {
            it is ExpressionStatement.Branch && it.condition == null
        }
    }
}

internal data class LoopRecognition(
    val regions: List<StructuredRegion.While>,
    val rejections: Map<BasicBlockId, UnstructuredControlFlowReason>,
)
