package io.github.relvl.deobscura.cfg

import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.raw.RawImportResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ControlFlowDiagnostics(
    private val builder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
    private val logger: Logger = LoggerFactory.getLogger(ControlFlowDiagnostics::class.java),
) {
    fun inspect(rawImport: RawImportResult) {
        var methodCount = 0
        var blockCount = 0L
        var edgeCount = 0L
        var exceptionEdgeCount = 0L
        var unreachableBlockCount = 0L
        var failureCount = 0
        var detailedUnreachableCount = 0

        for (rawClass in rawImport.classes.values) {
            for (method in rawClass.methods) {
                val code = method.code ?: continue
                methodCount++
                val methodName = "${rawClass.internalName}.${method.name}${method.descriptor}"
                try {
                    val graph = builder.build(code)
                    blockCount += graph.blocks.size
                    edgeCount += graph.edges.size
                    exceptionEdgeCount += graph.edges.count { it.kind == ControlFlowEdgeKind.EXCEPTION }

                    for (block in builder.unreachableBlocks(graph)) {
                        unreachableBlockCount++
                        if (detailedUnreachableCount < MAX_UNREACHABLE_BLOCK_DETAILS) {
                            logUnreachableBlock(methodName, graph, block)
                            detailedUnreachableCount++
                        }
                    }
                } catch (exception: Exception) {
                    failureCount++
                    val ir = TechnicalIrService.methodHint(rawClass.internalName, method.name, method.descriptor)
                    logger.warn("Failed to build CFG for '{}': {}.{}", methodName, exception.message, ir)
                }
            }
        }

        logger.info(
            "Built CFG for {} method(s): {} basic blocks, {} edges ({} exception), {} unreachable blocks.",
            methodCount,
            blockCount,
            edgeCount,
            exceptionEdgeCount,
            unreachableBlockCount,
        )
        logger.info("CFG construction completed with {} failure(s).", failureCount)
        if (unreachableBlockCount > detailedUnreachableCount) {
            logger.debug(
                "Unreachable block details truncated: {} more block(s) not shown.",
                unreachableBlockCount - detailedUnreachableCount,
            )
        }
    }

    private fun logUnreachableBlock(methodName: String, graph: ControlFlowGraph, block: BasicBlock) {
        val incomingEdges = graph.edges.filter { it.to == block.id }
        val opcodes = graph.instructions(block).map { it.opcode.mnemonic }
        val lines = sourceLines(graph, block)
        logger.debug(
            "Unreachable block: {} block={}, instructions={}..{}, lines={}, incoming={}, opcodes={}",
            methodName,
            block.id.value,
            block.startInstructionIndex,
            block.endInstructionIndexExclusive - 1,
            lines?.let { if (it.first == it.last) it.first.toString() else "${it.first}..${it.last}" } ?: "?",
            incomingEdges.joinToString { "${it.from.value}:${it.kind}" }.ifEmpty { "none" },
            formatOpcodes(opcodes),
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

    private fun formatOpcodes(opcodes: List<String>): String {
        val shown = opcodes.take(MAX_OPCODES_IN_UNREACHABLE_BLOCK)
        return buildString {
            append(shown.joinToString(" "))
            if (opcodes.size > shown.size) append(" ... (+${opcodes.size - shown.size} more)")
        }
    }

    private companion object {
        const val MAX_UNREACHABLE_BLOCK_DETAILS = 32
        const val MAX_OPCODES_IN_UNREACHABLE_BLOCK = 16
    }
}
