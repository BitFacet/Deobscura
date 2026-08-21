package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.formatTypeName

/** Formats an analyzed value type while leaving reference-name policy to the caller. */
internal fun JvmValueType.formatTypeName(
    objectName: (String) -> String,
    nullTypeName: String,
    unknownReferenceName: String,
): String = when (this) {
    is JvmValueType.Computational -> type.name.lowercase()
    is JvmValueType.Reference -> when (val reference = referenceType) {
        is JvmReferenceType.Exact -> reference.type.formatTypeName(objectName)
        JvmReferenceType.Null -> nullTypeName
        JvmReferenceType.Unknown -> unknownReferenceName
    }
}
