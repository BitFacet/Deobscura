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
        val unreachableBlocks = mutableListOf<UnreachableBlockDiagnostic>()
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
                    builder.unreachableBlocks(graph).forEach { block ->
                        unreachableBlocks += UnreachableBlockDiagnostic(
                            methodName = "${rawClass.internalName}.${method.name}${method.descriptor}",
                            blockId = block.id.value,
                            startInstructionIndex = block.startInstructionIndex,
                            endInstructionIndexExclusive = block.endInstructionIndexExclusive,
                            sourceLines = sourceLines(graph, block),
                            incomingEdges = graph.edges
                                .asSequence()
                                .filter { it.to == block.id }
                                .map { UnreachableIncomingEdge(it.from.value, it.kind) }
                                .toList(),
                            opcodes = graph.instructions(block).map { it.opcode.mnemonic },
                        )
                    }
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
            unreachableBlockCount = unreachableBlocks.size.toLong(),
            unreachableBlocks = unreachableBlocks,
            failureCount = warnings.size,
            warnings = warnings,
        )
    }

    private fun sourceLines(graph: ControlFlowGraph, block: BasicBlock): IntRange? {
        val lines = graph.code.lineNumbers
            .asSequence()
            .filter { it.instructionIndex in block.startInstructionIndex until block.endInstructionIndexExclusive }
            .map { it.line }
            .toList()
        if (lines.isEmpty()) return null
        return lines.min()..lines.max()
    }
}

data class UnreachableBlockDiagnostic(
    val methodName: String,
    val blockId: Int,
    val startInstructionIndex: Int,
    val endInstructionIndexExclusive: Int,
    val sourceLines: IntRange?,
    val incomingEdges: List<UnreachableIncomingEdge>,
    val opcodes: List<String>,
)

data class UnreachableIncomingEdge(
    val fromBlockId: Int,
    val kind: ControlFlowEdgeKind,
)

data class ControlFlowDiagnosticsResult(
    val methodCount: Int,
    val blockCount: Long,
    val edgeCount: Long,
    val exceptionEdgeCount: Long,
    val unreachableBlockCount: Long,
    val unreachableBlocks: List<UnreachableBlockDiagnostic>,
    val failureCount: Int,
    val warnings: List<String>,
)
