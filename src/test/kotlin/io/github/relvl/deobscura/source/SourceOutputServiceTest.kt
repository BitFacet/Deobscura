package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.raw.RawClass
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceOutputServiceTest {
    @AfterTest
    fun resetService() {
        SourceOutputService.reset()
    }

    @Test
    fun `configure clears only source subdirectory and writes class skeleton`() {
        val output = Files.createTempDirectory("deobscura-source-test")
        val source = Files.createDirectories(output.resolve(SourceOutputService.SOURCE_SUBDIRECTORY))
        val stale = source.resolve("stale.java")
        Files.writeString(stale, "stale")
        val sibling = output.resolve("technical-ir-marker.txt")
        Files.writeString(sibling, "keep")

        SourceOutputService.configure(output)

        assertFalse(stale.exists())
        assertTrue(sibling.exists())

        SourceOutputService.captureClass(
            RawClass(
                internalName = "example/Empty",
                majorVersion = 65,
                minorVersion = 0,
                accessFlags = 0x0001,
                superName = "java/lang/Object",
                interfaces = emptyList(),
                fields = emptyList(),
                methods = emptyList(),
            ),
        )
        SourceOutputService.writeAll()

        val rendered = output.resolve(SourceOutputService.SOURCE_SUBDIRECTORY).resolve("example/Empty.java")
        assertTrue(rendered.exists())
        assertContains(rendered.readText(), "public class Empty")
    }
}
