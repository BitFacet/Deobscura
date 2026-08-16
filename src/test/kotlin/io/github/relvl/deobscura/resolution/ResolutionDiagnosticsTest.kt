package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.ClassOrigin
import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole
import io.github.relvl.deobscura.jar.LoadedClass
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolutionDiagnosticsTest {
    @Test
    fun `reports unresolved classes with input referrers`() {
        val loadedClass = loadFixtureClass(DiagnosticsFixture::class.java)
        val jarResult = JarLoadResult(
            classes = mapOf(loadedClass.internalName to loadedClass),
            inputClassCount = 1,
            classpathClassCount = 0,
            classpathOnlyClassCount = 0,
            shadowedClasspathClassCount = 0,
            warnings = emptyList(),
        )

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val diagnostics = ResolutionDiagnostics().inspect(
                jarLoadResult = jarResult,
                resolver = ClassResolver(jarResult, runtimeSource),
            )

            val missingName = MissingFixtureDependency::class.java.name.replace('.', '/')
            val unresolved = diagnostics.unresolved.single { it.internalName == missingName }
            assertEquals(ReferenceKind.SIGNATURE, unresolved.kind)
            assertEquals(listOf(loadedClass.internalName), unresolved.referrers)
            assertEquals(1, diagnostics.count(ReferenceKind.SIGNATURE))
            assertTrue(diagnostics.warnings.isEmpty())
        }
    }

    private fun loadFixtureClass(type: Class<*>): LoadedClass {
        val internalName = type.name.replace('.', '/')
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
}

private class DiagnosticsFixture(
    val dependency: MissingFixtureDependency,
)

private class MissingFixtureDependency
