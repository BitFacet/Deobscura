package io.github.relvl.deobscura.cfg

import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawInstruction

data class ControlFlowGraph(
    val code: RawCode,
    val blocks: List<BasicBlock>,
    val edges: List<ControlFlowEdge>,
    val entryBlock: BasicBlockId?,
) {
    private val blocksById = blocks.associateBy { it.id }

    fun block(id: BasicBlockId): BasicBlock = requireNotNull(blocksById[id]) { "Unknown basic block id: ${id.value}" }

    fun instructions(block: BasicBlock): List<RawInstruction> = code.instructions.subList(block.startInstructionIndex, block.endInstructionIndexExclusive)
}

@JvmInline
value class BasicBlockId(val value: Int)

data class BasicBlock(
    val id: BasicBlockId,
    val startInstructionIndex: Int,
    val endInstructionIndexExclusive: Int,
    val predecessors: List<BasicBlockId>,
    val successors: List<BasicBlockId>,
)

data class ControlFlowEdge(
    val from: BasicBlockId,
    val to: BasicBlockId,
    val kind: ControlFlowEdgeKind,
    val switchValue: Int? = null,
    val catchType: String? = null,
)

enum class ControlFlowEdgeKind {
    FALLTHROUGH, CONDITIONAL, JUMP, SWITCH, EXCEPTION,
}
