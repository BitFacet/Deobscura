package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ResolutionDiagnostics(
    private val scanner: ClassReferenceScanner = ClassReferenceScanner(),
    private val logger: Logger = LoggerFactory.getLogger(ResolutionDiagnostics::class.java),
) {
    fun inspect(
        jarLoadResult: JarLoadResult,
        resolver: ClassResolver,
    ): ResolutionDiagnosticsResult {
        val unresolvedReferences = linkedMapOf<String, MutableUnresolvedReference>()
        val warnings = mutableListOf<String>()

        jarLoadResult.classes.values.asSequence().filter { it.origin.role == JarRole.INPUT }.forEach { loadedClass ->
                val references = try {
                    scanner.scan(loadedClass)
                } catch (exception: Exception) {
                    warnings += "Failed to inspect class '${loadedClass.internalName}' for references: ${exception.message}."
                    return@forEach
                }

                for (reference in references) {
                    if (resolver.findClass(reference.internalName) == null) {
                        unresolvedReferences.getOrPut(reference.internalName) { MutableUnresolvedReference() }.add(loadedClass.internalName, reference.kind)
                    }
                }
            }

        val unresolved = unresolvedReferences.toSortedMap().map { (internalName, reference) ->
                UnresolvedClassReference(
                    internalName = internalName,
                    kind = reference.strongestKind,
                    referrers = reference.referrers.sorted(),
                )
            }

        return ResolutionDiagnosticsResult(
            unresolved = unresolved,
            warnings = warnings,
        ).also { result -> logResolutionResult(result, resolver) }
    }

    fun logAnalysisImpact(
        resolver: ClassResolver,
        diagnostics: ResolutionDiagnosticsResult,
    ) {
        val unresolvedAnalysisUses = resolver.unresolvedAnalysisUses
        unresolvedAnalysisUses.forEach { use ->
            val discovered = diagnostics.unresolved.firstOrNull { it.internalName == use.internalName }
            val locations = TechnicalIrService.consumerLocations(use.requests.map { it.consumer })
            val ir = if (locations.isEmpty()) "" else " Technical IR: ${locations.joinToString()}."
            logger.warn(
                "Unresolved class '{}'{} affected analysis [{}]: {}.{}",
                use.internalName,
                discovered?.let { " [${it.kind}]" } ?: "",
                use.strongestImpact,
                formatAnalysisRequests(use.requests),
                ir,
            )
        }

        val affectedNames = unresolvedAnalysisUses.asSequence().map { it.internalName }.toSet()
        val unaffectedCount = diagnostics.unresolved.count { it.internalName !in affectedNames }
        logger.info(
            "Unresolved class impact: {} referenced, {} affected performed analyses, {} did not affect performed analyses.",
            diagnostics.unresolved.size,
            unresolvedAnalysisUses.size,
            unaffectedCount,
        )
    }

    private fun logResolutionResult(result: ResolutionDiagnosticsResult, resolver: ClassResolver) {
        val ir = TechnicalIrService.rootHint()
        result.warnings.forEach { logger.warn("{}{}", it, ir) }
        logger.info(
            "Resolved {} referenced classes from the runtime; {} class(es) remain unresolved.",
            resolver.resolvedRuntimeClassCount,
            result.unresolved.size,
        )
        if (result.unresolved.isNotEmpty()) {
            logger.info(
                "Unresolved classes by reference kind: {} structural, {} signature, {} constant-pool.",
                result.count(ReferenceKind.STRUCTURAL),
                result.count(ReferenceKind.SIGNATURE),
                result.count(ReferenceKind.CONSTANT_POOL),
            )
        }
    }

    private fun formatAnalysisRequests(requests: List<ResolutionRequest>): String {
        val distinctRequests = requests.distinct()
        val shown = distinctRequests.take(MAX_ANALYSIS_REQUESTS_IN_WARNING)
        return buildString {
            append(shown.joinToString { "${it.purpose} for ${it.consumer}" })
            if (distinctRequests.size > shown.size) append(" (+${distinctRequests.size - shown.size} more)")
        }
    }

    private companion object {
        const val MAX_ANALYSIS_REQUESTS_IN_WARNING = 5
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
