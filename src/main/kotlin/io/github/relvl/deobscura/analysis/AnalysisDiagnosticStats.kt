package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowReason
import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import org.slf4j.Logger

internal class AnalysisDiagnosticStats {
    val legacy = LegacySubroutineDiagnosticStats()
    val frame = FrameDiagnosticStats()
    val valueFlow = ValueFlowDiagnosticStats()
    val ssa = SsaDiagnosticStats()
    val expression = ExpressionDiagnosticStats()
    val structuredControlFlow = StructuredControlFlowDiagnosticStats()
    val sourceStructure = SourceStructureDiagnosticStats()
    val sourceLocals = SourceLocalDiagnosticStats()
    var preparationFailureCount = 0

    fun startMethod() {
        frame.methodCount++
        valueFlow.methodCount++
        ssa.methodCount++
        expression.methodCount++
        structuredControlFlow.methodCount++
        sourceStructure.methodCount++
        sourceLocals.methodCount++
    }

    fun record(methodName: String, analysis: MethodAnalysis) {
        legacy.record(analysis.normalization)
        frame.record(analysis.frames)
        valueFlow.record(analysis.valueFlow)
        ssa.record(methodName, analysis.graph, analysis.initialSsa, analysis.optimization)
        expression.record(analysis.expression)
        structuredControlFlow.record(methodName, analysis.structuredControlFlow)
        sourceStructure.record(analysis.sourceStructure)
        sourceLocals.record(analysis.sourceLocals)
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
        structuredControlFlow.log(logger)
        sourceStructure.log(logger)
        sourceLocals.log(logger)
    }
}

internal class SourceLocalDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var conditionalValueCount = 0L
    private var conditionalAssignmentCount = 0L
    private var twoArmAssignmentCount = 0L
    private var consumedIfCount = 0L

    fun record(analysis: io.github.relvl.deobscura.source.SourceLocalAnalysis) {
        analyzedMethodCount++
        conditionalValueCount += analysis.conditionalValues.size
        conditionalAssignmentCount += analysis.conditionalAssignments.size
        twoArmAssignmentCount += analysis.twoArmAssignments.size
        consumedIfCount += analysis.consumedIfHeaders.size
    }

    fun log(logger: Logger) {
        logger.info(
            "Source locals analyzed {}/{} method(s): {} conditional phi value(s), {} conditional assignment phi value(s), {} two-arm assignment phi value(s), {} materialization if(s) removed.",
            analyzedMethodCount, methodCount, conditionalValueCount, conditionalAssignmentCount, twoArmAssignmentCount, consumedIfCount,
        )
    }
}

internal class SourceStructureDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var issueMethodCount = 0
    private var issueCount = 0L
    private var fallbackBlockCount = 0L
    var failureCount = 0

    fun record(analysis: io.github.relvl.deobscura.source.SourceStructureAnalysis) {
        analyzedMethodCount++
        if (analysis.issues.isNotEmpty()) issueMethodCount++
        issueCount += analysis.issues.size
        fallbackBlockCount += analysis.issues.sumOf { it.blocks.size.toLong() }
    }

    fun log(logger: Logger) {
        logger.info(
            "Source structure projected {}/{} method(s): {} projection issue(s) in {} method(s), {} fallback block(s).",
            analyzedMethodCount, methodCount, issueCount, issueMethodCount, fallbackBlockCount,
        )
        logger.info("Source-structure projection completed with {} failure(s).", failureCount)
    }
}

internal class StructuredControlFlowDiagnosticStats {
    var methodCount = 0
    private var analyzedMethodCount = 0
    private var structuredMethodCount = 0
    private var conditionalBranchCount = 0L
    private var ifRegionCount = 0L
    private var whileRegionCount = 0L
    private var switchRegionCount = 0L
    private var exceptionRegionCount = 0L
    private var structuredExceptionRegionCount = 0L
    private var tryCatchRegionCount = 0L
    private var tryCatchFinallyRegionCount = 0L
    private var tryFinallyRegionCount = 0L
    private var synchronizedRegionCount = 0L
    private var unstructuredExceptionRegionCount = 0L
    private var unstructuredConditionalCount = 0L
    private var unstructuredSwitchCount = 0L
    private var switchCount = 0L
    private var booleanConditionFoldCount = 0L
    private var shortCircuitConditionFoldCount = 0L
    private var shortCircuitFoldedHeaderCount = 0L
    private var emptyArmNormalizationCount = 0L
    private var terminalIfRegionCount = 0L
    private var continueIfRegionCount = 0L
    private var breakIfRegionCount = 0L
    private var loopBodyIfRegionCount = 0L
    private var loopContinuationIfRegionCount = 0L
    private var loopBreakEdgeCount = 0L
    private val unstructuredReasonCounts = linkedMapOf<UnstructuredControlFlowReason, Long>()
    private val exceptionResidualFamilies = ExceptionResidualFamilyStats()
    var inconsistencyCount = 0
    var failureCount = 0

