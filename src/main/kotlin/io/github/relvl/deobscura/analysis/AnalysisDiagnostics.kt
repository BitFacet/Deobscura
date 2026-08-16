package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.RawImportResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Runs method analysis for the imported application and reports aggregate diagnostics. */
class AnalysisDiagnostics(
    private val methodAnalyzer: MethodAnalyzer = MethodAnalyzer(),
    private val logger: Logger = LoggerFactory.getLogger(AnalysisDiagnostics::class.java),
) {
    fun inspect(rawImport: RawImportResult) {
        val stats = AnalysisDiagnosticStats()

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                if (method.code == null) continue
                stats.startMethod()
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"

                try {
                    val analysis = methodAnalyzer.analyze(rawClass.internalName, method)
                    stats.record(methodName, analysis)
                } catch (exception: MethodAnalysisException) {
                    stats.recordProgress(exception.progress)
                    recordFailure(methodName, exception, stats)
                }
            }
        }

        stats.log(logger)
    }

    private fun recordFailure(
        methodName: String,
        exception: MethodAnalysisException,
        stats: AnalysisDiagnosticStats,
    ) {
        val cause = exception.cause ?: exception
        when (exception.stage) {
            MethodAnalysisStage.PREPARATION -> {
                stats.preparationFailureCount++
                logger.warn("Failed to prepare analysis for '{}': {}.", methodName, cause.message)
            }

            MethodAnalysisStage.FRAME -> when (cause) {
                is StackInconsistencyException -> {
                    stats.frame.stackInconsistencyCount++
                    stats.frame.failureCount++
                    logger.warn(
                        "Frame analysis found inconsistent stack/local state in '{}': {}.",
                        methodName,
                        cause.message,
                    )
                }

                is UnsupportedFrameInstructionException -> {
                    stats.frame.unsupportedInstructionCount++
                    stats.frame.failureCount++
                    logger.warn("Frame analysis does not support '{}': {}.", methodName, cause.message)
                }

                else -> {
                    stats.frame.failureCount++
                    logger.warn("Failed frame analysis for '{}': {}.", methodName, cause.message)
                }
            }

            MethodAnalysisStage.VALUE_FLOW -> when (cause) {
                is ValueFlowInconsistencyException -> {
                    stats.valueFlow.inconsistencyCount++
                    stats.valueFlow.failureCount++
                    logger.warn("Value-flow analysis found inconsistent state in '{}': {}.", methodName, cause.message)
                }

                is UnsupportedValueFlowInstructionException -> {
                    stats.valueFlow.unsupportedInstructionCount++
                    stats.valueFlow.failureCount++
                    logger.warn("Value-flow analysis does not support '{}': {}.", methodName, cause.message)
                }

                else -> {
                    stats.valueFlow.failureCount++
                    logger.warn("Failed value-flow analysis for '{}': {}.", methodName, cause.message)
                }
            }

            MethodAnalysisStage.SSA -> {
                stats.ssa.failureCount++
                if (cause is SsaInconsistencyException) {
                    stats.ssa.inconsistencyCount++
                    logger.warn("SSA analysis found inconsistent state in '{}': {}.", methodName, cause.message)
                } else {
                    logger.warn("Failed SSA analysis for '{}': {}.", methodName, cause.message)
                }
            }
        }
    }
}
