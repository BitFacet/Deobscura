package io.github.relvl.deobscura.raw

data class RawClass(
    val internalName: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val accessFlags: Int,
    val superName: String?,
    val interfaces: List<String>,
    val fields: List<RawField>,
    val methods: List<RawMethod>,
)

data class RawField(
    val name: String,
    val descriptor: String,
    val type: JvmType,
    val accessFlags: Int,
)

data class RawMethod(
    val name: String,
    val descriptor: String,
    val type: JvmMethodDescriptor,
    val accessFlags: Int,
    val exceptions: List<String>,
    val code: RawCode?,
)

data class RawCode(
    val maxStack: Int?,
    val maxLocals: Int?,
    val bytecodeLength: Int?,
    val instructions: List<RawInstruction>,
    val labels: List<RawLabel>,
    val exceptionHandlers: List<RawExceptionHandler>,
    val lineNumbers: List<RawLineNumber>,
)

@JvmInline
value class RawLabelId(val value: Int)

data class RawLabel(
    val id: RawLabelId,
    val instructionIndex: Int,
    val bytecodeOffset: Int?,
)

data class RawExceptionHandler(
    val tryStart: RawLabelId,
    val tryEnd: RawLabelId,
    val handler: RawLabelId,
    val catchType: String?,
)

data class RawLineNumber(
    val instructionIndex: Int,
    val line: Int,
)
