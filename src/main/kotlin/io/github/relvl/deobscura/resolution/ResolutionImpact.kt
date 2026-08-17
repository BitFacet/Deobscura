package io.github.relvl.deobscura.resolution

enum class ResolutionPurpose {
    SUPERCLASS_HIERARCHY,
    INTERFACE_HIERARCHY,
    METHOD_LOOKUP,
    FIELD_LOOKUP,
    ASSIGNABILITY,
    COMMON_SUPERTYPE,
    OVERRIDE_ANALYSIS,
    VIRTUAL_DISPATCH,
    ANNOTATION_TYPE,
    GENERIC_SIGNATURE,
}

enum class ResolutionImpact(val priority: Int) {
    PRECISION_LOSS(1),
    ANALYSIS_SKIPPED(2),
    ANALYSIS_FAILED(3),
}

data class UnresolvedAnalysisUse(
    val internalName: String,
    val strongestImpact: ResolutionImpact,
    val requests: List<ResolutionRequest>,
)

data class ResolutionRequest(
    val purpose: ResolutionPurpose,
    val consumer: String,
    val impact: ResolutionImpact,
)

internal class ResolutionImpactTracker {
    private val unresolvedUses = linkedMapOf<String, MutableSet<ResolutionRequest>>()

    fun record(
        internalName: String,
        purpose: ResolutionPurpose,
        consumer: String,
        impact: ResolutionImpact,
    ) {
        unresolvedUses
            .getOrPut(internalName) { linkedSetOf() }
            .add(ResolutionRequest(purpose, consumer, impact))
    }

    fun snapshot(): List<UnresolvedAnalysisUse> = unresolvedUses
        .toSortedMap()
        .map { (internalName, requests) ->
            UnresolvedAnalysisUse(
                internalName = internalName,
                strongestImpact = requests.maxBy { it.impact.priority }.impact,
                requests = requests.toList(),
            )
        }
}
