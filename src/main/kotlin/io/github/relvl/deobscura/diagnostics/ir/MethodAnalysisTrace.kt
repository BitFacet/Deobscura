package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.raw.RawMethod

/** Method-local diagnostic state collected without mutating global technical-IR storage. */
internal class MethodAnalysisTrace(
    val originalMethod: RawMethod,
) {
    var normalizedMethod: RawMethod? = null
    var normalization: LegacySubroutineNormalizationResult? = null
    var graph: ControlFlowGraph? = null
    var frames: FrameAnalysis? = null
    var valueFlow: ValueFlowAnalysis? = null
    var initialSsa: SsaAnalysis? = null
    var optimization: SsaOptimizationResult? = null
    var expression: ExpressionAnalysis? = null
    var structuredControlFlow: StructuredControlFlowAnalysis? = null
    var failure: MethodAnalysisException? = null

    fun completeAnalysis(): MethodAnalysis? {
        val method = normalizedMethod ?: return null
        val currentGraph = graph ?: return null
        val currentFrames = frames ?: return null
        val currentValueFlow = valueFlow ?: return null
        val currentInitialSsa = initialSsa ?: return null
        val currentOptimization = optimization ?: return null
        val currentExpression = expression ?: return null
        val currentStructuredControlFlow = structuredControlFlow ?: return null
        val currentNormalization = normalization ?: return null
        return MethodAnalysis(
            method = method,
            graph = currentGraph,
            frames = currentFrames,
            valueFlow = currentValueFlow,
            initialSsa = currentInitialSsa,
            optimization = currentOptimization,
            expression = currentExpression,
            structuredControlFlow = currentStructuredControlFlow,
            normalization = currentNormalization,
        )
    }
}
