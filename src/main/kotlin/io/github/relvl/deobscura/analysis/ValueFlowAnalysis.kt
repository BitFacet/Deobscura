package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.RawInstruction

@JvmInline
value class ValueId(val value: Int)

data class ValueFlowAnalysis(
    val values: Map<ValueId, ValueDefinition>,
    val operations: List<ValueOperation>,
    val blockEntryLocals: Map<BasicBlockId, List<ValueId?>>,
    val blockEntryStacks: Map<BasicBlockId, List<ValueId>>,
    val blockExitLocals: Map<BasicBlockId, List<ValueId?>>,
    val blockExitStacks: Map<BasicBlockId, List<ValueId>>,
    val mergeValueCount: Int,
    val eliminatedStackInstructionCount: Int,
    val unanalyzedBlockCount: Int,
)

sealed interface ValueDefinition {
    val id: ValueId
    val type: JvmValueType
    val kind: FrameValueKind get() = type.kind

    data class Root(
        override val id: ValueId,
        override val type: JvmValueType,
        val origin: ValueOrigin,
    ) : ValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, origin: ValueOrigin) : this(id, JvmValueType.of(kind), origin)
    }

    data class Instruction(
        override val id: ValueId,
        override val type: JvmValueType,
        val instructionIndex: Int,
    ) : ValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, instructionIndex: Int) : this(id, JvmValueType.of(kind), instructionIndex)
    }

    data class Merge(
        override val id: ValueId,
        override val type: JvmValueType,
        val site: ValueMergeSite,
        val inputs: List<ValueId>,
    ) : ValueDefinition {
        constructor(id: ValueId, kind: FrameValueKind, site: ValueMergeSite, inputs: List<ValueId>) : this(id, JvmValueType.of(kind), site, inputs)
    }
}

sealed interface ValueMergeSite {
    val blockId: BasicBlockId

    data class Local(
        override val blockId: BasicBlockId,
        val slot: Int,
    ) : ValueMergeSite

    data class Stack(
        override val blockId: BasicBlockId,
        val index: Int,
    ) : ValueMergeSite
}

data class ValueOperation(
    val instructionIndex: Int,
    val instruction: RawInstruction,
    val inputs: List<ValueId>,
    val output: ValueId? = null,
    val localSlot: Int? = null,
)
