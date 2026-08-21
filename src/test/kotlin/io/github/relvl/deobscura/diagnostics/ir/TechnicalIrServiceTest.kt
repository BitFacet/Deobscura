package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.raw.RawClass
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.*

class TechnicalIrServiceTest {
    @AfterTest
    fun resetService() {
        TechnicalIrService.reset()
    }

    @Test
    fun `configure preserves shared output contents and writeAll writes manifest`() {
        val parent = Files.createTempDirectory("deobscura-ir-test")
        val root = Files.createDirectories(parent.resolve("out"))
        val staleDirectory = Files.createDirectories(root.resolve("old/nested"))
        val staleFile = staleDirectory.resolve("stale.ir")
        Files.writeString(staleFile, "old run")
        val input = parent.resolve("input.jar")

        TechnicalIrService.configure(root, input)

        assertTrue(staleFile.exists())
        assertTrue(TechnicalIrService.enabled)
        TechnicalIrService.writeAll()

        val manifest = root.resolve(TechnicalIrService.MANIFEST_FILE)
        assertTrue(manifest.exists())
        assertContains(manifest.readText(), "format-version: ${TechnicalIrService.FORMAT_VERSION}")
        assertContains(manifest.readText(), "input: $input")
    }

    @Test
    fun `disabled service does not expose locator or create output`() {
        val input = Files.createTempFile("deobscura-ir-test", ".jar")

        TechnicalIrService.configure(null, input)

        assertFalse(TechnicalIrService.enabled)
        assertNull(TechnicalIrService.locator)
        TechnicalIrService.writeAll()
    }

    @Test
    fun `malformed UTF-16 code units are escaped while valid surrogate pairs are preserved`() {
        val smile = "\uD83D\uDE00"
        val malformed = "before\uD800middle\uDC00after $smile"

        val escaped = malformed.escapeMalformedUtf16()

        assertContains(escaped, "before\\uD800middle\\uDC00after")
        assertContains(escaped, smile)
    }

    @Test
    fun `locator maps internal names to class files and method locations`() {
        val root = Files.createTempDirectory("deobscura-ir-test")
        val locator = TechnicalIrLocator(root)

        val classFile = locator.classFile("org/example/Foo\$Bar")

        assertTrue(classFile.endsWith(Path.of("org", "example", "Foo\$Bar.ir")))
        assertContains(
            locator.methodLocation("org/example/Foo\$Bar", "run", "(I)V"),
            "Foo\$Bar.ir :: run(I)V",
        )
        assertContains(
            requireNotNull(locator.consumerLocation("org/example/Foo.run(I)V")),
            "Foo.ir :: run(I)V",
        )
        assertTrue(locator.classFile("odd/CON").endsWith(Path.of("odd", "%43ON.ir")))
        assertTrue(locator.classFile("odd/a:b").endsWith(Path.of("odd", "a%3Ab.ir")))
    }

    @Test
    fun `deobfuscation maps IR path beside source path`() {
        val root = Files.createTempDirectory("deobscura-ir-deobfuscation-test")
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
        TechnicalIrService.configure(root, root.resolveSibling("input.jar"))
        TechnicalIrService.setDeobfuscation(DeobfuscationPlan.build(listOf(rawClass), enabled = true))

        assertTrue(requireNotNull(TechnicalIrService.locator).classFile("class/Sample").endsWith(Path.of("package_class", "Sample.ir")))
    }

}
