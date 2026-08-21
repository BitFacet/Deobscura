package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.ClassOrigin
import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole
import io.github.relvl.deobscura.jar.LoadedClass
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClassResolverTest {
    @Test
    fun `application class takes priority over runtime class`() {
        val applicationBytes = byteArrayOf(1, 2, 3)
        val loadedClass = LoadedClass(
            internalName = "java/lang/String",
            bytes = applicationBytes,
            origin = ClassOrigin(
                jar = Path.of("input.jar"),
                entry = "java/lang/String.class",
                role = JarRole.INPUT,
            ),
        )
        val jarResult = JarLoadResult(
            classes = mapOf(loadedClass.internalName to loadedClass),
            inputClassCount = 1,
            classpathClassCount = 0,
            classpathOnlyClassCount = 0,
            shadowedClasspathClassCount = 0,
            warnings = emptyList(),
        )

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val resolved = ClassResolver(jarResult, runtimeSource).findClass("java/lang/String")!!

            assertContentEquals(applicationBytes, resolved.bytes)
            assertIs<ClassOrigin.Input>(resolved.origin)
        }
    }

    @Test
    fun `falls back to runtime when application class is absent`() {
        val jarResult = JarLoadResult(
            classes = emptyMap(),
            inputClassCount = 0,
            classpathClassCount = 0,
            classpathOnlyClassCount = 0,
            shadowedClasspathClassCount = 0,
            warnings = emptyList(),
        )

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val resolver = ClassResolver(jarResult, runtimeSource)
            val resolved = resolver.findClass("java/lang/String")!!

            assertIs<ClassOrigin.Runtime>(resolved.origin)
            assertEquals("java.base", resolved.origin.module)
            assertEquals(1, resolver.resolvedRuntimeClassCount)
        }
    }

    @Test
    fun `plain lookup does not mark a missing class as affecting analysis`() {
        val jarResult = emptyJarResult()

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val resolver = ClassResolver(jarResult, runtimeSource)

            assertEquals(null, resolver.findClass("not/a/real/Class"))
            assertEquals(emptyList(), resolver.unresolvedAnalysisUses)
        }
    }

    @Test
    fun `analysis lookup records why a missing class matters`() {
        val jarResult = emptyJarResult()

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val resolver = ClassResolver(jarResult, runtimeSource)

            assertEquals(
                null,
                resolver.findClassForAnalysis(
                    internalName = "not/a/real/Class",
                    purpose = ResolutionPurpose.OVERRIDE_ANALYSIS,
                    consumer = "example/Child.method()V",
                ),
            )

            val use = resolver.unresolvedAnalysisUses.single()
            assertEquals("not/a/real/Class", use.internalName)
            assertEquals(ResolutionImpact.PRECISION_LOSS, use.strongestImpact)
            assertEquals(ResolutionPurpose.OVERRIDE_ANALYSIS, use.requests.single().purpose)
            assertEquals("example/Child.method()V", use.requests.single().consumer)
        }
    }

    @Test
    fun `negative lookup cache still records every analysis use`() {
        val jarResult = emptyJarResult()

        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtimeSource ->
            val resolver = ClassResolver(jarResult, runtimeSource)

            repeat(2) { index ->
                assertEquals(
                    null,
                    resolver.findClassForAnalysis(
                        internalName = "not/a/real/Class",
                        purpose = ResolutionPurpose.COMMON_SUPERTYPE,
                        consumer = "consumer-$index",
                    ),
                )
            }

            val requests = resolver.unresolvedAnalysisUses.single().requests
            assertEquals(listOf("consumer-0", "consumer-1"), requests.map { it.consumer })
        }
    }

    private fun emptyJarResult() = JarLoadResult(
        classes = emptyMap(),
        inputClassCount = 0,
        classpathClassCount = 0,
        classpathOnlyClassCount = 0,
        shadowedClasspathClassCount = 0,
        warnings = emptyList(),
    )
}
