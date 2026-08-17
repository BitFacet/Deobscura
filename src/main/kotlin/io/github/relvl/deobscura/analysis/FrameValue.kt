package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.toReferenceType

enum class FrameValueKind(val category: Int) {
    INT(1),
    LONG(2),
    FLOAT(1),
    DOUBLE(2),
    REFERENCE(1),
    RETURN_ADDRESS(1),
}

sealed interface ValueOrigin {
    data class This(val ownerInternalName: String) : ValueOrigin
    data class Parameter(val index: Int) : ValueOrigin
    data class Instruction(val index: Int) : ValueOrigin
    data class ReturnAddress(val returnInstructionIndex: Int) : ValueOrigin
    data class ExceptionHandler(val handlerInstructionIndex: Int, val catchType: String?) : ValueOrigin
}

data class FrameValue(
    val kind: FrameValueKind,
    val origins: Set<ValueOrigin>,
    val referenceType: JvmReferenceType? = if (kind == FrameValueKind.REFERENCE) JvmReferenceType.Unknown else null,
) {
    init {
        require((kind == FrameValueKind.REFERENCE) == (referenceType != null)) {
            "Reference type must be present exactly for REFERENCE frame values."
        }
    }

    companion object {
        fun of(kind: FrameValueKind, origin: ValueOrigin): FrameValue = FrameValue(kind, setOf(origin))

        fun reference(type: JvmReferenceType, origin: ValueOrigin): FrameValue =
            FrameValue(FrameValueKind.REFERENCE, setOf(origin), type)

        fun of(type: JvmType, origin: ValueOrigin): FrameValue =
            if (type is JvmType.ObjectType || type is JvmType.ArrayType) {
                reference(type.toReferenceType(), origin)
            } else {
                FrameValue(type.toFrameValueKind(), setOf(origin))
            }
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

internal fun JvmType.toFrameValueKind(): FrameValueKind = when (this) {
    JvmType.BooleanType,
    JvmType.ByteType,
    JvmType.CharType,
    JvmType.ShortType,
    JvmType.IntType,
        -> FrameValueKind.INT

    JvmType.LongType -> FrameValueKind.LONG
    JvmType.FloatType -> FrameValueKind.FLOAT
    JvmType.DoubleType -> FrameValueKind.DOUBLE
    is JvmType.ObjectType, is JvmType.ArrayType -> FrameValueKind.REFERENCE
    JvmType.VoidType -> error("Void does not have a frame value kind.")
}
