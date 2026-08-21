package io.github.relvl.deobscura.raw

/** Formats a JVM type while leaving object-name presentation to the caller. */
internal fun JvmType.formatTypeName(objectName: (String) -> String): String = when (this) {
    JvmType.BooleanType -> "boolean"
    JvmType.ByteType -> "byte"
    JvmType.CharType -> "char"
    JvmType.ShortType -> "short"
    JvmType.IntType -> "int"
    JvmType.LongType -> "long"
    JvmType.FloatType -> "float"
    JvmType.DoubleType -> "double"
    JvmType.VoidType -> "void"
    is JvmType.ObjectType -> objectName(internalName)
    is JvmType.ArrayType -> "${componentType.formatTypeName(objectName)}[]"
}
