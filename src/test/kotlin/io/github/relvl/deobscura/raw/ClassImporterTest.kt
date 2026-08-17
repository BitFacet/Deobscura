package io.github.relvl.deobscura.raw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassImporterTest {
    private val importer = ClassImporter()

    @Test
    fun `imports class metadata methods and instructions`() {
        val type = ImportFixture::class.java
        val internalName = type.name.replace('.', '/')
        val bytes = requireNotNull(type.getResourceAsStream("/$internalName.class")).use { it.readAllBytes() }

        val imported = importer.importClass(bytes)

        assertEquals(internalName, imported.internalName)
        assertTrue(imported.superName != null)
        assertTrue(imported.fields.any { it.name == "value" && it.type == JvmType.IntType })

        val method = imported.methods.single { it.name == "transform" }
        val code = assertNotNull(method.code)
        assertTrue(code.instructions.isNotEmpty())
        assertTrue(code.instructions.none { it is RawUnknownInstruction })
        assertTrue(code.instructions.any { it is RawBranchInstruction })
        assertTrue(code.instructions.any { it is RawReturnInstruction })
    }

    @Test
    fun `imports reference array whose component is itself an array`() {
        val type = ImportFixture::class.java
        val internalName = type.name.replace('.', '/')
        val bytes = requireNotNull(type.getResourceAsStream("/$internalName.class")).use { it.readAllBytes() }

        val imported = importer.importClass(bytes)
        val method = imported.methods.single { it.name == "createIntArrays" }
        val code = assertNotNull(method.code)
        val newArray = code.instructions.filterIsInstance<RawNewArrayInstruction>().single()

        assertEquals(JvmType.ArrayType(JvmType.IntType), newArray.componentType)
    }
}

private class ImportFixture(
    private val value: Int,
) {
    fun transform(input: Int): Int = if (input > 0) input + value else -input

    fun createIntArrays(size: Int): Array<IntArray?> = arrayOfNulls(size)
}
