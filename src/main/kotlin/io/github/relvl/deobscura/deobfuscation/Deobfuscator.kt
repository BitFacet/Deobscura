package io.github.relvl.deobscura.deobfuscation

import io.github.relvl.deobscura.raw.RawImportResult
import io.github.relvl.deobscura.resolution.MethodOverrideAnalysis

/** Builds source-facing deobfuscation facts without mutating the canonical imported JVM model. */
class Deobfuscator {
    fun analyze(
        rawImport: RawImportResult,
        enabled: Boolean,
        methodOverrides: MethodOverrideAnalysis = MethodOverrideAnalysis.EMPTY,
    ): DeobfuscationPlan =
        DeobfuscationPlan.build(rawImport.classes.values, enabled, methodOverrides)
}
