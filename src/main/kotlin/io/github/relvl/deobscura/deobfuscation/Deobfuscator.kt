package io.github.relvl.deobscura.deobfuscation

import io.github.relvl.deobscura.raw.RawImportResult

/** Builds source-facing deobfuscation facts without mutating the canonical imported JVM model. */
class Deobfuscator {
    fun analyze(rawImport: RawImportResult, enabled: Boolean): DeobfuscationPlan =
        DeobfuscationPlan.build(rawImport.classes.values, enabled)
}
