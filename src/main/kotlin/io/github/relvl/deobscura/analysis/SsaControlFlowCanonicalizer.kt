package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawSwitchInstruction

/**
 * Canonicalizes the analysis-only CFG after semantic SSA simplification.
 *
 * RawCode and the raw CFG are never rewritten. Control-transfer instructions that are fully
 * represented by the optimized CFG can disappear from SSA, and semantically empty passthrough
 * blocks can be bypassed by redirected analysis edges. Rewrites touching exception edges are kept
 * conservative until exceptional SSA is edge-precise.
 */
class SsaControlFlowCanonicalizer {
    fun canonicalize(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
    ): SsaControlFlowCanonicalizationResult {
        var currentFlow = controlFlow
        var currentAnalysis = analysis
        var removedPassthroughBlockCount = 0
        var removedControlFlowOperationCount = 0
        var removedGotoOperationCount = 0
        var collapsedControlFlowOperationCount = 0
        var collapsedEdgeCount = 0
        var redirectedEdgeCount = 0

        val gotoRemoval = removeDirectGotos(graph, currentFlow, currentAnalysis)
        currentAnalysis = gotoRemoval.analysis
        removedGotoOperationCount += gotoRemoval.removedCount
        removedControlFlowOperationCount += gotoRemoval.removedCount

        while (true) {
            val terminator = removeOneRedundantTerminator(graph, currentFlow, currentAnalysis)
            if (terminator != null) {
                currentFlow = terminator.controlFlow
                currentAnalysis = terminator.analysis
                removedControlFlowOperationCount++
                collapsedControlFlowOperationCount++
                collapsedEdgeCount += terminator.collapsedEdgeCount
                continue
            }

            val bypass = bypassOnePassthroughBlock(graph, currentFlow, currentAnalysis)
            if (bypass != null) {
                currentFlow = bypass.controlFlow
                currentAnalysis = bypass.analysis
                removedPassthroughBlockCount++
                redirectedEdgeCount += bypass.redirectedEdgeCount
                continue
            }

            break
        }

        return SsaControlFlowCanonicalizationResult(
            analysis = currentAnalysis,
            controlFlow = currentFlow,
            removedPassthroughBlockCount = removedPassthroughBlockCount,
            removedControlFlowOperationCount = removedControlFlowOperationCount,
            removedGotoOperationCount = removedGotoOperationCount,
            collapsedControlFlowOperationCount = collapsedControlFlowOperationCount,
            collapsedEdgeCount = collapsedEdgeCount,
            redirectedEdgeCount = redirectedEdgeCount,
        )
    }

    private fun removeDirectGotos(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
    ): GotoRemoval {
        val activeTerminalIndexes = graph.blocks.asSequence()
            .filter { it.id in controlFlow.blocks }
            .mapTo(mutableSetOf()) { it.endInstructionIndexExclusive - 1 }
        val removed = analysis.operations.filter { operation ->
            operation.instructionIndex in activeTerminalIndexes &&
                    operation.instruction is RawBranchInstruction &&
                    operation.instruction.opcode.mnemonic in DIRECT_GOTOS
        }.toSet()
        if (removed.isEmpty()) return GotoRemoval(analysis, 0)

        val operations = analysis.operations.filter { it !in removed }
        val uses = rebuildSsaUses(analysis.values, operations, analysis.phiNodes, "CFG canonicalization")
        return GotoRemoval(analysis.copy(operations = operations, uses = uses), removed.size)
    }

    private fun removeOneRedundantTerminator(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
    ): TerminatorRewrite? {
        val operationsByInstruction = analysis.operations.associateBy { it.instructionIndex }

        for (block in graph.blocks) {
            if (block.id !in controlFlow.blocks) continue
            val terminalIndex = block.endInstructionIndexExclusive - 1
            val operation = operationsByInstruction[terminalIndex] ?: continue
            val instruction = operation.instruction
            val normalOutgoing = controlFlow.edges.filter {
                it.from == block.id && it.kind != ControlFlowEdgeKind.EXCEPTION
            }

            val collapsible = when (instruction) {
                is RawBranchInstruction -> instruction.opcode.mnemonic !in DIRECT_GOTOS
                is RawSwitchInstruction -> true
                else -> false
            }
            if (!collapsible || normalOutgoing.isEmpty()) continue

            val target = normalOutgoing.first().to
            if (normalOutgoing.any { it.to != target }) continue
            val collapsed = collapseOutgoingEdges(graph, controlFlow, analysis, block.id, target, normalOutgoing)
                ?: continue

            return TerminatorRewrite(
                analysis = removeOperation(collapsed.analysis, operation),
                controlFlow = collapsed.controlFlow,
                collapsedEdgeCount = normalOutgoing.size - 1,
            )
        }

        return null
    }

