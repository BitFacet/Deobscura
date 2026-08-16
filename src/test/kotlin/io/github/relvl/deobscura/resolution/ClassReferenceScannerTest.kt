package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.ClassOrigin
import io.github.relvl.deobscura.jar.JarRole
import io.github.relvl.deobscura.jar.LoadedClass
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ClassReferenceScannerTest {
    private val scanner = ClassReferenceScanner()

    @Test
    fun `classifies superclass and interfaces as structural references`() {
        val references = scanner.scan(loadFixtureClass(StructuralFixture::class.java))

        assertTrue(references.contains(ClassReference(internalName(StructuralBase::class.java), ReferenceKind.STRUCTURAL)))
        assertTrue(references.contains(ClassReference(internalName(StructuralInterface::class.java), ReferenceKind.STRUCTURAL)))
    }

    @Test
    fun `classifies field method and declared exception types as signature references`() {
        val references = scanner.scan(loadFixtureClass(SignatureFixture::class.java))

        assertTrue(references.contains(ClassReference(internalName(SignatureDependency::class.java), ReferenceKind.SIGNATURE)))
        assertTrue(references.contains(ClassReference(internalName(SignatureException::class.java), ReferenceKind.SIGNATURE)))
    }

    private fun loadFixtureClass(type: Class<*>): LoadedClass {
        val internalName = internalName(type)
        val resourceName = "/$internalName.class"
        val bytes = requireNotNull(type.getResourceAsStream(resourceName)).use { it.readAllBytes() }
        return LoadedClass(
            internalName = internalName,
            bytes = bytes,
            origin = ClassOrigin(
                jar = Path.of("fixture.jar"),
                entry = resourceName.removePrefix("/"),
                role = JarRole.INPUT,
            ),
        )
    }

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')
}

private open class StructuralBase
private interface StructuralInterface
private class StructuralFixture : StructuralBase(), StructuralInterface

private class SignatureDependency
private class SignatureException : Exception()
private class SignatureFixture(
    val dependency: SignatureDependency,
) {
    @Throws(SignatureException::class)
    fun use(value: SignatureDependency): SignatureDependency = value
}
