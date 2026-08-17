package io.github.relvl.deobscura.diagnostics.ir

import java.nio.charset.StandardCharsets
import java.nio.file.Path

class TechnicalIrLocator(
    val root: Path,
) {
    fun classFile(ownerInternalName: String): Path {
        val segments = ownerInternalName.split('/')
        require(segments.isNotEmpty() && segments.none(String::isEmpty)) {
            "Invalid class internal name '$ownerInternalName'."
        }
        val packageSegments = segments.dropLast(1).map(::encodePathSegment)
        val fileName = "${encodePathSegment(segments.last())}.ir"
        return packageSegments.fold(root) { path, segment -> path.resolve(segment) }.resolve(fileName).normalize()
    }

    fun rootLocation(): String = root.toString()

    fun classLocation(ownerInternalName: String): String = classFile(ownerInternalName).toString()

    fun methodLocation(ownerInternalName: String, methodName: String, descriptor: String): String =
        "${classLocation(ownerInternalName)} :: $methodName$descriptor"

    fun consumerLocation(consumer: String): String? {
        val separator = consumer.lastIndexOf('.')
        if (separator <= 0 || separator == consumer.lastIndex) return null
        val owner = consumer.substring(0, separator)
        val method = consumer.substring(separator + 1)
        if ('/' !in owner || '(' !in method) return null
        return "${classLocation(owner)} :: $method"
    }

    private fun encodePathSegment(segment: String): String {
        val bytes = segment.toByteArray(StandardCharsets.UTF_8)
        val encoded = buildString {
            for (byte in bytes) {
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

    private fun Char.isAsciiFileNameCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-' || this == '$'

    private companion object {
        const val HEX = "0123456789ABCDEF"
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { number ->
                add("COM$number")
                add("LPT$number")
            }
        }
    }
}
