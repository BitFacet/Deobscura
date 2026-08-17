package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.diagnostics.ir.MethodAnalysisTrace
import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.expression.ExpressionIrInconsistencyException
import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.raw.RawImportResult
import io.github.relvl.deobscura.raw.RawMethod
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Runs method analysis for the imported application and reports aggregate diagnostics. */
class AnalysisDiagnostics(
    private val methodAnalyzer: MethodAnalyzer = MethodAnalyzer(),
    private val logger: Logger = LoggerFactory.getLogger(AnalysisDiagnostics::class.java),
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
) {
    fun inspect(rawImport: RawImportResult) {
        val stats = AnalysisDiagnosticStats()
        val classes = rawImport.classes.values.toList()
        val methodCount = classes.sumOf { rawClass -> rawClass.methods.count { it.code != null } }
        val technicalIrEnabled = TechnicalIrService.enabled
        val progress = MethodAnalysisProgressLogger(
            totalMethodCount = methodCount,
            technicalIrEnabled = technicalIrEnabled,
            logger = logger,
        )
        val processorCount = availableProcessors().coerceAtLeast(1)
        val workerCount = analysisWorkerCount(processorCount)

        classes.forEach(TechnicalIrService::captureClass)
        progress.start(workerCount, processorCount)

        val threadNumber = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "deobscura-analysis-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val classIterator = classes.iterator()
            val pending = ArrayDeque<Future<ClassAnalysisResult>>()
            val maxInFlight = workerCount * MAX_IN_FLIGHT_CLASSES_PER_WORKER

            fun submitNext(): Boolean {
                if (!classIterator.hasNext()) return false
                val rawClass = classIterator.next()
                pending.add(
                    executor.submit(Callable { analyzeClass(rawClass, technicalIrEnabled, progress) }),
                )
                return true
            }

            repeat(maxInFlight) { if (!submitNext()) return@repeat }
            while (pending.isNotEmpty()) {
                recordClassResult(pending.removeFirst().get(), stats)
                submitNext()
            }
        } finally {
            executor.shutdownNow()
        }

        progress.finish()
        stats.log(logger)
    }

    private fun analyzeClass(
        rawClass: RawClass,
        technicalIrEnabled: Boolean,
        progress: MethodAnalysisProgressLogger,
    ): ClassAnalysisResult {
        val methods = rawClass.methods.mapNotNull { method ->
            if (method.code == null) return@mapNotNull null
            val trace = if (technicalIrEnabled) MethodAnalysisTrace(method) else null
            try {
                MethodTaskResult(method, methodAnalyzer.analyze(rawClass.internalName, method, trace), null, trace)
            } catch (exception: MethodAnalysisException) {
                MethodTaskResult(method, null, exception, trace)
            } finally {
                progress.methodCompleted()
            }
        }
        return ClassAnalysisResult(rawClass, methods)
    }

    private fun recordClassResult(result: ClassAnalysisResult, stats: AnalysisDiagnosticStats) {
        result.methods.forEach { methodResult ->
            stats.startMethod()
            methodResult.trace?.let { TechnicalIrService.captureMethod(result.rawClass.internalName, it) }
            val method = methodResult.method
            val methodName = "${result.rawClass.internalName}.${method.name}${method.descriptor}"
            val analysis = methodResult.analysis
            if (analysis != null) {
                analysis.structuredControlFlow.unstructured
                    .filter { it.kind == UnstructuredControlFlowKind.SWITCH }
                    .forEach { diagnostic ->
                        logger.warn(
                            "Unstructured switch in '{}': B{} ({}).{}",
                            methodName,
                            diagnostic.header.value,
                            diagnostic.reason.diagnosticName,
                            TechnicalIrService.methodHint(result.rawClass.internalName, method.name, method.descriptor),
                        )
                    }
                analysis.structuredControlFlow.unstructured
                    .filter { it.kind == UnstructuredControlFlowKind.EXCEPTION }
                    .forEach { diagnostic ->
                        logger.warn(
                            "Unstructured exception region in '{}': B{} protected={}..<{} ({}).{}",
                            methodName,
                            diagnostic.header.value,
                            diagnostic.protectedStartInstructionIndex,
                            diagnostic.protectedEndInstructionIndexExclusive,
                            diagnostic.reason.diagnosticName,
                            TechnicalIrService.methodHint(result.rawClass.internalName, method.name, method.descriptor),
                        )
                    }
                stats.record(methodName, analysis)
                return@forEach
            }

            val exception = requireNotNull(methodResult.failure)
            stats.recordProgress(exception.progress)
            recordFailure(
                result.rawClass.internalName,
                method.name,
                method.descriptor,
                methodName,
                exception,
                stats,
            )
        }
    }

    private companion object {
        const val MAX_IN_FLIGHT_CLASSES_PER_WORKER = 8
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

            MethodAnalysisStage.STRUCTURED_CONTROL_FLOW -> {
                stats.structuredControlFlow.failureCount++
                if (cause is io.github.relvl.deobscura.controlflow.StructuredControlFlowInconsistencyException) {
                    stats.structuredControlFlow.inconsistencyCount++
                    logger.warn("Structured control-flow analysis found inconsistent state in '{}': {}.{}", methodName, cause.message, ir)
                } else {
                    logger.warn("Failed structured control-flow analysis for '{}': {}.{}", methodName, cause.message, ir)
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

    fun start(workerCount: Int, availableProcessorCount: Int) {
        startedAt = nanoTime()
        nextProgressAt = startedAt + PROGRESS_INTERVAL_NANOS
        val ir = if (technicalIrEnabled) "; technical IR snapshots enabled" else ""
        logger.info(
            "Starting method analysis pipeline for {} method(s) on {} worker(s) / {} available processor(s): legacy normalization, JVM frames, value flow, SSA optimization, expression IR{}.",
            totalMethodCount,
            workerCount,
            availableProcessorCount,
            ir,
        )
    }

    @Synchronized
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

    @Synchronized
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

internal fun analysisWorkerCount(availableProcessors: Int): Int {
    val processors = availableProcessors.coerceAtLeast(1)
    return ((processors * 3) + 3) / 4
}

private data class ClassAnalysisResult(
    val rawClass: RawClass,
    val methods: List<MethodTaskResult>,
)

private data class MethodTaskResult(
    val method: RawMethod,
    val analysis: MethodAnalysis?,
    val failure: MethodAnalysisException?,
    val trace: MethodAnalysisTrace?,
)
