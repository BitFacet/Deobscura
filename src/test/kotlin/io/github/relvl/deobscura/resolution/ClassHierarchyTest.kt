package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassHierarchyTest {
    @Test
    fun `resolves assignability through classes and interfaces`() = withHierarchy { hierarchy, _ ->
        assertTrue(hierarchy.isAssignable(exact("java/lang/Object"), exact("java/lang/String"), "test") == true)
        assertTrue(hierarchy.isAssignable(exact("java/io/Serializable"), exact("java/lang/String"), "test") == true)
        assertFalse(hierarchy.isAssignable(exact("java/lang/String"), exact("java/lang/Object"), "test") == true)
        assertNull(hierarchy.isAssignable(JvmReferenceType.Unknown, exact("java/lang/String"), "test"))
    }

    @Test
    fun `finds common superclass for objects and arrays`() = withHierarchy { hierarchy, _ ->
        assertEquals(
            exact("java/lang/Object"),
            hierarchy.commonSupertype(exact("java/lang/String"), exact("java/lang/StringBuilder"), "test"),
        )
        assertEquals(
            JvmReferenceType.Exact(JvmType.ArrayType(JvmType.ObjectType("java/lang/Object"))),
            hierarchy.commonSupertype(
                JvmReferenceType.Exact(JvmType.ArrayType(JvmType.ObjectType("java/lang/String"))),
                JvmReferenceType.Exact(JvmType.ArrayType(JvmType.ObjectType("java/lang/StringBuilder"))),
                "test",
            ),
        )
    }

    @Test
    fun `null merges into the other reference type`() = withHierarchy { hierarchy, _ ->
        assertEquals(
            exact("java/lang/String"),
            hierarchy.commonSupertype(JvmReferenceType.Null, exact("java/lang/String"), "test"),
        )
    }

    @Test
    fun `missing hierarchy information degrades to unknown and records impact`() = withHierarchy { hierarchy, resolver ->
        assertEquals(
            JvmReferenceType.Unknown,
            hierarchy.commonSupertype(exact("missing/example/Type"), exact("java/lang/String"), "test/Owner.method()V"),
        )
        val impact = resolver.unresolvedAnalysisUses.single { it.internalName == "missing/example/Type" }
        assertEquals(ResolutionImpact.PRECISION_LOSS, impact.strongestImpact)
        assertTrue(impact.requests.any { it.purpose == ResolutionPurpose.COMMON_SUPERTYPE })
    }

    private fun withHierarchy(block: (ClassHierarchy, ClassResolver) -> Unit) {
        RuntimeClassSource(Path.of(System.getProperty("java.home"))).use { runtime ->
            val resolver = ClassResolver(emptyJarResult(), runtime)
            block(ClassHierarchy(resolver), resolver)
        }
    }

    private fun exact(internalName: String): JvmReferenceType =
        JvmReferenceType.Exact(JvmType.ObjectType(internalName))

    private fun emptyJarResult() = JarLoadResult(
        classes = emptyMap(),
        inputClassCount = 0,
        classpathClassCount = 0,
        classpathOnlyClassCount = 0,
        shadowedClasspathClassCount = 0,
        warnings = emptyList(),
    )
}
