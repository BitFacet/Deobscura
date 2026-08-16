package io.github.relvl.deobscura.normalize

import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawImportResult
import io.github.relvl.deobscura.raw.RawRetInstruction

class LegacySubroutineDiagnostics(
    private val normalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun inspect(rawImport: RawImportResult): LegacySubroutineDiagnosticsResult {
        var methodCount = 0
        var jsrCallSiteCount = 0L
        var clonedBlockCount = 0L
        var normalizedInstructionCount = 0L
        val warnings = mutableListOf<String>()

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                val code = method.code ?: continue
                if (code.instructions.none { instruction ->
                        instruction is RawRetInstruction ||
                            instruction is RawBranchInstruction && instruction.opcode.mnemonic in LEGACY_JSR_OPCODES
                    }
                ) {
                    continue
                }

                methodCount++
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"
                try {
                    val result = normalizer.normalize(code)
                    jsrCallSiteCount += result.jsrCallSiteCount
                    clonedBlockCount += result.clonedBlockCount
                    normalizedInstructionCount += result.normalizedInstructionCount
                } catch (exception: Exception) {
                    warnings += "Failed to normalize legacy JSR/RET in '$methodName': ${exception.message}."
                }
            }
        }

        return LegacySubroutineDiagnosticsResult(
            methodCount = methodCount,
            jsrCallSiteCount = jsrCallSiteCount,
            clonedBlockCount = clonedBlockCount,
            normalizedInstructionCount = normalizedInstructionCount,
            failureCount = warnings.size,
            warnings = warnings,
        )
    }

    private companion object {
        val LEGACY_JSR_OPCODES = setOf("jsr", "jsr_w")
    }
}

data class LegacySubroutineDiagnosticsResult(
    val methodCount: Int,
    val jsrCallSiteCount: Long,
    val clonedBlockCount: Long,
    val normalizedInstructionCount: Long,
    val failureCount: Int,
    val warnings: List<String>,
)
