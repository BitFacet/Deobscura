package io.github.relvl.deobscura.output

import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal fun classOutputFile(root: Path, internalName: String, extension: String): Path {
    val segments = internalName.split('/')
    require(segments.isNotEmpty() && segments.none(String::isEmpty)) {
        "Invalid class internal name '$internalName'."
    }
    val packageSegments = segments.dropLast(1).map(::encodePathSegment)
    val fileName = "${encodePathSegment(segments.last())}.$extension"
    return packageSegments.fold(root) { current, segment -> current.resolve(segment) }.resolve(fileName).normalize()
}

private fun encodePathSegment(segment: String): String {
    val encoded = buildString {
        segment.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 0xff
            val char = value.toChar()
            if (char.isAsciiFileNameCharacter()) {
                append(char)
            } else {
                append('%')
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }
    if (encoded.uppercase() !in WINDOWS_RESERVED_NAMES) return encoded
    val first = encoded[0].code
    return "%${HEX[first ushr 4]}${HEX[first and 0x0f]}${encoded.substring(1)}"
}

private fun Char.isAsciiFileNameCharacter(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-' || this == '$'

private const val HEX = "0123456789ABCDEF"
private val WINDOWS_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { number ->
        add("COM$number")
        add("LPT$number")
    }
}
