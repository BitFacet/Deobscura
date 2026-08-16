package io.github.relvl.deobscura.raw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmTypeTest {
    @Test
    fun `parses primitive object and array types`() {
        assertEquals(JvmType.IntType, JvmType.parse("I"))
        assertEquals(JvmType.ObjectType("java/lang/String"), JvmType.parse("Ljava/lang/String;"))
        assertEquals(
            JvmType.ArrayType(JvmType.ArrayType(JvmType.ObjectType("java/lang/String"))),
            JvmType.parse("[[Ljava/lang/String;"),
        )
    }

    @Test
    fun `parses method descriptor`() {
        assertEquals(
            JvmMethodDescriptor(
                parameterTypes = listOf(
                    JvmType.IntType,
                    JvmType.ArrayType(JvmType.ObjectType("java/lang/String")),
                ),
                returnType = JvmType.LongType,
            ),
            JvmMethodDescriptor.parse("(I[Ljava/lang/String;)J"),
        )
    }

    @Test
    fun `rejects void method parameter`() {
        assertFailsWith<IllegalArgumentException> {
            JvmMethodDescriptor.parse("(V)V")
        }
    }
}
