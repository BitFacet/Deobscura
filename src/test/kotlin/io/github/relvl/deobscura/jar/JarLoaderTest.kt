package io.github.relvl.deobscura.jar

import io.github.relvl.deobscura.config.ResolvedConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.*

class JarLoaderTest {
    @Test
    fun `input classes take priority over classpath classes`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")
        val dependency = directory.resolve("dependency.jar")

        writeJar(
            input,
            mapOf(
                "sample/Input.class" to byteArrayOf(1),
                "sample/Duplicate.class" to byteArrayOf(2),
            ),
        )
        writeJar(
            dependency,
            mapOf(
                "sample/Dependency.class" to byteArrayOf(3),
                "sample/Duplicate.class" to byteArrayOf(4),
            ),
        )

        val result = JarLoader().load(config(directory, input, listOf(dependency)))

        assertEquals(2, result.inputClassCount)
        assertEquals(2, result.classpathClassCount)
        assertEquals(1, result.classpathOnlyClassCount)
        assertEquals(1, result.shadowedClasspathClassCount)
        assertEquals(3, result.classes.size)
        assertContentEquals(byteArrayOf(2), result.classes.getValue("sample/Duplicate").bytes)
        assertEquals(JarRole.INPUT, result.classes.getValue("sample/Duplicate").origin.role)
        assertEquals(1, result.warnings.count { "already present in the input JAR" in it })
    }

    @Test
    fun `later classpath jar overrides earlier classpath jar`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")
        val firstDependency = directory.resolve("first.jar")
        val secondDependency = directory.resolve("second.jar")

        writeJar(input, mapOf("sample/Input.class" to byteArrayOf(1)))
        writeJar(firstDependency, mapOf("sample/Duplicate.class" to byteArrayOf(2)))
        writeJar(secondDependency, mapOf("sample/Duplicate.class" to byteArrayOf(3)))

        val result = JarLoader().load(config(directory, input, listOf(firstDependency, secondDependency)))

        assertEquals(2, result.classpathClassCount)
        assertEquals(1, result.classpathOnlyClassCount)
        assertEquals(0, result.shadowedClasspathClassCount)
        assertContentEquals(byteArrayOf(3), result.classes.getValue("sample/Duplicate").bytes)
        assertEquals(secondDependency, result.classes.getValue("sample/Duplicate").origin.jar)
        assertTrue(result.warnings.any { "Duplicate classpath class 'sample/Duplicate'" in it })
    }

    @Test
    fun `aggregates input shadowing warning per classpath jar`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")
        val dependency = directory.resolve("dependency.jar")

        writeJar(
            input,
            mapOf(
                "sample/First.class" to byteArrayOf(1),
                "sample/Second.class" to byteArrayOf(2),
            ),
        )
        writeJar(
            dependency,
            mapOf(
                "sample/First.class" to byteArrayOf(3),
                "sample/Second.class" to byteArrayOf(4),
            ),
        )

        val result = JarLoader().load(config(directory, input, listOf(dependency)))

        assertEquals(2, result.shadowedClasspathClassCount)
        assertEquals(1, result.warnings.size)
        assertTrue("contains 2 class(es) already present in the input JAR" in result.warnings.single())
    }

    @Test
    fun `ignores versioned entries when jar is not marked multi release`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")

        writeJar(
            input,
            mapOf(
                "sample/Example.class" to byteArrayOf(1),
                "META-INF/versions/9/sample/Example.class" to byteArrayOf(9),
                "META-INF/versions/9/sample/VersionOnly.class" to byteArrayOf(10),
            ),
        )

        val result = JarLoader().load(config(directory, input))

        assertEquals(1, result.inputClassCount)
        assertEquals(setOf("sample/Example"), result.classes.keys)
        assertContentEquals(byteArrayOf(1), result.classes.getValue("sample/Example").bytes)
        assertFalse(result.warnings.any { "Duplicate class" in it })
    }

    @Test
    fun `uses effective class version when jar is marked multi release`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")

        writeJar(
            input,
            mapOf(
                "sample/Example.class" to byteArrayOf(1),
                "META-INF/versions/9/sample/Example.class" to byteArrayOf(9),
                "META-INF/versions/16/sample/Example.class" to byteArrayOf(16),
                "META-INF/versions/22/sample/Example.class" to byteArrayOf(22),
            ),
            multiRelease = true,
        )

        val result = JarLoader().load(
            config(
                directory = directory,
                input = input,
                runtimeVersion = Runtime.Version.parse("21"),
            ),
        )

        assertEquals(1, result.inputClassCount)
        assertEquals(setOf("sample/Example"), result.classes.keys)
        assertContentEquals(byteArrayOf(16), result.classes.getValue("sample/Example").bytes)
        assertEquals("META-INF/versions/16/sample/Example.class", result.classes.getValue("sample/Example").origin.entry)
        assertFalse(result.warnings.any { "Duplicate class" in it })
    }

    @Test
    fun `broken classpath jar is warned and skipped`() {
        val directory = Files.createTempDirectory("deobscura-jar-test")
        val input = directory.resolve("input.jar")
        val brokenDependency = directory.resolve("broken.jar")
        writeJar(input, mapOf("sample/Input.class" to byteArrayOf(1)))
        Files.writeString(brokenDependency, "not a jar")

        val result = JarLoader().load(config(directory, input, listOf(brokenDependency)))

        assertEquals(1, result.classes.size)
        assertTrue(result.warnings.any { "broken.jar" in it })
    }

    private fun config(
        directory: Path,
        input: Path,
        classpath: List<Path> = emptyList(),
        runtimeVersion: Runtime.Version = Runtime.version(),
    ) = ResolvedConfig(
        input = input,
        classpath = classpath,
        runtime = Path.of(System.getProperty("java.home")),
        runtimeVersion = runtimeVersion,
        output = directory.resolve("out"),
    )

    private fun writeJar(
        path: Path,
        entries: Map<String, ByteArray>,
        multiRelease: Boolean = false,
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) {
                mainAttributes.putValue("Multi-Release", "true")
            }
        }

        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            for ((name, bytes) in entries) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}
