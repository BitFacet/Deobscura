package io.github.relvl.deobscura.raw

import java.lang.constant.ConstantDesc
import java.lang.constant.DirectMethodHandleDesc

@JvmInline
value class JvmOpcode(val mnemonic: String)

enum class JvmComputationalType {
    BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, REFERENCE, VOID, RETURN_ADDRESS, ;

    companion object {
        fun fromClassFileName(name: String): JvmComputationalType = valueOf(name)
    }
}

sealed interface RawInstruction {
    val opcode: JvmOpcode
}

data class RawConstantInstruction(
    override val opcode: JvmOpcode,
    val type: JvmComputationalType,
    val value: ConstantDesc,
) : RawInstruction

data class RawLocalInstruction(
    override val opcode: JvmOpcode,
    val operation: LocalOperation,
    val type: JvmComputationalType,
    val slot: Int,
) : RawInstruction

enum class LocalOperation { LOAD, STORE }

data class RawIncrementInstruction(
    override val opcode: JvmOpcode,
    val slot: Int,
    val amount: Int,
) : RawInstruction

data class RawArrayInstruction(
    override val opcode: JvmOpcode,
    val operation: ArrayOperation,
    val componentType: JvmComputationalType,
) : RawInstruction

enum class ArrayOperation { LOAD, STORE }

data class RawOperatorInstruction(
    override val opcode: JvmOpcode,
    val type: JvmComputationalType,
) : RawInstruction

data class RawConversionInstruction(
    override val opcode: JvmOpcode,
    val fromType: JvmComputationalType,
    val toType: JvmComputationalType,
) : RawInstruction

data class RawStackInstruction(override val opcode: JvmOpcode) : RawInstruction

data class RawBranchInstruction(
    override val opcode: JvmOpcode,
    val target: RawLabelId,
) : RawInstruction

data class RawSwitchInstruction(
    override val opcode: JvmOpcode,
    val defaultTarget: RawLabelId,
    val cases: List<RawSwitchCase>,
    val lowValue: Int? = null,
    val highValue: Int? = null,
) : RawInstruction

data class RawSwitchCase(val value: Int, val target: RawLabelId)

data class RawFieldInstruction(
    override val opcode: JvmOpcode,
    val owner: String,
    val name: String,
    val descriptor: String,
    val type: JvmType,
) : RawInstruction

data class RawInvokeInstruction(
    override val opcode: JvmOpcode,
    val owner: String,
    val name: String,
    val descriptor: String,
    val type: JvmMethodDescriptor,
    val isInterface: Boolean,
) : RawInstruction

data class RawInvokeDynamicInstruction(
    override val opcode: JvmOpcode,
    val name: String,
    val descriptor: String,
    val type: JvmMethodDescriptor,
    val bootstrapMethod: DirectMethodHandleDesc,
    val bootstrapArguments: List<ConstantDesc>,
) : RawInstruction

data class RawNewObjectInstruction(
    override val opcode: JvmOpcode,
    val internalName: String,
) : RawInstruction

data class RawNewArrayInstruction(
    override val opcode: JvmOpcode,
    val componentType: JvmType,
) : RawInstruction

data class RawNewMultiArrayInstruction(
    override val opcode: JvmOpcode,
    val arrayType: JvmType.ArrayType,
    val dimensions: Int,
) : RawInstruction

data class RawTypeCheckInstruction(
    override val opcode: JvmOpcode,
    val type: JvmType,
) : RawInstruction

data class RawReturnInstruction(
    override val opcode: JvmOpcode,
    val type: JvmComputationalType,
) : RawInstruction

data class RawMonitorInstruction(override val opcode: JvmOpcode) : RawInstruction

data class RawThrowInstruction(override val opcode: JvmOpcode) : RawInstruction

data class RawNopInstruction(override val opcode: JvmOpcode) : RawInstruction

data class RawRetInstruction(
    override val opcode: JvmOpcode,
    val slot: Int,
) : RawInstruction

data class RawUnknownInstruction(
    override val opcode: JvmOpcode,
    val classFileInstructionType: String,
) : RawInstruction
