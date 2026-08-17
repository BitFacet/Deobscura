package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.RawConstantInstruction
import io.github.relvl.deobscura.raw.toReferenceType
import java.lang.constant.ClassDesc
import java.lang.constant.MethodHandleDesc
import java.lang.constant.MethodTypeDesc

enum class FrameValueKind(val category: Int) {
    INT(1),
    LONG(2),
    FLOAT(1),
    DOUBLE(2),
    REFERENCE(1),
    RETURN_ADDRESS(1),
}

sealed interface JvmValueType {
    val kind: FrameValueKind

    data class Computational(val type: JvmComputationalType) : JvmValueType {
        init {
            require(type != JvmComputationalType.REFERENCE && type != JvmComputationalType.VOID) {
                "$type is not a computational value type."
            }
        }

        override val kind: FrameValueKind = type.toFrameValueKind()
    }

    data class Reference(val referenceType: JvmReferenceType) : JvmValueType {
        override val kind: FrameValueKind = FrameValueKind.REFERENCE
    }

    companion object {
        fun of(kind: FrameValueKind): JvmValueType = when (kind) {
            FrameValueKind.INT -> Computational(JvmComputationalType.INT)
            FrameValueKind.LONG -> Computational(JvmComputationalType.LONG)
            FrameValueKind.FLOAT -> Computational(JvmComputationalType.FLOAT)
            FrameValueKind.DOUBLE -> Computational(JvmComputationalType.DOUBLE)
            FrameValueKind.REFERENCE -> Reference(JvmReferenceType.Unknown)
            FrameValueKind.RETURN_ADDRESS -> Computational(JvmComputationalType.RETURN_ADDRESS)
        }

        fun of(type: JvmComputationalType): JvmValueType = when (type) {
            JvmComputationalType.REFERENCE -> Reference(JvmReferenceType.Unknown)
            JvmComputationalType.VOID -> error("Void does not have a value type.")
            else -> Computational(type)
        }

        fun of(type: JvmType): JvmValueType = when (type) {
            JvmType.BooleanType -> Computational(JvmComputationalType.BOOLEAN)
            JvmType.ByteType -> Computational(JvmComputationalType.BYTE)
            JvmType.CharType -> Computational(JvmComputationalType.CHAR)
            JvmType.ShortType -> Computational(JvmComputationalType.SHORT)
            JvmType.IntType -> Computational(JvmComputationalType.INT)
            JvmType.LongType -> Computational(JvmComputationalType.LONG)
            JvmType.FloatType -> Computational(JvmComputationalType.FLOAT)
            JvmType.DoubleType -> Computational(JvmComputationalType.DOUBLE)
            is JvmType.ObjectType, is JvmType.ArrayType -> Reference(type.toReferenceType())
            JvmType.VoidType -> error("Void does not have a value type.")
        }
    }
}

sealed interface ValueOrigin {
    data class This(val ownerInternalName: String) : ValueOrigin
    data class Parameter(val index: Int) : ValueOrigin
    data class Instruction(val index: Int) : ValueOrigin
    data class ReturnAddress(val returnInstructionIndex: Int) : ValueOrigin
    data class ExceptionHandler(val handlerInstructionIndex: Int, val catchType: String?) : ValueOrigin
}

data class FrameValue(
    val type: JvmValueType,
    val origins: Set<ValueOrigin>,
) {
    val kind: FrameValueKind
        get() = type.kind

    val referenceType: JvmReferenceType?
        get() = (type as? JvmValueType.Reference)?.referenceType

    companion object {
        fun of(kind: FrameValueKind, origin: ValueOrigin): FrameValue =
            FrameValue(JvmValueType.of(kind), setOf(origin))

        fun of(type: JvmComputationalType, origin: ValueOrigin): FrameValue =
            FrameValue(JvmValueType.of(type), setOf(origin))

        fun of(type: JvmValueType, origin: ValueOrigin): FrameValue =
            FrameValue(type, setOf(origin))

        fun reference(type: JvmReferenceType, origin: ValueOrigin): FrameValue =
            FrameValue(JvmValueType.Reference(type), setOf(origin))

        fun of(type: JvmType, origin: ValueOrigin): FrameValue =
            FrameValue(JvmValueType.of(type), setOf(origin))
    }
}

data class FrameState(
    // null means verifier-style TOP/unavailable for a local slot. It is also used for the second
    // slot occupied by category-2 values (long/double). The operand stack never contains TOP.
    val locals: List<FrameValue?>,
    val stack: List<FrameValue>,
)

internal fun JvmComputationalType.toFrameValueKind(): FrameValueKind = when (this) {
    JvmComputationalType.BOOLEAN,
    JvmComputationalType.BYTE,
    JvmComputationalType.CHAR,
    JvmComputationalType.SHORT,
    JvmComputationalType.INT,
        -> FrameValueKind.INT

    JvmComputationalType.LONG -> FrameValueKind.LONG
    JvmComputationalType.FLOAT -> FrameValueKind.FLOAT
    JvmComputationalType.DOUBLE -> FrameValueKind.DOUBLE
    JvmComputationalType.REFERENCE -> FrameValueKind.REFERENCE
    JvmComputationalType.RETURN_ADDRESS -> FrameValueKind.RETURN_ADDRESS
    JvmComputationalType.VOID -> error("Void does not have a frame value kind.")
}

internal fun JvmType.toFrameValueKind(): FrameValueKind = JvmValueType.of(this).kind

internal fun RawConstantInstruction.toValueType(): JvmValueType {
    if (type != JvmComputationalType.REFERENCE) return JvmValueType.of(type)
    val referenceType = when {
        opcode.mnemonic == "aconst_null" -> JvmReferenceType.Null
        value.javaClass == String::class.java -> exactObjectType("java/lang/String")
        value is ClassDesc -> exactObjectType("java/lang/Class")
        value is MethodTypeDesc -> exactObjectType("java/lang/invoke/MethodType")
        value is MethodHandleDesc -> exactObjectType("java/lang/invoke/MethodHandle")
        else -> JvmReferenceType.Unknown
    }
    return JvmValueType.Reference(referenceType)
}

private fun exactObjectType(internalName: String): JvmReferenceType =
    JvmReferenceType.Exact(JvmType.ObjectType(internalName))
