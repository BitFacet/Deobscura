package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawImportResult

class FrameDiagnostics(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val analyzer: FrameAnalyzer = FrameAnalyzer(),
    private val legacySubroutineNormalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun inspect(rawImport: RawImportResult): FrameDiagnosticsResult {
        var methodCount = 0
        var frameMergeCount = 0L
        var valueMergeCount = 0L
        var stackInconsistencyCount = 0
        var unsupportedInstructionCount = 0
        var failureCount = 0
        val warnings = mutableListOf<String>()

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                if (method.code == null) continue
                methodCount++
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"
                try {
                    val normalizedCode = legacySubroutineNormalizer.normalize(method.code).code
                    val normalizedMethod = if (normalizedCode === method.code) method else method.copy(code = normalizedCode)
                    val graph = graphBuilder.build(normalizedCode)
                    val analysis = analyzer.analyze(rawClass.internalName, normalizedMethod, graph)
                    frameMergeCount += analysis.frameMergeCount
                    valueMergeCount += analysis.valueMergeCount
                } catch (exception: StackInconsistencyException) {
                    stackInconsistencyCount++
                    failureCount++
                    warnings += "Frame analysis found inconsistent stack/local state in '$methodName': ${exception.message}."
                } catch (exception: UnsupportedFrameInstructionException) {
                    unsupportedInstructionCount++
                    failureCount++
                    warnings += "Frame analysis does not support '$methodName': ${exception.message}."
                } catch (exception: Exception) {
                    failureCount++
                    warnings += "Failed frame analysis for '$methodName': ${exception.message}."
                }
            }
        }

        return FrameDiagnosticsResult(
            methodCount = methodCount,
            frameMergeCount = frameMergeCount,
            valueMergeCount = valueMergeCount,
            stackInconsistencyCount = stackInconsistencyCount,
            unsupportedInstructionCount = unsupportedInstructionCount,
            failureCount = failureCount,
            warnings = warnings,
        )
    }
}

data class FrameDiagnosticsResult(
    val methodCount: Int,
    val frameMergeCount: Long,
    val valueMergeCount: Long,
    val stackInconsistencyCount: Int,
    val unsupportedInstructionCount: Int,
    val failureCount: Int,
    val warnings: List<String>,
)
