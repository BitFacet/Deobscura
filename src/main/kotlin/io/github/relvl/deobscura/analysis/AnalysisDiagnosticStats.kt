package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import org.slf4j.Logger

internal class AnalysisDiagnosticStats {
    val legacy = LegacySubroutineDiagnosticStats()
    val frame = FrameDiagnosticStats()
    val valueFlow = ValueFlowDiagnosticStats()
    val ssa = SsaDiagnosticStats()
    val expression = ExpressionDiagnosticStats()
    var preparationFailureCount = 0

    fun startMethod() {
        frame.methodCount++
        valueFlow.methodCount++
        ssa.methodCount++
        expression.methodCount++
    }

    fun record(methodName: String, analysis: MethodAnalysis) {
        legacy.record(analysis.normalization)
        frame.record(analysis.frames)
        valueFlow.record(analysis.valueFlow)
        ssa.record(methodName, analysis.graph, analysis.initialSsa, analysis.optimization)
        expression.record(analysis.expression)
    }

    fun recordProgress(progress: MethodAnalysisProgress) {
        progress.normalization?.let(legacy::record)
        progress.frames?.let(frame::record)
        progress.valueFlow?.let(valueFlow::record)
    }

    fun log(logger: Logger) {
        if (preparationFailureCount > 0) {
            val ir = TechnicalIrService.rootHint()
            logger.warn("Analysis pipeline preparation failed for {} method(s).{}", preparationFailureCount, ir)
        }
        legacy.log(logger)
        frame.log(logger)
        valueFlow.log(logger)
        ssa.log(logger)
        expression.log(logger)
    }
}

internal class ExpressionDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var valueCount = 0L
    private var statementCount = 0L
    private var rawValueCount = 0L
    private var rawStatementCount = 0L
    private var constructedObjectCount = 0L
    private var inlinedValueCount = 0L
    private var discardedResultCount = 0L
    private var booleanPhiCount = 0L
    var inconsistencyCount = 0
    var failureCount = 0

    fun record(analysis: ExpressionAnalysis) {
        analyzedMethodCount++
        valueCount += analysis.values.size
        statementCount += analysis.statements.size
        rawValueCount += analysis.values.values.count { it.node is ExpressionNode.Raw }
        rawStatementCount += analysis.statements.count { it is ExpressionStatement.Raw }
        constructedObjectCount += analysis.values.values.count { it.node is ExpressionNode.ConstructObject }
        inlinedValueCount += analysis.materialization.inlineValues.size
        discardedResultCount += analysis.materialization.discardedResultValues.size
        booleanPhiCount += analysis.materialization.booleanValues.size
    }

    fun log(logger: Logger) {
        logger.info(
            "Built expression IR for {}/{} method(s): {} value expression(s), {} statement(s), {} object construction(s).",
            analyzedMethodCount,
            methodCount,
            valueCount,
            statementCount,
            constructedObjectCount,
        )
        logger.info(
            "Expression materialization inlined {} pure single-use value(s), rendered {} unused result(s) as statement(s), and recognized {} boolean phi value(s).",
            inlinedValueCount,
            discardedResultCount,
            booleanPhiCount,
        )
        logger.info(
            "Expression IR completed with {} failure(s): {} inconsistent state(s), {} raw value node(s), {} raw statement node(s).",
            failureCount,
            inconsistencyCount,
            rawValueCount,
            rawStatementCount,
        )
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
    private var referenceMergeCount = 0L
    private var impreciseReferenceMergeCount = 0L
    var stackInconsistencyCount = 0
    var unsupportedInstructionCount = 0
    var failureCount = 0

    fun record(analysis: FrameAnalysis) {
        analyzedMethodCount++
        frameMergeCount += analysis.frameMergeCount
        valueMergeCount += analysis.valueMergeCount
        referenceMergeCount += analysis.referenceMergeCount
        impreciseReferenceMergeCount += analysis.impreciseReferenceMergeCount
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
            "Frame reference typing merged {} differing reference value(s): {} merge(s) lost exact type precision.",
            referenceMergeCount,
            impreciseReferenceMergeCount,
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
