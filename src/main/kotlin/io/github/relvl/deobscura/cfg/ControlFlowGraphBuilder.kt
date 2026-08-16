package io.github.relvl.deobscura.cfg

import io.github.relvl.deobscura.raw.RawBranchInstruction
import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawLabelId
import io.github.relvl.deobscura.raw.RawRetInstruction
import io.github.relvl.deobscura.raw.RawReturnInstruction
import io.github.relvl.deobscura.raw.RawSwitchInstruction
import io.github.relvl.deobscura.raw.RawThrowInstruction
import java.util.ArrayDeque

class ControlFlowGraphBuilder {
    fun build(code: RawCode): ControlFlowGraph {
        if (code.instructions.isEmpty()) {
            return ControlFlowGraph(code, emptyList(), emptyList(), null)
        }

        val labelPositions = code.labels.associate { it.id to it.instructionIndex }
        val leaders = sortedSetOf(0)

        fun addTarget(label: RawLabelId, context: String) {
            val target = requireNotNull(labelPositions[label]) { "Unknown label ${label.value} used by $context." }
            require(target in code.instructions.indices) {
                "$context targets instruction $target, outside 0..${code.instructions.lastIndex}."
            }
            leaders += target
        }

        code.instructions.forEachIndexed { index, instruction ->
            when (instruction) {
                is RawBranchInstruction -> {
                    addTarget(instruction.target, "branch at instruction $index")
                    addLeaderAfterTerminator(index, code.instructions.size, leaders)
                }

                is RawSwitchInstruction -> {
                    addTarget(instruction.defaultTarget, "switch default at instruction $index")
                    instruction.cases.forEach { case ->
                        addTarget(case.target, "switch case ${case.value} at instruction $index")
                    }
                    addLeaderAfterTerminator(index, code.instructions.size, leaders)
                }

                is RawReturnInstruction, is RawThrowInstruction, is RawRetInstruction ->
                    addLeaderAfterTerminator(index, code.instructions.size, leaders)

                else -> Unit
            }
        }

        code.exceptionHandlers.forEachIndexed { index, handler ->
            val tryStart = requireLabelPosition(labelPositions, handler.tryStart, "exception handler $index try start")
            val tryEnd = requireLabelPosition(labelPositions, handler.tryEnd, "exception handler $index try end")
            val handlerStart = requireLabelPosition(labelPositions, handler.handler, "exception handler $index target")

            require(tryStart in 0 until code.instructions.size) {
                "Exception handler $index try start $tryStart is outside the instruction stream."
            }
            require(tryEnd in 1..code.instructions.size) {
                "Exception handler $index try end $tryEnd is outside the instruction stream."
            }
            require(tryStart < tryEnd) {
                "Exception handler $index has empty or reversed protected range $tryStart..<$tryEnd."
            }
            require(handlerStart in code.instructions.indices) {
                "Exception handler $index target $handlerStart is outside the instruction stream."
            }

            leaders += tryStart
            if (tryEnd < code.instructions.size) leaders += tryEnd
            leaders += handlerStart
        }

        val leaderList = leaders.toList()
        val mutableBlocks = leaderList.mapIndexed { blockIndex, start ->
            val end = leaderList.getOrElse(blockIndex + 1) { code.instructions.size }
            MutableBlock(
                id = BasicBlockId(blockIndex),
                start = start,
                endExclusive = end,
            )
        }
        val blockByInstruction = IntArray(code.instructions.size)
        mutableBlocks.forEach { block ->
            for (instructionIndex in block.start until block.endExclusive) {
                blockByInstruction[instructionIndex] = block.id.value
            }
        }

        val edges = mutableListOf<ControlFlowEdge>()
        val edgeKeys = mutableSetOf<EdgeKey>()

        fun addEdge(edge: ControlFlowEdge) {
            val key = EdgeKey(edge.from, edge.to, edge.kind, edge.switchValue, edge.catchType)
            if (edgeKeys.add(key)) edges += edge
        }

        fun targetBlock(label: RawLabelId): BasicBlockId {
            val instructionIndex = requireNotNull(labelPositions[label]) { "Unknown label ${label.value}." }
            require(instructionIndex in code.instructions.indices) {
                "Label ${label.value} targets instruction $instructionIndex outside the instruction stream."
            }
            return BasicBlockId(blockByInstruction[instructionIndex])
        }

        mutableBlocks.forEachIndexed { blockIndex, block ->
            val lastInstruction = code.instructions[block.endExclusive - 1]
            when (lastInstruction) {
                is RawSwitchInstruction -> {
                    addEdge(
                        ControlFlowEdge(
                            from = block.id,
                            to = targetBlock(lastInstruction.defaultTarget),
                            kind = ControlFlowEdgeKind.SWITCH,
                        ),
                    )
                    lastInstruction.cases.forEach { case ->
                        addEdge(
                            ControlFlowEdge(
                                from = block.id,
                                to = targetBlock(case.target),
                                kind = ControlFlowEdgeKind.SWITCH,
                                switchValue = case.value,
                            ),
                        )
                    }
                }

                is RawBranchInstruction -> {
                    val mnemonic = lastInstruction.opcode.mnemonic
                    val directKind = if (mnemonic in DIRECT_JUMPS) {
                        ControlFlowEdgeKind.JUMP
                    } else {
                        ControlFlowEdgeKind.CONDITIONAL
                    }
                    addEdge(
                        ControlFlowEdge(
                            from = block.id,
                            to = targetBlock(lastInstruction.target),
                            kind = directKind,
                        ),
                    )
                    if (mnemonic !in NON_RETURNING_JUMPS) {
                        // JSR eventually returns to the following instruction through RET. Keeping the
                        // return site reachable is conservative until legacy subroutines are analyzed.
                        addFallthrough(blockIndex, mutableBlocks, block.id, ::addEdge)
                    }
                }

                is RawReturnInstruction, is RawThrowInstruction, is RawRetInstruction -> Unit

                else -> addFallthrough(blockIndex, mutableBlocks, block.id, ::addEdge)
            }
        }

        code.exceptionHandlers.forEach { handler ->
            val tryStart = requireLabelPosition(labelPositions, handler.tryStart, "exception try start")
            val tryEnd = requireLabelPosition(labelPositions, handler.tryEnd, "exception try end")
            val handlerBlock = targetBlock(handler.handler)

            mutableBlocks
                .asSequence()
                .filter { it.start < tryEnd && it.endExclusive > tryStart }
                .forEach { protectedBlock ->
                    addEdge(
                        ControlFlowEdge(
                            from = protectedBlock.id,
                            to = handlerBlock,
                            kind = ControlFlowEdgeKind.EXCEPTION,
                            catchType = handler.catchType,
                        ),
                    )
                }
        }

        val predecessors = mutableBlocks.associate { it.id to linkedSetOf<BasicBlockId>() }
        val successors = mutableBlocks.associate { it.id to linkedSetOf<BasicBlockId>() }
        edges.forEach { edge ->
            successors.getValue(edge.from) += edge.to
            predecessors.getValue(edge.to) += edge.from
        }

        val blocks = mutableBlocks.map { block ->
            BasicBlock(
                id = block.id,
                startInstructionIndex = block.start,
                endInstructionIndexExclusive = block.endExclusive,
                predecessors = predecessors.getValue(block.id).toList(),
                successors = successors.getValue(block.id).toList(),
            )
        }

        validateCoverage(blocks, code.instructions.size)
        return ControlFlowGraph(code, blocks, edges, BasicBlockId(0))
    }

