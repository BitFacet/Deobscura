package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import org.slf4j.Logger

internal class AnalysisDiagnosticStats {
    val legacy = LegacySubroutineDiagnosticStats()
    val frame = FrameDiagnosticStats()
    val valueFlow = ValueFlowDiagnosticStats()
    val ssa = SsaDiagnosticStats()
    var preparationFailureCount = 0

    fun startMethod() {
        frame.methodCount++
        valueFlow.methodCount++
        ssa.methodCount++
    }

    fun record(methodName: String, analysis: MethodAnalysis) {
        legacy.record(analysis.normalization)
        frame.record(analysis.frames)
        valueFlow.record(analysis.valueFlow)
        ssa.record(methodName, analysis.graph, analysis.initialSsa, analysis.optimization)
    }

    fun recordProgress(progress: MethodAnalysisProgress) {
        progress.normalization?.let(legacy::record)
        progress.frames?.let(frame::record)
        progress.valueFlow?.let(valueFlow::record)
    }

    fun log(logger: Logger) {
        if (preparationFailureCount > 0) {
            logger.warn("Analysis pipeline preparation failed for {} method(s).", preparationFailureCount)
        }
        legacy.log(logger)
        frame.log(logger)
        valueFlow.log(logger)
        ssa.log(logger)
    }
}

internal class LegacySubroutineDiagnosticStats {
    private var methodCount = 0
    private var jsrCallSiteCount = 0L
    private var clonedBlockCount = 0L
    private var normalizedInstructionCount = 0L

    fun record(result: LegacySubroutineNormalizationResult) {
        if (result.jsrCallSiteCount == 0) return
        methodCount++
        jsrCallSiteCount += result.jsrCallSiteCount
        clonedBlockCount += result.clonedBlockCount
        normalizedInstructionCount += result.normalizedInstructionCount
    }

    fun log(logger: Logger) {
        logger.info(
            "Normalized legacy JSR/RET in {} method(s): {} JSR call site(s), {} cloned basic block(s), {} normalized instruction(s).",
            methodCount,
            jsrCallSiteCount,
            clonedBlockCount,
            normalizedInstructionCount,
        )
    }
}

internal class FrameDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var frameMergeCount = 0L
    private var valueMergeCount = 0L
    var stackInconsistencyCount = 0
    var unsupportedInstructionCount = 0
    var failureCount = 0

    fun record(analysis: FrameAnalysis) {
        analyzedMethodCount++
        frameMergeCount += analysis.frameMergeCount
        valueMergeCount += analysis.valueMergeCount
    }

    fun log(logger: Logger) {
        logger.info(
            "Analyzed JVM frames for {}/{} method(s): {} frame merge(s), {} value merge(s).",
            analyzedMethodCount,
            methodCount,
            frameMergeCount,
            valueMergeCount,
        )
        logger.info(
            "Frame analysis completed with {} failure(s): {} stack/local inconsistency(s), {} unsupported instruction case(s).",
            failureCount,
            stackInconsistencyCount,
            unsupportedInstructionCount,
        )
    }
}

internal class ValueFlowDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var valueCount = 0L
    private var operationCount = 0L
    private var mergeValueCount = 0L
    private var eliminatedStackInstructionCount = 0L
    private var unanalyzedBlockCount = 0L
    var inconsistencyCount = 0
    var unsupportedInstructionCount = 0
    var failureCount = 0

    fun record(analysis: ValueFlowAnalysis) {
        analyzedMethodCount++
        valueCount += analysis.values.size
        operationCount += analysis.operations.size
        mergeValueCount += analysis.mergeValueCount
        eliminatedStackInstructionCount += analysis.eliminatedStackInstructionCount
        unanalyzedBlockCount += analysis.unanalyzedBlockCount
    }

    fun log(logger: Logger) {
        logger.info(
            "Built explicit value flow for {}/{} method(s): {} values, {} operation(s), {} merge value(s), {} stack instruction(s) eliminated.",
            analyzedMethodCount,
            methodCount,
            valueCount,
            operationCount,
            mergeValueCount,
            eliminatedStackInstructionCount,
        )
        if (unanalyzedBlockCount > 0) {
            logger.debug("Value flow excluded {} unreachable basic block(s).", unanalyzedBlockCount)
        }
        logger.info(
            "Value-flow analysis completed with {} failure(s): {} inconsistent state(s), {} unsupported instruction case(s).",
            failureCount,
            inconsistencyCount,
            unsupportedInstructionCount,
        )
    }
}