    private fun collapseOutgoingEdges(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
        source: BasicBlockId,
        target: BasicBlockId,
        outgoing: List<ControlFlowEdge>,
    ): FlowAndAnalysis? {
        val incomingToTarget = controlFlow.edges.filter { it.to == target }
        val targetPhis = analysis.phiNodes.filter { it.blockId == target }
        if (targetPhis.isNotEmpty()) {
            if (incomingToTarget.any { it.kind == ControlFlowEdgeKind.EXCEPTION }) return null
            if (targetPhis.any { it.inputs.size != incomingToTarget.size }) return null

            val collapsedIndexes = incomingToTarget.indices.filter { incomingToTarget[it] in outgoing }
            if (collapsedIndexes.isEmpty()) return null
            for (phi in targetPhis) {
                val values = collapsedIndexes.map { phi.inputs[it] }.distinct()
                if (values.size != 1) return null
            }
        }

        val canonicalEdge = ControlFlowEdge(
            from = source,
            to = target,
            kind = canonicalUnconditionalKind(graph, controlFlow, source, target),
        )
        val firstOutgoingIndex = controlFlow.edges.indexOfFirst { it in outgoing }
        val newEdges = buildList {
            controlFlow.edges.forEachIndexed { index, edge ->
                when {
                    index == firstOutgoingIndex -> add(canonicalEdge)
                    edge in outgoing -> Unit
                    else -> add(edge)
                }
            }
        }

        val rewrittenPhis = if (targetPhis.isEmpty() || outgoing.size == 1) {
            analysis.phiNodes
        } else {
            val firstIncomingIndex = incomingToTarget.indexOfFirst { it in outgoing }
            analysis.phiNodes.map { phi ->
                if (phi.blockId != target) return@map phi
                val inputs = buildList {
                    phi.inputs.forEachIndexed { index, input ->
                        when {
                            index == firstIncomingIndex -> add(input)
                            incomingToTarget[index] in outgoing -> Unit
                            else -> add(input)
                        }
                    }
                }
                phi.copy(inputs = inputs)
            }
        }

        val rewrittenAnalysis = rewritePhiDefinitions(analysis, rewrittenPhis)
        return FlowAndAnalysis(controlFlow.copy(edges = newEdges), rewrittenAnalysis)
    }

    private fun bypassOnePassthroughBlock(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        analysis: SsaAnalysis,
    ): PassthroughRewrite? {
        val blockByInstruction = IntArray(graph.code.instructions.size) { -1 }
        graph.blocks.forEach { block ->
            for (instructionIndex in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                blockByInstruction[instructionIndex] = block.id.value
            }
        }
        val operationsByBlock = analysis.operations.groupBy { operation ->
            BasicBlockId(blockByInstruction[operation.instructionIndex])
        }
        val phiBlocks = analysis.phiNodes.mapTo(mutableSetOf()) { it.blockId }

        for (block in graph.blocks) {
            val blockId = block.id
            if (blockId !in controlFlow.blocks || blockId == controlFlow.entryBlock) continue
            if (blockId in phiBlocks) continue
            if (operationsByBlock[blockId].orEmpty().isNotEmpty()) continue

            val incoming = controlFlow.edges.filter { it.to == blockId }
            val outgoing = controlFlow.edges.filter { it.from == blockId }
            if (incoming.isEmpty() || outgoing.size != 1) continue
            if (incoming.any { it.kind == ControlFlowEdgeKind.EXCEPTION }) continue
            val exit = outgoing.single()
            if (exit.kind == ControlFlowEdgeKind.EXCEPTION || exit.to == blockId) continue

            val target = exit.to
            val targetIncoming = controlFlow.edges.filter { it.to == target }
            val targetPhis = analysis.phiNodes.filter { it.blockId == target }
            if (targetPhis.isNotEmpty()) {
                if (targetIncoming.any { it.kind == ControlFlowEdgeKind.EXCEPTION }) continue
                if (targetPhis.any { it.inputs.size != targetIncoming.size }) continue
            }

            val redirected = incoming.map { edge -> redirectEdge(edge, exit, target) }
            val unaffected = controlFlow.edges.filter { it.from != blockId && it.to != blockId }
            if (hasDuplicateEdges(unaffected + redirected)) continue

            val exitGlobalIndex = controlFlow.edges.indexOf(exit)
            val newEdges = buildList {
                controlFlow.edges.forEachIndexed { index, edge ->
                    when {
                        index == exitGlobalIndex -> addAll(redirected)
                        edge.from == blockId || edge.to == blockId -> Unit
                        else -> add(edge)
                    }
                }
            }

            val rewrittenPhis = if (targetPhis.isEmpty()) {
                analysis.phiNodes
            } else {
                val exitIncomingIndex = targetIncoming.indexOf(exit)
                if (exitIncomingIndex < 0) continue
                analysis.phiNodes.map { phi ->
                    if (phi.blockId != target) return@map phi
                    val replacement = phi.inputs[exitIncomingIndex]
                    val inputs = buildList {
                        phi.inputs.forEachIndexed { index, input ->
                            if (index == exitIncomingIndex) repeat(redirected.size) { add(replacement) } else add(input)
                        }
                    }
                    phi.copy(inputs = inputs)
                }
            }

            val rewrittenAnalysis = rewritePhiDefinitions(analysis, rewrittenPhis)
            val newBlocks = controlFlow.blocks.filterTo(linkedSetOf()) { it != blockId }
            return PassthroughRewrite(
                analysis = rewrittenAnalysis,
                controlFlow = controlFlow.copy(blocks = newBlocks, edges = newEdges),
                redirectedEdgeCount = redirected.size,
            )
        }

        return null
    }

