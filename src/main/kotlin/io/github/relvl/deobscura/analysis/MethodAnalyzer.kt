package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalyzer
import io.github.relvl.deobscura.diagnostics.ir.MethodAnalysisTrace
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionBuilder
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawMethod
import io.github.relvl.deobscura.source.SourceStructureAnalysis
import io.github.relvl.deobscura.source.SourceStructureBuilder

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
    private val sourceStructureBuilder: SourceStructureBuilder = SourceStructureBuilder(),
) {
    fun analyze(ownerInternalName: String, method: RawMethod): MethodAnalysis =
        analyze(ownerInternalName, method, null)

    internal fun analyze(
        ownerInternalName: String,
        method: RawMethod,
        trace: MethodAnalysisTrace?,
    ): MethodAnalysis {
        val code = requireNotNull(method.code) { "Cannot analyze a method without code." }

        val normalization = try {
            legacySubroutineNormalizer.normalize(code)
        } catch (exception: Exception) {
            throw failure(trace, MethodAnalysisStage.PREPARATION, cause = exception)
        }

        val normalizedCode = normalization.code
        val normalizedMethod = if (normalizedCode === code) method else method.copy(code = normalizedCode)
        trace?.apply {
            this.normalizedMethod = normalizedMethod
            this.normalization = normalization
        }

        val graph = try {
            graphBuilder.build(normalizedCode)
        } catch (exception: Exception) {
            throw failure(trace, MethodAnalysisStage.PREPARATION, normalization, cause = exception)
        }
        trace?.graph = graph

        val frames = try {
            frameAnalyzer.analyze(ownerInternalName, normalizedMethod, graph)
        } catch (exception: Exception) {
            throw failure(trace, MethodAnalysisStage.FRAME, normalization, cause = exception)
        }
        trace?.frames = frames

        val valueFlow = try {
            valueFlowAnalyzer.analyze(graph, frames)
        } catch (exception: Exception) {
            throw failure(trace, MethodAnalysisStage.VALUE_FLOW, normalization, frames, cause = exception)
        }
        trace?.valueFlow = valueFlow

        val initialSsa = try {
            ssaAnalyzer.analyze(graph, valueFlow)
        } catch (exception: Exception) {
            throw failure(trace, MethodAnalysisStage.SSA, normalization, frames, valueFlow, cause = exception)
        }
        trace?.initialSsa = initialSsa

        val optimization = try {
            ssaOptimizer.optimize(graph, initialSsa)
        } catch (exception: Exception) {
            throw failure(
                trace, MethodAnalysisStage.SSA, normalization, frames, valueFlow, initialSsa, cause = exception,
            )
        }
        trace?.optimization = optimization

        val expression = try {
            expressionBuilder.build(optimization.analysis)
        } catch (exception: Exception) {
            throw failure(
                trace,
                MethodAnalysisStage.EXPRESSION,
                normalization,
                frames,
                valueFlow,
                initialSsa,
                optimization,
                exception,
            )
        }
        trace?.expression = expression

        val structuredControlFlow = try {
            structuredControlFlowAnalyzer.analyze(
                graph = graph,
                flow = optimization.controlFlow,
                expression = expression,
                legacySubroutineProvenance = normalization.provenance,
            )
        } catch (exception: Exception) {
            throw failure(
                trace,
                MethodAnalysisStage.STRUCTURED_CONTROL_FLOW,
                normalization,
                frames,
                valueFlow,
                initialSsa,
                optimization,
                exception,
            )
        }
        trace?.structuredControlFlow = structuredControlFlow

        val sourceStructure = try {
            sourceStructureBuilder.build(
                graph = graph,
                flow = optimization.controlFlow,
                structure = structuredControlFlow,
            )
        } catch (exception: Exception) {
            throw failure(
                trace,
                MethodAnalysisStage.SOURCE_STRUCTURE,
                normalization,
                frames,
                valueFlow,
                initialSsa,
                optimization,
                exception,
            )
        }
        trace?.sourceStructure = sourceStructure

        return MethodAnalysis(
            method = normalizedMethod,
            graph = graph,
            frames = frames,
            valueFlow = valueFlow,
            initialSsa = initialSsa,
            optimization = optimization,
            expression = expression,
            structuredControlFlow = structuredControlFlow,
            sourceStructure = sourceStructure,
            normalization = normalization,
        )
    }

    private fun failure(
        trace: MethodAnalysisTrace?,
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
        trace?.failure = exception
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
    val sourceStructure: SourceStructureAnalysis,
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
    SOURCE_STRUCTURE,
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
