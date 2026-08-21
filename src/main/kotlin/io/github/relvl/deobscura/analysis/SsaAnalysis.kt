package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId

data class SsaAnalysis(
    val values: Map<ValueId, SsaValueDefinition>,
    val operations: List<ValueOperation>,
    val phiNodes: List<SsaPhiNode>,
    val uses: Map<ValueId, List<SsaValueUse>>,
    val constants: Map<ValueId, SsaConstant> = emptyMap(),
    val eliminatedLocalInstructionCount: Int,
    /** Source-reconstruction provenance for JVM local reads/writes eliminated from semantic SSA operations. */
    val localAccesses: List<SsaLocalAccess> = emptyList(),
) {
    fun typeOf(id: ValueId): JvmValueType = requireNotNull(values[id]) { "Unknown SSA value v${id.value}." }.type

    val localPhiCount: Int
        get() = phiNodes.count { it.location is SsaPhiLocation.Local }

    val stackPhiCount: Int
        get() = phiNodes.count { it.location is SsaPhiLocation.Stack }

    val useEdgeCount: Int
        get() = uses.values.sumOf { it.size }

    val phiBlockCount: Int
        get() = phiNodes.asSequence().map { it.blockId }.distinct().count()

    val trivialPhiCount: Int
        get() = phiNodes.count { phi ->
            phi.inputs.asSequence().map { it.value }.filter { it != phi.output }.distinct().count() <= 1
        }
}

/** JVM local-slot provenance retained after local load/store elimination. */
data class SsaLocalAccess(
    val instructionIndex: Int,
    val slot: Int,
    val kind: SsaLocalAccessKind,
    val value: ValueId,
)

enum class SsaLocalAccessKind { READ, WRITE }

sealed interface SsaValueDefinition {
    val id: ValueId
    val type: JvmValueType
    val kind: FrameValueKind get() = type.kind

    data class Root(
        override val id: ValueId,
        override val type: JvmValueType,
        val origin: ValueOrigin,
    ) : SsaValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, origin: ValueOrigin) : this(id, JvmValueType.of(kind), origin)
    }

    data class Instruction(
        override val id: ValueId,
        override val type: JvmValueType,
        val instructionIndex: Int,
    ) : SsaValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, instructionIndex: Int) : this(id, JvmValueType.of(kind), instructionIndex)
    }

    data class Phi(
        override val id: ValueId,
        override val type: JvmValueType,
        val blockId: BasicBlockId,
        val location: SsaPhiLocation,
        val inputs: List<SsaPhiInput>,
    ) : SsaValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, blockId: BasicBlockId, location: SsaPhiLocation, inputs: List<SsaPhiInput>) : this(id, JvmValueType.of(kind), blockId, location, inputs)
    }
}

sealed interface SsaPhiLocation {
    data class Local(val slot: Int) : SsaPhiLocation
    data class Stack(val index: Int) : SsaPhiLocation
}

data class SsaPhiInput(
    val value: ValueId,
    /** Null for conservative exceptional/frame-origin merges that are not predecessor-addressed yet. */
    val predecessor: BasicBlockId? = null,
)

data class SsaPhiNode(
    val output: ValueId,
    val blockId: BasicBlockId,
    val location: SsaPhiLocation,
    val inputs: List<SsaPhiInput>,
) {
    val isPredecessorAddressed: Boolean
        get() = inputs.all { it.predecessor != null }
}

sealed interface SsaValueUse {
    data class Operation(
        val instructionIndex: Int,
        val inputIndex: Int,
    ) : SsaValueUse

    data class Phi(
        val output: ValueId,
        val predecessor: BasicBlockId?,
        val inputIndex: Int,
    ) : SsaValueUse
}
