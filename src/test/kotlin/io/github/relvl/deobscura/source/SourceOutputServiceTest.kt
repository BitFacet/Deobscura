package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.raw.RawClass
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SourceOutputServiceTest {
    @AfterTest
    fun resetService() {
        SourceOutputService.reset()
    }

    @Test
    fun `writes class skeleton directly under output package tree`() {
        val output = Files.createTempDirectory("deobscura-source-test")
        SourceOutputService.configure(output)

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

        val rendered = output.resolve("example/Empty.java")
        assertTrue(rendered.exists())
        assertContains(rendered.readText(), "public class Empty")
    }

    @Test
    fun `deobfuscation renames package directory and declaration`() {
        val output = Files.createTempDirectory("deobscura-source-deobfuscation-test")
        SourceOutputService.configure(output)
        val rawClass = RawClass(
            internalName = "class/Sample",
            majorVersion = 65,
            minorVersion = 0,
            accessFlags = 0x0001,
            superName = "java/lang/Object",
            interfaces = emptyList(),
            fields = emptyList(),
            methods = emptyList(),
        )
        SourceOutputService.setDeobfuscation(
            io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan.build(listOf(rawClass), enabled = true),
        )
        SourceOutputService.captureClass(rawClass)

        SourceOutputService.writeAll()

        val rendered = output.resolve("package_class/Sample.java")
        assertTrue(rendered.exists())
        assertContains(rendered.readText(), "package package_class;")
    }
}
