package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole

class ResolutionDiagnostics(
    private val scanner: ClassReferenceScanner = ClassReferenceScanner(),
) {
    fun inspect(
        jarLoadResult: JarLoadResult,
        resolver: ClassResolver,
    ): ResolutionDiagnosticsResult {
        val unresolvedReferences = linkedMapOf<String, MutableUnresolvedReference>()
        val warnings = mutableListOf<String>()

        jarLoadResult.classes.values
            .asSequence()
            .filter { it.origin.role == JarRole.INPUT }
            .forEach { loadedClass ->
                val references = try {
                    scanner.scan(loadedClass)
                } catch (exception: Exception) {
                    warnings += "Failed to inspect class '${loadedClass.internalName}' for references: ${exception.message}."
                    return@forEach
                }

                for (reference in references) {
                    if (resolver.findClass(reference.internalName) == null) {
                        unresolvedReferences
                            .getOrPut(reference.internalName) { MutableUnresolvedReference() }
                            .add(loadedClass.internalName, reference.kind)
                    }
                }
            }

        val unresolved = unresolvedReferences
            .toSortedMap()
            .map { (internalName, reference) ->
                UnresolvedClassReference(
                    internalName = internalName,
                    kind = reference.strongestKind,
                    referrers = reference.referrers.sorted(),
                )
            }

        return ResolutionDiagnosticsResult(
            unresolved = unresolved,
            warnings = warnings,
        )
    }

    private class MutableUnresolvedReference {
        val referrers = linkedSetOf<String>()
        var strongestKind: ReferenceKind = ReferenceKind.CONSTANT_POOL
            private set

        fun add(referrer: String, kind: ReferenceKind) {
            referrers += referrer
            if (kind.priority > strongestKind.priority) {
                strongestKind = kind
            }
        }
    }
}

data class ResolutionDiagnosticsResult(
    val unresolved: List<UnresolvedClassReference>,
    val warnings: List<String>,
) {
    fun count(kind: ReferenceKind): Int = unresolved.count { it.kind == kind }
}

data class UnresolvedClassReference(
    val internalName: String,
    val kind: ReferenceKind,
    val referrers: List<String>,
)
