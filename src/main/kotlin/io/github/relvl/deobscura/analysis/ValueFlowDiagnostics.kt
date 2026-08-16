package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawImportResult

class ValueFlowDiagnostics(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val frameAnalyzer: FrameAnalyzer = FrameAnalyzer(),
    private val valueFlowAnalyzer: ValueFlowAnalyzer = ValueFlowAnalyzer(),
    private val legacySubroutineNormalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun inspect(rawImport: RawImportResult): ValueFlowDiagnosticsResult {
        var methodCount = 0
        var valueCount = 0L
        var operationCount = 0L
        var mergeValueCount = 0L
        var eliminatedStackInstructionCount = 0L
        var unanalyzedBlockCount = 0L
        var inconsistencyCount = 0
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
                    val frames = frameAnalyzer.analyze(rawClass.internalName, normalizedMethod, graph)
                    val analysis = valueFlowAnalyzer.analyze(graph, frames)
                    valueCount += analysis.values.size
                    operationCount += analysis.operations.size
                    mergeValueCount += analysis.mergeValueCount
                    eliminatedStackInstructionCount += analysis.eliminatedStackInstructionCount
                    unanalyzedBlockCount += analysis.unanalyzedBlockCount
                } catch (exception: ValueFlowInconsistencyException) {
                    inconsistencyCount++
                    failureCount++
                    warnings += "Value-flow analysis found inconsistent state in '$methodName': ${exception.message}."
                } catch (exception: UnsupportedValueFlowInstructionException) {
                    unsupportedInstructionCount++
                    failureCount++
                    warnings += "Value-flow analysis does not support '$methodName': ${exception.message}."
                } catch (exception: Exception) {
                    failureCount++
                    warnings += "Failed value-flow analysis for '$methodName': ${exception.message}."
                }
            }
        }

        return ValueFlowDiagnosticsResult(
            methodCount = methodCount,
            valueCount = valueCount,
            operationCount = operationCount,
            mergeValueCount = mergeValueCount,
            eliminatedStackInstructionCount = eliminatedStackInstructionCount,
            unanalyzedBlockCount = unanalyzedBlockCount,
            inconsistencyCount = inconsistencyCount,
            unsupportedInstructionCount = unsupportedInstructionCount,
            failureCount = failureCount,
            warnings = warnings,
        )
    }
}

data class ValueFlowDiagnosticsResult(
    val methodCount: Int,
    val valueCount: Long,
    val operationCount: Long,
    val mergeValueCount: Long,
    val eliminatedStackInstructionCount: Long,
    val unanalyzedBlockCount: Long,
    val inconsistencyCount: Int,
    val unsupportedInstructionCount: Int,
    val failureCount: Int,
    val warnings: List<String>,
)