    private fun removeOperation(analysis: SsaAnalysis, operation: ValueOperation): SsaAnalysis {
        val operations = analysis.operations.filter { it !== operation }
        val uses = rebuildSsaUses(analysis.values, operations, analysis.phiNodes, "CFG canonicalization")
        return analysis.copy(operations = operations, uses = uses)
    }

    private fun rewritePhiDefinitions(analysis: SsaAnalysis, phiNodes: List<SsaPhiNode>): SsaAnalysis {
        if (phiNodes == analysis.phiNodes) return analysis
        val phisByOutput = phiNodes.associateBy { it.output }
        val values = analysis.values.mapValues { (id, definition) ->
            if (definition !is SsaValueDefinition.Phi) return@mapValues definition
            val phi = phisByOutput[id]
                ?: throw SsaInconsistencyException("CFG canonicalization lost phi definition ${id.value}.")
            definition.copy(inputs = phi.inputs)
        }
        val uses = rebuildSsaUses(values, analysis.operations, phiNodes, "CFG canonicalization")
        return analysis.copy(values = values, phiNodes = phiNodes, uses = uses)
    }

    private fun redirectEdge(
        incoming: ControlFlowEdge,
        exit: ControlFlowEdge,
        target: BasicBlockId,
    ): ControlFlowEdge {
        val kind = if (incoming.kind == ControlFlowEdgeKind.FALLTHROUGH) exit.kind else incoming.kind
        return incoming.copy(to = target, kind = kind)
    }

    private fun canonicalUnconditionalKind(
        graph: ControlFlowGraph,
        controlFlow: SsaControlFlowGraph,
        source: BasicBlockId,
        target: BasicBlockId,
    ): ControlFlowEdgeKind {
        val active = graph.blocks.asSequence().map { it.id }.filter { it in controlFlow.blocks }.toList()
        val sourceIndex = active.indexOf(source)
        val next = active.getOrNull(sourceIndex + 1)
        return if (next == target) ControlFlowEdgeKind.FALLTHROUGH else ControlFlowEdgeKind.JUMP
    }

    private fun hasDuplicateEdges(edges: List<ControlFlowEdge>): Boolean = edges.size != edges.toSet().size

    private data class FlowAndAnalysis(
        val controlFlow: SsaControlFlowGraph,
        val analysis: SsaAnalysis,
    )

    private data class GotoRemoval(
        val analysis: SsaAnalysis,
        val removedCount: Int,
    )

    private data class TerminatorRewrite(
        val analysis: SsaAnalysis,
        val controlFlow: SsaControlFlowGraph,
        val collapsedEdgeCount: Int,
    )

    private data class PassthroughRewrite(
        val analysis: SsaAnalysis,
        val controlFlow: SsaControlFlowGraph,
        val redirectedEdgeCount: Int,
    )

    private companion object {
        val DIRECT_GOTOS = setOf("goto", "goto_w")
    }
}

data class SsaControlFlowCanonicalizationResult(
    val analysis: SsaAnalysis,
    val controlFlow: SsaControlFlowGraph,
    val removedPassthroughBlockCount: Int,
    val removedControlFlowOperationCount: Int,
    val removedGotoOperationCount: Int,
    val collapsedControlFlowOperationCount: Int,
    val collapsedEdgeCount: Int,
    val redirectedEdgeCount: Int,
)