    fun record(methodName: String, analysis: StructuredControlFlowAnalysis) {
        analyzedMethodCount++
        conditionalBranchCount += analysis.conditionalBranchCount
        switchCount += analysis.switchCount
        exceptionRegionCount += analysis.exceptionRegionCount
        booleanConditionFoldCount += analysis.booleanConditionFolds.size
        shortCircuitConditionFoldCount += analysis.shortCircuitConditionFolds.size
        shortCircuitFoldedHeaderCount += analysis.shortCircuitConditionFolds.sumOf { it.foldedHeaders.size.toLong() }
        emptyArmNormalizationCount += analysis.emptyArmNormalizationCount
        terminalIfRegionCount += analysis.terminalIfRegionCount
        continueIfRegionCount += analysis.continueIfRegionCount
        breakIfRegionCount += analysis.breakIfRegionCount
        loopBodyIfRegionCount += analysis.loopBodyIfRegionCount
        loopContinuationIfRegionCount += analysis.loopContinuationIfRegionCount
        loopBreakEdgeCount += analysis.regions.filterIsInstance<StructuredRegion.While>().sumOf { it.breakEdges.size.toLong() }
        analysis.unstructured.forEach { diagnostic ->
            unstructuredReasonCounts[diagnostic.reason] = unstructuredReasonCounts.getOrDefault(diagnostic.reason, 0L) + 1L
            exceptionResidualFamilies.record(methodName, diagnostic)
        }
        val ifs = analysis.regions.count { it is StructuredRegion.If }
        val whiles = analysis.regions.count { it is StructuredRegion.While }
        val switches = analysis.regions.count { it is StructuredRegion.Switch }
        val tryCatches = analysis.regions.count { it is StructuredRegion.TryCatch }
        val tryCatchFinallys = analysis.regions.count { it is StructuredRegion.TryCatchFinally }
        val tryFinallys = analysis.regions.count { it is StructuredRegion.TryFinally }
        val synchronizeds = analysis.regions.count { it is StructuredRegion.Synchronized }
        val exceptions = tryCatches + tryCatchFinallys + tryFinallys + synchronizeds
        ifRegionCount += ifs
        whileRegionCount += whiles
        switchRegionCount += switches
        structuredExceptionRegionCount += analysis.exceptionRegionCount - analysis.unstructuredExceptionRegionCount
        tryCatchRegionCount += tryCatches
        tryCatchFinallyRegionCount += tryCatchFinallys
        tryFinallyRegionCount += tryFinallys
        synchronizedRegionCount += synchronizeds
        unstructuredConditionalCount += analysis.unstructuredConditionalCount
        unstructuredSwitchCount += analysis.unstructured.count {
            it.kind == UnstructuredControlFlowKind.SWITCH
        }
        unstructuredExceptionRegionCount += analysis.unstructuredExceptionRegionCount
        if (ifs + whiles + switches + exceptions + analysis.booleanConditionFolds.size + analysis.shortCircuitConditionFolds.size > 0) structuredMethodCount++
    }

