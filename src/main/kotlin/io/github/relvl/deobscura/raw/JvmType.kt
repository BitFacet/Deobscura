package io.github.relvl.deobscura.raw

sealed interface JvmType {
    val descriptor: String

    data object BooleanType : JvmType {
        override val descriptor = "Z"
    }

    data object ByteType : JvmType {
        override val descriptor = "B"
    }

    data object CharType : JvmType {
        override val descriptor = "C"
    }

    data object ShortType : JvmType {
        override val descriptor = "S"
    }

    data object IntType : JvmType {
        override val descriptor = "I"
    }

    data object LongType : JvmType {
        override val descriptor = "J"
    }

    data object FloatType : JvmType {
        override val descriptor = "F"
    }

    data object DoubleType : JvmType {
        override val descriptor = "D"
    }

    data object VoidType : JvmType {
        override val descriptor = "V"
    }

    data class ObjectType(val internalName: String) : JvmType {
        override val descriptor: String = "L$internalName;"
    }

    data class ArrayType(val componentType: JvmType) : JvmType {
        override val descriptor: String = "[${componentType.descriptor}"
    }

    companion object {
        fun parse(descriptor: String): JvmType {
            val cursor = DescriptorCursor(descriptor)
            val type = cursor.readType(allowVoid = true)
            require(cursor.atEnd()) { "Unexpected trailing descriptor data in '$descriptor'." }
            return type
        }
    }
}

data class JvmMethodDescriptor(
    val parameterTypes: List<JvmType>,
    val returnType: JvmType,
) {
    val descriptor: String = buildString {
        append('(')
        parameterTypes.forEach { append(it.descriptor) }
        append(')')
        append(returnType.descriptor)
    }

    companion object {
        fun parse(descriptor: String): JvmMethodDescriptor {
            val cursor = DescriptorCursor(descriptor)
            require(cursor.readChar() == '(') { "Invalid method descriptor '$descriptor'." }

            val parameters = mutableListOf<JvmType>()
            while (cursor.peekChar() != ')') {
                require(!cursor.atEnd()) { "Unterminated method descriptor '$descriptor'." }
                parameters += cursor.readType(allowVoid = false)
            }
            cursor.readChar()

            val returnType = cursor.readType(allowVoid = true)
            require(cursor.atEnd()) { "Unexpected trailing descriptor data in '$descriptor'." }
            return JvmMethodDescriptor(parameters, returnType)
        }
    }
}

private class DescriptorCursor(private val descriptor: String) {
    private var index = 0

    fun atEnd(): Boolean = index == descriptor.length

    fun peekChar(): Char? = descriptor.getOrNull(index)

    fun readChar(): Char = descriptor.getOrNull(index++)
        ?: throw IllegalArgumentException("Unexpected end of descriptor '$descriptor'.")

    fun readType(allowVoid: Boolean): JvmType {
        return when (val marker = readChar()) {
            'Z' -> JvmType.BooleanType
            'B' -> JvmType.ByteType
            'C' -> JvmType.CharType
            'S' -> JvmType.ShortType
            'I' -> JvmType.IntType
            'J' -> JvmType.LongType
            'F' -> JvmType.FloatType
            'D' -> JvmType.DoubleType
            'V' -> {
                require(allowVoid) { "Void is not valid here in descriptor '$descriptor'." }
                JvmType.VoidType
            }

            '[' -> JvmType.ArrayType(readType(allowVoid = false))
            'L' -> {
                val end = descriptor.indexOf(';', index)
                require(end >= index) { "Unterminated object type in descriptor '$descriptor'." }
                val internalName = descriptor.substring(index, end)
                require(internalName.isNotEmpty()) { "Empty object type in descriptor '$descriptor'." }
                index = end + 1
                JvmType.ObjectType(internalName)
            }

            else -> throw IllegalArgumentException("Unknown descriptor marker '$marker' in '$descriptor'.")
        }
    }
}

sealed interface JvmReferenceType {
    data object Unknown : JvmReferenceType
    data object Null : JvmReferenceType

    data class Exact(val type: JvmType) : JvmReferenceType {
        init {
            require(type is JvmType.ObjectType || type is JvmType.ArrayType) {
                "Exact reference type requires an object or array type, got $type."
            }
        }
    }
}

fun JvmType.toReferenceType(): JvmReferenceType = when (this) {
    is JvmType.ObjectType, is JvmType.ArrayType -> JvmReferenceType.Exact(this)
    else -> error("$this is not a JVM reference type.")
}
