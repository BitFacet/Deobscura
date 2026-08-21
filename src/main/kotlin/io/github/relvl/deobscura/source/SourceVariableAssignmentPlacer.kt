package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.SsaLocalAccess
import io.github.relvl.deobscura.analysis.SsaLocalAccessKind
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph

/**
 * Chooses a concrete source-order position for one copy required by SSA destruction.
 *
 * The variable analyzer decides which SSA versions form one source variable; this class owns the
 * separate placement proof. Exact JVM local writes are preferred, then whole-block exits. A true
 * edge copy is admitted only when SourceStructure keeps that predecessor's control transfer
 * explicit, so the renderer can emit the already-proven edge copy mechanically without having to
 * rediscover control-flow semantics.
 */
internal class SourceVariableAssignmentPlacer(
    graph: ControlFlowGraph,
    ssa: SsaAnalysis,
    controlFlow: SsaControlFlowGraph,
    sourceStructure: SourceStructureAnalysis,
    private val dominators: Map<BasicBlockId, Set<BasicBlockId>>,
) {
    private val outgoing = controlFlow.edges.asSequence()
        .filter { it.kind != ControlFlowEdgeKind.EXCEPTION }
        .groupBy { it.from }
    private val explicitControlBlocks = collectExplicitControlBlocks(sourceStructure.root)

    private val localWrites: List<LocalWrite>
    private val localWritesByBlockAndSlot: Map<LocalWriteKey, List<LocalWrite>>
    private val localWritesBySlotAndValue: Map<LocalWriteValueKey, List<LocalWrite>>

    init {
        val instructionToBlock = buildMap<Int, BasicBlockId> {
            graph.blocks.forEach { block ->
                for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
            }
        }
        localWrites = ssa.localAccesses.asSequence()
            .filter { it.kind == SsaLocalAccessKind.WRITE }
            .mapNotNull { access ->
                val block = instructionToBlock[access.instructionIndex] ?: return@mapNotNull null
                LocalWrite(block, access)
            }
            .toList()
        localWritesByBlockAndSlot = localWrites
            .groupBy { LocalWriteKey(it.block, it.access.slot) }
            .mapValues { (_, writes) -> writes.sortedBy { it.access.instructionIndex } }
        localWritesBySlotAndValue = localWrites
            .groupBy { LocalWriteValueKey(it.access.slot, it.access.value) }
            .mapValues { (_, writes) -> writes.sortedBy { it.access.instructionIndex } }
    }

    fun place(
        origin: SourceVariableOrigin,
        value: ValueId,
        predecessor: BasicBlockId,
        phiBlock: BasicBlockId,
    ): SourceVariablePlacement? {
        if (origin is SourceVariableOrigin.Local) {
            // A local phi input denotes one SSA version of this JVM slot. A unique dominating
            // xstore/iinc is the strongest placement proof because it preserves exact source order.
            val versionWrites = localWritesBySlotAndValue[LocalWriteValueKey(origin.slot, value)].orEmpty()
            if (versionWrites.size == 1) {
                val write = versionWrites.single()
                if (write.block in dominators[predecessor].orEmpty()) {
                    return SourceVariablePlacement(
                        SourceVariableAssignmentSite.Instruction(write.access.instructionIndex),
                        write.block,
                    )
                }
            }

            // Repeated stores of the same SSA value are still unambiguous when the predecessor's
            // final write establishes exactly the version consumed by this phi input.
            val predecessorWrites = localWritesByBlockAndSlot[LocalWriteKey(predecessor, origin.slot)].orEmpty()
            val finalWrite = predecessorWrites.lastOrNull()
            if (finalWrite?.access?.value == value) {
                return SourceVariablePlacement(
                    SourceVariableAssignmentSite.Instruction(finalWrite.access.instructionIndex),
                    finalWrite.block,
                )
            }
        }

        val targets = outgoing[predecessor].orEmpty().map { it.to }.distinct()
        if (targets == listOf(phiBlock)) {
            return SourceVariablePlacement(SourceVariableAssignmentSite.BlockExit(predecessor), predecessor)
        }

        // At this point the copy belongs to one specific CFG edge. Keep it only when the source
        // projection intentionally retained that predecessor as explicit control flow. Structured
        // arms need their own lexical part-placement proof and remain unresolved for now.
        if (phiBlock in targets && predecessor in explicitControlBlocks) {
            return SourceVariablePlacement(SourceVariableAssignmentSite.Edge(predecessor, phiBlock), predecessor)
        }
        return null
    }

    private fun collectExplicitControlBlocks(root: SourceBlock): Set<BasicBlockId> = buildSet {
        fun visit(block: SourceBlock) {
            block.nodes.forEach { node ->
                when (node) {
                    is SourceNode.Unstructured -> add(node.block)
                    is SourceNode.ProjectionFallback -> add(node.block)
                    is SourceNode.Structured -> node.parts.forEach { visit(it.body) }
                    is SourceNode.BasicBlock -> Unit
                }
            }
        }
        visit(root)
    }

    private data class LocalWrite(
        val block: BasicBlockId,
        val access: SsaLocalAccess,
    )

    private data class LocalWriteKey(
        val block: BasicBlockId,
        val slot: Int,
    )

    private data class LocalWriteValueKey(
        val slot: Int,
        val value: ValueId,
    )
}

internal data class SourceVariablePlacement(
    val site: SourceVariableAssignmentSite,
    val block: BasicBlockId,
)
