package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizer
import io.github.relvl.deobscura.raw.RawMethod

/** Runs the complete low-level analysis pipeline for one method. */
class MethodAnalyzer(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val frameAnalyzer: FrameAnalyzer = FrameAnalyzer(),
    private val valueFlowAnalyzer: ValueFlowAnalyzer = ValueFlowAnalyzer(),
    private val ssaAnalyzer: SsaAnalyzer = SsaAnalyzer(),
    private val ssaOptimizer: SsaOptimizer = SsaOptimizer(),
    private val legacySubroutineNormalizer: LegacySubroutineNormalizer = LegacySubroutineNormalizer(),
) {
    fun analyze(ownerInternalName: String, method: RawMethod): MethodAnalysis {
        val code = requireNotNull(method.code) { "Cannot analyze a method without code." }

        val normalization = try {
            legacySubroutineNormalizer.normalize(code)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.PREPARATION, cause = exception)
        }

        val normalizedCode = normalization.code
        val normalizedMethod = if (normalizedCode === code) method else method.copy(code = normalizedCode)
        val graph = try {
            graphBuilder.build(normalizedCode)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.PREPARATION, normalization, cause = exception)
        }

        val frames = try {
            frameAnalyzer.analyze(ownerInternalName, normalizedMethod, graph)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.FRAME, normalization, cause = exception)
        }

        val valueFlow = try {
            valueFlowAnalyzer.analyze(graph, frames)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.VALUE_FLOW, normalization, frames, cause = exception)
        }

        val initialSsa = try {
            ssaAnalyzer.analyze(graph, valueFlow)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.SSA, normalization, frames, valueFlow, cause = exception)
        }

        val optimization = try {
            ssaOptimizer.optimize(graph, initialSsa)
        } catch (exception: Exception) {
            throw failure(MethodAnalysisStage.SSA, normalization, frames, valueFlow, initialSsa, exception)
        }

        return MethodAnalysis(
            method = normalizedMethod,
            graph = graph,
            frames = frames,
            valueFlow = valueFlow,
            initialSsa = initialSsa,
            optimization = optimization,
            normalization = normalization,
        )
    }

    private fun failure(
        stage: MethodAnalysisStage,
        normalization: LegacySubroutineNormalizationResult? = null,
        frames: FrameAnalysis? = null,
        valueFlow: ValueFlowAnalysis? = null,
        initialSsa: SsaAnalysis? = null,
        cause: Exception,
    ) = MethodAnalysisException(
        stage = stage,
        progress = MethodAnalysisProgress(normalization, frames, valueFlow, initialSsa),
        cause = cause,
    )
}

data class MethodAnalysis(
    val method: RawMethod,
    val graph: ControlFlowGraph,
    val frames: FrameAnalysis,
    val valueFlow: ValueFlowAnalysis,
    val initialSsa: SsaAnalysis,
    val optimization: SsaOptimizationResult,
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
)
