package io.github.relvl.deobscura.config

import java.nio.file.Files
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigResolverTest {
    @Test
    fun `resolves paths against working directory and expands classpath globs`() {
        val workingDirectory = Files.createTempDirectory("deobscura-resolver-test")
        createJar(workingDirectory.resolve("input.jar"))
        val lib = Files.createDirectories(workingDirectory.resolve("lib"))
        createJar(lib.resolve("b.jar"))
        createJar(lib.resolve("a.jar"))
        Files.writeString(lib.resolve("not-a-jar.txt"), "ignored")

        val resolution = ConfigResolver(workingDirectory).resolve(
            DeobscuraConfig(
                input = "input.jar",
                classpath = listOf("lib/*.jar", "missing.jar"),
                output = "result",
            ),
        )

        assertEquals(workingDirectory.resolve("input.jar"), resolution.config.input)
        assertEquals(
            listOf(lib.resolve("a.jar"), lib.resolve("b.jar")),
            resolution.config.classpath,
        )
        assertEquals(workingDirectory.resolve("result"), resolution.config.output)
        assertTrue(resolution.warnings.any { "missing.jar" in it })
    }

    @Test
    fun `missing input jar is fatal`() {
        val workingDirectory = Files.createTempDirectory("deobscura-resolver-test")

        assertFailsWith<ConfigException> {
            ConfigResolver(workingDirectory).resolve(DeobscuraConfig(input = "missing.jar"))
        }
    }

    private fun createJar(path: java.nio.file.Path) {
        JarOutputStream(Files.newOutputStream(path)).use { }
    }
}
