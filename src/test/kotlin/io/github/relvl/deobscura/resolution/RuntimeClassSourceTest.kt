package io.github.relvl.deobscura.resolution

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeClassSourceTest {
    @Test
    fun `resolves a class from current runtime lazily`() {
        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { source ->
            val resolved = assertNotNull(source.findClass("java/lang/String"))

            assertTrue(resolved.bytes.isNotEmpty())
            assertTrue(resolved.module.isNotBlank())
            assertNull(source.findClass("not/a/real/Class"))
        }
    }
}