    fun unreachableBlocks(graph: ControlFlowGraph): List<BasicBlock> {
        val entry = graph.entryBlock ?: return graph.blocks
        val reachable = mutableSetOf<BasicBlockId>()
        val queue = ArrayDeque<BasicBlockId>()
        queue.addLast(entry)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!reachable.add(current)) continue
            graph.block(current).successors.forEach(queue::addLast)
        }

        return graph.blocks.filter { it.id !in reachable }
    }

    fun unreachableBlockCount(graph: ControlFlowGraph): Int = unreachableBlocks(graph).size

    private fun addLeaderAfterTerminator(index: Int, instructionCount: Int, leaders: MutableSet<Int>) {
        if (index + 1 < instructionCount) leaders += index + 1
    }

    private fun addFallthrough(
        blockIndex: Int,
        blocks: List<MutableBlock>,
        from: BasicBlockId,
        addEdge: (ControlFlowEdge) -> Unit,
    ) {
        if (blockIndex + 1 >= blocks.size) return
        addEdge(
            ControlFlowEdge(
                from = from,
                to = blocks[blockIndex + 1].id,
                kind = ControlFlowEdgeKind.FALLTHROUGH,
            ),
        )
    }

    private fun requireLabelPosition(
        labelPositions: Map<RawLabelId, Int>,
        label: RawLabelId,
        context: String,
    ): Int = requireNotNull(labelPositions[label]) { "Unknown label ${label.value} used by $context." }

    private fun validateCoverage(blocks: List<BasicBlock>, instructionCount: Int) {
        var expectedStart = 0
        blocks.forEach { block ->
            require(block.startInstructionIndex == expectedStart) {
                "CFG block ${block.id.value} starts at ${block.startInstructionIndex}, expected $expectedStart."
            }
            require(block.endInstructionIndexExclusive > block.startInstructionIndex) {
                "CFG block ${block.id.value} is empty."
            }
            expectedStart = block.endInstructionIndexExclusive
        }
        require(expectedStart == instructionCount) {
            "CFG covers $expectedStart of $instructionCount instructions."
        }
    }

    private data class MutableBlock(
        val id: BasicBlockId,
        val start: Int,
        val endExclusive: Int,
    )

    private data class EdgeKey(
        val from: BasicBlockId,
        val to: BasicBlockId,
        val kind: ControlFlowEdgeKind,
        val switchValue: Int?,
        val catchType: String?,
    )

    private companion object {
        val DIRECT_JUMPS = setOf("goto", "goto_w", "jsr", "jsr_w")
        val NON_RETURNING_JUMPS = setOf("goto", "goto_w")
    }
}