    fun log(logger: Logger) {
        logger.info(
            "Structured control flow for {}/{} method(s): {} if region(s), {} natural while loop(s), {} switch region(s), {} try/catch region(s), {} try/catch/finally region(s), {} try/finally region(s), {} synchronized region(s) in {} method(s).",
            analyzedMethodCount,
            methodCount,
            ifRegionCount,
            whileRegionCount,
            switchRegionCount,
            tryCatchRegionCount,
            tryCatchFinallyRegionCount,
            tryFinallyRegionCount,
            synchronizedRegionCount,
            structuredMethodCount,
        )
        logger.info(
            "Control-flow structuring classified {}/{} conditional branch header(s); {} remain block-based. Structured {}/{} switch(es); {} remain block-based. Structured {}/{} exception region(s); {} remain block-based.",
            ifRegionCount + whileRegionCount + booleanConditionFoldCount + shortCircuitFoldedHeaderCount,
            conditionalBranchCount,
            unstructuredConditionalCount,
            switchRegionCount,
            switchCount,
            unstructuredSwitchCount,
            structuredExceptionRegionCount,
            exceptionRegionCount,
            unstructuredExceptionRegionCount,
        )
        logger.info(
            "Control-flow normalization folded {} boolean materialization diamond(s) and {} short-circuit chain(s) ({} condition header(s)), normalized {} empty if arm(s), recognized {} terminal-arm if region(s), {} continue if region(s), {} break if region(s), {} loop-body regional if(s), and {} loop-continuation if(s).",
            booleanConditionFoldCount,
            shortCircuitConditionFoldCount,
            shortCircuitFoldedHeaderCount,
            emptyArmNormalizationCount,
            terminalIfRegionCount,
            continueIfRegionCount,
            breakIfRegionCount,
            loopBodyIfRegionCount,
            loopContinuationIfRegionCount,
        )
        logger.info(
            "Loop structuring recognized {} body edge(s) to canonical loop exits as break transfer(s).",
            loopBreakEdgeCount,
        )
        if (unstructuredReasonCounts.isNotEmpty()) {
            logger.info(
                "Unstructured control-flow reasons: {}.",
                unstructuredReasonCounts.entries
                    .sortedByDescending { it.value }
                    .joinToString { (reason, count) -> "${reason.diagnosticName}=$count" },
            )
        }
        exceptionResidualFamilies.log(logger)
        logger.info(
            "Structured control-flow analysis completed with {} failure(s): {} inconsistent state(s).",
            failureCount,
            inconsistencyCount,
        )
    }
}


/** Aggregates unsupported catch-all regions into coarse structural families with a few examples. */
internal class ExceptionResidualFamilyStats(
    private val representativeLimit: Int = 3,
    private val familyLogLimit: Int = 16,
) {
    private data class Family(
        var count: Long = 0,
        val representatives: LinkedHashSet<String> = linkedSetOf(),
    )

    private val families = linkedMapOf<String, Family>()

    fun record(methodName: String, diagnostic: io.github.relvl.deobscura.controlflow.UnstructuredControlFlowDiagnostic) {
        if (diagnostic.kind != UnstructuredControlFlowKind.EXCEPTION ||
            diagnostic.reason != UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED
        ) return
        val familyName = diagnostic.exceptionResidualFamily ?: "unclassified"
        val family = families.getOrPut(familyName) { Family() }
        family.count++
        if (family.representatives.size < representativeLimit) family.representatives += methodName
    }

    internal fun summaries(): List<String> = families.entries
        .sortedWith(compareByDescending<Map.Entry<String, Family>> { it.value.count }.thenBy { it.key })
        .map { (name, family) ->
            buildString {
                append(name).append('=').append(family.count)
                if (family.representatives.isNotEmpty()) {
                    append(" [e.g. ").append(family.representatives.joinToString()).append(']')
                }
            }
        }

    fun log(logger: Logger) {
        val ordered = families.entries
            .sortedWith(compareByDescending<Map.Entry<String, Family>> { it.value.count }.thenBy { it.key })
        if (ordered.isEmpty()) return

        logger.info(
            "Unsupported catch-all/finally residuals grouped into {} structural family(s); showing up to {} largest.",
            ordered.size,
            familyLogLimit,
        )
        ordered.take(familyLogLimit).forEach { (name, family) ->
            logger.info(
                "Finally residual family: {} region(s), {}; examples: {}.",
                family.count,
                name,
                family.representatives.joinToString(),
            )
        }
        if (ordered.size > familyLogLimit) {
            val hidden = ordered.drop(familyLogLimit)
            logger.info(
                "Finally residual families omitted: {} family(s), {} region(s).",
                hidden.size,
                hidden.sumOf { it.value.count },
            )
        }
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
