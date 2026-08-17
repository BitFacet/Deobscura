package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.raw.RawImportResult
import io.github.relvl.deobscura.expression.ExpressionIrInconsistencyException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Runs method analysis for the imported application and reports aggregate diagnostics. */
class AnalysisDiagnostics(
    private val methodAnalyzer: MethodAnalyzer = MethodAnalyzer(),
    private val logger: Logger = LoggerFactory.getLogger(AnalysisDiagnostics::class.java),
) {
    fun inspect(rawImport: RawImportResult) {
        val stats = AnalysisDiagnosticStats()
        val methodCount = rawImport.classes.values.sumOf { rawClass ->
            rawClass.methods.count { it.code != null }
        }
        val progress = MethodAnalysisProgressLogger(
            totalMethodCount = methodCount,
            technicalIrEnabled = TechnicalIrService.enabled,
            logger = logger,
        )
        progress.start()

        for (rawClass in rawImport.classes.values) {
            TechnicalIrService.captureClass(rawClass)
            for (method in rawClass.methods) {
                if (method.code == null) continue
                stats.startMethod()
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"

                try {
                    val analysis = methodAnalyzer.analyze(rawClass.internalName, method)
                    stats.record(methodName, analysis)
                } catch (exception: MethodAnalysisException) {
                    stats.recordProgress(exception.progress)
                    recordFailure(rawClass.internalName, method.name, method.descriptor, methodName, exception, stats)
                } finally {
                    progress.methodCompleted()
                }
            }
        }

        progress.finish()
        stats.log(logger)
    }

    private fun recordFailure(
        ownerInternalName: String,
        methodSimpleName: String,
        descriptor: String,
        methodName: String,
        exception: MethodAnalysisException,
        stats: AnalysisDiagnosticStats,
    ) {
        val cause = exception.cause ?: exception
        val ir = TechnicalIrService.methodHint(ownerInternalName, methodSimpleName, descriptor)
        when (exception.stage) {
            MethodAnalysisStage.PREPARATION -> {
                stats.preparationFailureCount++
                logger.warn("Failed to prepare analysis for '{}': {}.{}", methodName, cause.message, ir)
            }

            MethodAnalysisStage.FRAME -> when (cause) {
                is StackInconsistencyException -> {
                    stats.frame.stackInconsistencyCount++
                    stats.frame.failureCount++
                    logger.warn(
                        "Frame analysis found inconsistent stack/local state in '{}': {}.{}",
                        methodName,
                        cause.message,
                        ir,
                    )
                }

                is UnsupportedFrameInstructionException -> {
                    stats.frame.unsupportedInstructionCount++
                    stats.frame.failureCount++
                    logger.warn("Frame analysis does not support '{}': {}.{}", methodName, cause.message, ir)
                }

                else -> {
                    stats.frame.failureCount++
                    logger.warn("Failed frame analysis for '{}': {}.{}", methodName, cause.message, ir)
                }
            }

            MethodAnalysisStage.VALUE_FLOW -> when (cause) {
                is ValueFlowInconsistencyException -> {
                    stats.valueFlow.inconsistencyCount++
                    stats.valueFlow.failureCount++
                    logger.warn(
                        "Value-flow analysis found inconsistent state in '{}': {}.{}",
                        methodName,
                        cause.message,
                        ir,
                    )
                }

                is UnsupportedValueFlowInstructionException -> {
                    stats.valueFlow.unsupportedInstructionCount++
                    stats.valueFlow.failureCount++
                    logger.warn("Value-flow analysis does not support '{}': {}.{}", methodName, cause.message, ir)
                }

                else -> {
                    stats.valueFlow.failureCount++
                    logger.warn("Failed value-flow analysis for '{}': {}.{}", methodName, cause.message, ir)
                }
            }

            MethodAnalysisStage.SSA -> {
                stats.ssa.failureCount++
                if (cause is SsaInconsistencyException) {
                    stats.ssa.inconsistencyCount++
                    logger.warn("SSA analysis found inconsistent state in '{}': {}.{}", methodName, cause.message, ir)
                } else {
                    logger.warn("Failed SSA analysis for '{}': {}.{}", methodName, cause.message, ir)
                }
            }

            MethodAnalysisStage.EXPRESSION -> {
                stats.expression.failureCount++
                if (cause is ExpressionIrInconsistencyException) {
                    stats.expression.inconsistencyCount++
                    logger.warn("Expression IR found inconsistent SSA state in '{}': {}.{}", methodName, cause.message, ir)
                } else {
                    logger.warn("Failed expression IR construction for '{}': {}.{}", methodName, cause.message, ir)
                }
            }
        }
    }
}

private class MethodAnalysisProgressLogger(
    private val totalMethodCount: Int,
    private val technicalIrEnabled: Boolean,
    private val logger: Logger,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var completedMethodCount = 0
    private var startedAt = 0L
    private var nextProgressAt = 0L

    fun start() {
        startedAt = nanoTime()
        nextProgressAt = startedAt + PROGRESS_INTERVAL_NANOS
        val ir = if (technicalIrEnabled) "; technical IR snapshots enabled" else ""
        logger.info(
            "Starting method analysis pipeline for {} method(s): legacy normalization, JVM frames, value flow, SSA optimization, expression IR{}.",
            totalMethodCount,
            ir,
        )
    }

    fun methodCompleted() {
        completedMethodCount++
        val now = nanoTime()
        if (now < nextProgressAt) return

        val percent = if (totalMethodCount == 0) {
            100
        } else {
            (completedMethodCount.toLong() * 100 / totalMethodCount).toInt()
        }
        logger.info(
            "Method analysis progress: {}/{} method(s) ({}%).",
            completedMethodCount,
            totalMethodCount,
            percent,
        )
        nextProgressAt = now + PROGRESS_INTERVAL_NANOS
    }

    fun finish() {
        logger.info(
            "Method analysis pipeline completed: {}/{} method(s) processed in {}.",
            completedMethodCount,
            totalMethodCount,
            formatElapsed(nanoTime() - startedAt),
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 5_000_000_000L
    }
}

private fun formatElapsed(nanos: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0)
