package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawMethod
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionBuilder
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalyzer

/** Runs the complete low-level analysis pipeline for one method. */
class MethodAnalyzer(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val frameAnalyzer: FrameAnalyzer = FrameAnalyzer(),
    private val valueFlowAnalyzer: ValueFlowAnalyzer = ValueFlowAnalyzer(),
    private val ssaAnalyzer: SsaAnalyzer = SsaAnalyzer(),
    private val ssaOptimizer: SsaOptimizer = SsaOptimizer(),
    private val expressionBuilder: ExpressionBuilder = ExpressionBuilder(),
    private val structuredControlFlowAnalyzer: StructuredControlFlowAnalyzer = StructuredControlFlowAnalyzer(),
    private val legacySubroutineNormalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun analyze(ownerInternalName: String, method: RawMethod): MethodAnalysis {
        val code = requireNotNull(method.code) { "Cannot analyze a method without code." }

        val normalization = try {
            legacySubroutineNormalizer.normalize(code)
        } catch (exception: Exception) {
            throw failure(ownerInternalName, method, MethodAnalysisStage.PREPARATION, cause = exception)
        }

        val normalizedCode = normalization.code
        val normalizedMethod = if (normalizedCode === code) method else method.copy(code = normalizedCode)
        TechnicalIrService.captureNormalization(ownerInternalName, method, normalizedMethod, normalization)

        val graph = try {
            graphBuilder.build(normalizedCode)
        } catch (exception: Exception) {
            throw failure(ownerInternalName, method, MethodAnalysisStage.PREPARATION, normalization, cause = exception)
        }
        TechnicalIrService.captureGraph(ownerInternalName, method, graph)

        val frames = try {
            frameAnalyzer.analyze(ownerInternalName, normalizedMethod, graph)
        } catch (exception: Exception) {
            throw failure(ownerInternalName, method, MethodAnalysisStage.FRAME, normalization, cause = exception)
        }
        TechnicalIrService.captureFrames(ownerInternalName, method, frames)

        val valueFlow = try {
            valueFlowAnalyzer.analyze(graph, frames)
        } catch (exception: Exception) {
            throw failure(ownerInternalName, method, MethodAnalysisStage.VALUE_FLOW, normalization, frames, cause = exception)
        }
        TechnicalIrService.captureValueFlow(ownerInternalName, method, valueFlow)

        val initialSsa = try {
            ssaAnalyzer.analyze(graph, valueFlow)
        } catch (exception: Exception) {
            throw failure(ownerInternalName, method, MethodAnalysisStage.SSA, normalization, frames, valueFlow, cause = exception)
        }
        TechnicalIrService.captureInitialSsa(ownerInternalName, method, initialSsa)

        val optimization = try {
            ssaOptimizer.optimize(graph, initialSsa)
        } catch (exception: Exception) {
            throw failure(
                ownerInternalName, method, MethodAnalysisStage.SSA, normalization, frames, valueFlow, initialSsa, cause = exception,
            )
        }
        TechnicalIrService.captureOptimization(ownerInternalName, method, optimization)

        val expression = try {
            expressionBuilder.build(optimization.analysis)
        } catch (exception: Exception) {
            throw failure(
                ownerInternalName,
                method,
                MethodAnalysisStage.EXPRESSION,
                normalization,
                frames,
                valueFlow,
                initialSsa,
                optimization,
                exception,
            )
        }
        TechnicalIrService.captureExpression(ownerInternalName, method, expression)

        val structuredControlFlow = try {
            structuredControlFlowAnalyzer.analyze(graph, optimization.controlFlow, expression)
        } catch (exception: Exception) {
            throw failure(
                ownerInternalName,
                method,
                MethodAnalysisStage.STRUCTURED_CONTROL_FLOW,
                normalization,
                frames,
                valueFlow,
                initialSsa,
                optimization,
                exception,
            )
        }
        TechnicalIrService.captureStructuredControlFlow(ownerInternalName, method, structuredControlFlow)

        return MethodAnalysis(
            method = normalizedMethod,
            graph = graph,
            frames = frames,
            valueFlow = valueFlow,
            initialSsa = initialSsa,
            optimization = optimization,
            expression = expression,
            structuredControlFlow = structuredControlFlow,
            normalization = normalization,
        )
    }

    private fun failure(
        ownerInternalName: String,
        method: RawMethod,
        stage: MethodAnalysisStage,
        normalization: LegacySubroutineNormalizationResult? = null,
        frames: FrameAnalysis? = null,
        valueFlow: ValueFlowAnalysis? = null,
        initialSsa: SsaAnalysis? = null,
        optimization: SsaOptimizationResult? = null,
        cause: Exception,
    ): MethodAnalysisException {
        val exception = MethodAnalysisException(
            stage = stage,
            progress = MethodAnalysisProgress(normalization, frames, valueFlow, initialSsa, optimization),
            cause = cause,
        )
        TechnicalIrService.captureFailure(ownerInternalName, method, exception)
        return exception
    }
}

data class MethodAnalysis(
    val method: RawMethod,
    val graph: ControlFlowGraph,
    val frames: FrameAnalysis,
    val valueFlow: ValueFlowAnalysis,
    val initialSsa: SsaAnalysis,
    val optimization: SsaOptimizationResult,
    val expression: ExpressionAnalysis,
    val structuredControlFlow: StructuredControlFlowAnalysis,
    val normalization: LegacySubroutineNormalizationResult,
) {
    val ssa: SsaAnalysis
        get() = optimization.analysis
}

enum class MethodAnalysisStage {
    PREPARATION,
    FRAME,
    VALUE_FLOW,
    SSA,
    EXPRESSION,
    STRUCTURED_CONTROL_FLOW,
}

class MethodAnalysisException(
    val stage: MethodAnalysisStage,
    val progress: MethodAnalysisProgress,
    cause: Exception,
) : IllegalStateException("Method analysis failed during $stage: ${cause.message}", cause)

data class MethodAnalysisProgress(
    val normalization: LegacySubroutineNormalizationResult?,
    val frames: FrameAnalysis?,
    val valueFlow: ValueFlowAnalysis?,
    val initialSsa: SsaAnalysis?,
    val optimization: SsaOptimizationResult?,
)
