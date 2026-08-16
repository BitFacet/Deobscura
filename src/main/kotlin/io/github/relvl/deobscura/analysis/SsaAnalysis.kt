package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId

data class SsaAnalysis(
    val values: Map<ValueId, SsaValueDefinition>,
    val operations: List<ValueOperation>,
    val phiNodes: List<SsaPhiNode>,
    val uses: Map<ValueId, List<SsaValueUse>>,
    val eliminatedLocalInstructionCount: Int,
) {
    val localPhiCount: Int
        get() = phiNodes.count { it.location is SsaPhiLocation.Local }

    val stackPhiCount: Int
        get() = phiNodes.count { it.location is SsaPhiLocation.Stack }

    val useEdgeCount: Int
        get() = uses.values.sumOf { it.size }

    val phiBlockCount: Int
        get() = phiNodes.asSequence().map { it.blockId }.distinct().count()

    val trivialPhiCount: Int
        get() = phiNodes.count { it.inputs.distinct().size <= 1 }
}

sealed interface SsaValueDefinition {
    val id: ValueId
    val kind: FrameValueKind

    data class Root(
        override val id: ValueId,
        override val kind: FrameValueKind,
        val origin: ValueOrigin,
    ) : SsaValueDefinition

    data class Instruction(
        override val id: ValueId,
        override val kind: FrameValueKind,
        val instructionIndex: Int,
    ) : SsaValueDefinition

    data class Phi(
        override val id: ValueId,
        override val kind: FrameValueKind,
        val blockId: BasicBlockId,
        val location: SsaPhiLocation,
        val inputs: List<ValueId>,
    ) : SsaValueDefinition
}

sealed interface SsaPhiLocation {
    data class Local(val slot: Int) : SsaPhiLocation
    data class Stack(val index: Int) : SsaPhiLocation
}

data class SsaPhiNode(
    val output: ValueId,
    val blockId: BasicBlockId,
    val location: SsaPhiLocation,
    val inputs: List<ValueId>,
)

sealed interface SsaValueUse {
    data class Operation(
        val instructionIndex: Int,
        val inputIndex: Int,
    ) : SsaValueUse

    data class Phi(
        val output: ValueId,
        val inputIndex: Int,
    ) : SsaValueUse
}
