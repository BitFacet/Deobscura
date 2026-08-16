package io.github.relvl.deobscura.cfg

import io.github.relvl.deobscura.raw.RawImportResult

class ControlFlowDiagnostics(
    private val builder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
) {
    fun inspect(rawImport: RawImportResult): ControlFlowDiagnosticsResult {
        var methodCount = 0
        var blockCount = 0L
        var edgeCount = 0L
        var exceptionEdgeCount = 0L
        var unreachableBlockCount = 0L
        val warnings = mutableListOf<String>()

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                val code = method.code ?: continue
                methodCount++
                try {
                    val graph = builder.build(code)
                    blockCount += graph.blocks.size
                    edgeCount += graph.edges.size
                    exceptionEdgeCount += graph.edges.count { it.kind == ControlFlowEdgeKind.EXCEPTION }
                    unreachableBlockCount += builder.unreachableBlockCount(graph)
                } catch (exception: Exception) {
                    warnings += "Failed to build CFG for '${rawClass.internalName}.${method.name}${method.descriptor}': ${exception.message}."
                }
            }
        }

        return ControlFlowDiagnosticsResult(
            methodCount = methodCount,
            blockCount = blockCount,
            edgeCount = edgeCount,
            exceptionEdgeCount = exceptionEdgeCount,
            unreachableBlockCount = unreachableBlockCount,
            failureCount = warnings.size,
            warnings = warnings,
        )
    }
}

data class ControlFlowDiagnosticsResult(
    val methodCount: Int,
    val blockCount: Long,
    val edgeCount: Long,
    val exceptionEdgeCount: Long,
    val unreachableBlockCount: Long,
    val failureCount: Int,
    val warnings: List<String>,
)
