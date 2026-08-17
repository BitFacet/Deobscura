package io.github.relvl.deobscura.config

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfigRepositoryTest {
    @Test
    fun `creates documented default config when file is missing`() {
        val directory = Files.createTempDirectory("deobscura-config-test")
        val path = directory.resolve("default.jsonc")

        val result = ConfigRepository().loadOrCreate(path)

        assertIs<ConfigLoadResult.Created>(result)
        val json = path.readText()
        assertContains(json, "// JAR file to analyze.")
        assertContains(json, "\"input\"")
        assertContains(json, "\"input.jar\"")
        assertContains(json, "// Additional JAR files used for class resolution. Glob patterns are supported.")
        assertContains(json, "\"classpath\"")
        assertContains(json, "// Target Java runtime directory. null uses the runtime of the current JVM.")
        assertContains(json, "\"runtime\"")
        assertContains(json, "null")
        assertContains(json, "// Output directory.")
        assertContains(json, "\"output\"")
        assertContains(json, "\"out\"")
        assertContains(json, "// Relative subdirectory under output for technical IR dumps. null disables technical IR output.")
        assertContains(json, "\"technicalIr\"")
    }

    @Test
    fun `loads JSONC with comments and trailing commas`() {
        val directory = Files.createTempDirectory("deobscura-config-test")
        val path = directory.resolve("wurm.jsonc")
        Files.writeString(
            path,
            """
            {
              // Main client.
              "input": "WurmLauncher.jar",
              "classpath": [
                "lib/*.jar",
              ],
              /* Bundled runtime. */
              "runtime": "runtime",
              "output": "deobfuscated",
              "technicalIr": "technical-ir",
            }
            """.trimIndent(),
        )

        val result = ConfigRepository().loadOrCreate(path)

        val loaded = assertIs<ConfigLoadResult.Loaded>(result)
        assertEquals("WurmLauncher.jar", loaded.config.input)
        assertEquals(listOf("lib/*.jar"), loaded.config.classpath)
        assertEquals("runtime", loaded.config.runtime)
        assertEquals("deobfuscated", loaded.config.output)
        assertEquals("technical-ir", loaded.config.technicalIr)
    }

    @Test
    fun `rewrites loaded config with generated comments and preserves values`() {
        val directory = Files.createTempDirectory("deobscura-config-test")
        val path = directory.resolve("wurm.jsonc")
        val repository = ConfigRepository()
        Files.writeString(
            path,
            """
            {
              "input": "WurmLauncher.jar",
              "classpath": ["lib/*.jar"],
              "runtime": "runtime",
              "output": "deobfuscated"
            }
            """.trimIndent(),
        )

        val loaded = assertIs<ConfigLoadResult.Loaded>(repository.loadOrCreate(path))
        repository.write(path, loaded.config)

        val rewritten = path.readText()
        assertContains(rewritten, "// JAR file to analyze.")
        assertContains(rewritten, "\"WurmLauncher.jar\"")
        assertContains(rewritten, "\"lib/*.jar\"")
        assertContains(rewritten, "\"runtime\"")
        assertContains(rewritten, "\"deobfuscated\"")

        val reloaded = assertIs<ConfigLoadResult.Loaded>(repository.loadOrCreate(path))
        assertEquals(loaded.config, reloaded.config)
    }
}
