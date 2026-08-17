package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole
import java.nio.file.Path

class ClassResolver(
    jarLoadResult: JarLoadResult,
    private val runtimeSource: RuntimeClassSource,
) {
    private val jarClasses = jarLoadResult.classes
    private val runtimeClasses = mutableMapOf<String, ResolvedClass>()
    private val missingClasses = mutableSetOf<String>()
    private val impactTracker = ResolutionImpactTracker()

    fun findClass(internalName: String): ResolvedClass? = resolveClass(internalName)

    fun findClassForAnalysis(
        internalName: String,
        purpose: ResolutionPurpose,
        consumer: String,
        impactIfMissing: ResolutionImpact = ResolutionImpact.PRECISION_LOSS,
    ): ResolvedClass? {
        val resolved = resolveClass(internalName)
        if (resolved == null) {
            impactTracker.record(internalName, purpose, consumer, impactIfMissing)
        }
        return resolved
    }

    val unresolvedAnalysisUses: List<UnresolvedAnalysisUse>
        get() = impactTracker.snapshot()

    @Synchronized
    private fun resolveClass(internalName: String): ResolvedClass? {
        jarClasses[internalName]?.let { loadedClass ->
            return ResolvedClass(
                internalName = internalName,
                bytes = loadedClass.bytes,
                origin = when (loadedClass.origin.role) {
                    JarRole.INPUT -> ClassOrigin.Input(
                        jar = loadedClass.origin.jar,
                        entry = loadedClass.origin.entry,
                    )

                    JarRole.CLASSPATH -> ClassOrigin.Classpath(
                        jar = loadedClass.origin.jar,
                        entry = loadedClass.origin.entry,
                    )
                },
            )
        }

        runtimeClasses[internalName]?.let { return it }
        if (internalName in missingClasses) return null

        val runtimeClass = runtimeSource.findClass(internalName) ?: run {
            missingClasses += internalName
            return null
        }
        return ResolvedClass(
            internalName = internalName,
            bytes = runtimeClass.bytes,
            origin = ClassOrigin.Runtime(runtimeClass.module),
        ).also { runtimeClasses[internalName] = it }
    }

    val resolvedRuntimeClassCount: Int
        @Synchronized get() = runtimeClasses.size
}

data class ResolvedClass(
    val internalName: String,
    val bytes: ByteArray,
    val origin: ClassOrigin,
)

sealed interface ClassOrigin {
    data class Input(
        val jar: Path,
        val entry: String,
    ) : ClassOrigin

    data class Classpath(
        val jar: Path,
        val entry: String,
    ) : ClassOrigin

    data class Runtime(
        val module: String,
    ) : ClassOrigin
}
