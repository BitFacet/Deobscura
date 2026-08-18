package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FinallyBodyMatcherTest {
    @Test
    fun `matches cleanup copies with alpha renamed local temporaries`() {
        val handler = BasicBlockId(0)
        val rethrow = BasicBlockId(1)
        val normal = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val instructions = listOf(
            local("astore", LocalOperation.STORE, 9),
            local("aload", LocalOperation.LOAD, 9),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(6)),
            local("astore", LocalOperation.STORE, 7),
            local("aload", LocalOperation.LOAD, 7),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(7)),
            local("aload", LocalOperation.LOAD, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val graph = graph(
            instructions,
            listOf(
                BasicBlock(handler, 0, 3, emptyList(), emptyList()),
                BasicBlock(normal, 3, 6, emptyList(), emptyList()),
                BasicBlock(rethrow, 6, 8, emptyList(), emptyList()),
                BasicBlock(continuation, 8, 8, emptyList(), emptyList()),
            ),
        )
        val facts = facts(
            listOf(
                ControlFlowEdge(handler, rethrow, ControlFlowEdgeKind.JUMP),
                ControlFlowEdge(normal, continuation, ControlFlowEdgeKind.JUMP),
            ),
        )

        val match = assertNotNull(
            FinallyBodyMatcher.match(
                graph = graph,
                handlerEntry = handler,
                handlerBlocks = setOf(handler),
                handlerExit = rethrow,
                handlerEntryInstructionOffset = 0,
                normalEntry = normal,
                facts = facts,
            ),
        )

        assertEquals(setOf(normal), match.blocks)
        assertEquals(continuation, match.continuation)
        assertEquals(listOf(3..5), match.instructionRanges)
    }

    @Test
    fun `rejects non bijective local renaming`() {
        val handler = BasicBlockId(0)
        val rethrow = BasicBlockId(1)
        val normal = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val instructions = listOf(
            local("aload", LocalOperation.LOAD, 9),
            local("aload", LocalOperation.LOAD, 10),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(5)),
            local("aload", LocalOperation.LOAD, 7),
            local("aload", LocalOperation.LOAD, 7),
            RawBranchInstruction(JvmOpcode("goto"), RawLabelId(6)),
            local("aload", LocalOperation.LOAD, 1),
            RawThrowInstruction(JvmOpcode("athrow")),
        )
        val graph = graph(
            instructions,
            listOf(
                BasicBlock(handler, 0, 3, emptyList(), emptyList()),
                BasicBlock(normal, 3, 6, emptyList(), emptyList()),
                BasicBlock(rethrow, 6, 8, emptyList(), emptyList()),
                BasicBlock(continuation, 8, 8, emptyList(), emptyList()),
            ),
        )
        val facts = facts(
            listOf(
                ControlFlowEdge(handler, rethrow, ControlFlowEdgeKind.JUMP),
                ControlFlowEdge(normal, continuation, ControlFlowEdgeKind.JUMP),
            ),
        )

        assertNull(
            FinallyBodyMatcher.match(
                graph = graph,
                handlerEntry = handler,
                handlerBlocks = setOf(handler),
                handlerExit = rethrow,
                handlerEntryInstructionOffset = 0,
                normalEntry = normal,
                facts = facts,
            ),
        )
    }

    private fun local(mnemonic: String, operation: LocalOperation, slot: Int) =
        RawLocalInstruction(JvmOpcode(mnemonic), operation, JvmComputationalType.REFERENCE, slot)

    private fun graph(instructions: List<RawInstruction>, blocks: List<BasicBlock>): ControlFlowGraph {
        val labels = List(instructions.size + 1) { index -> RawLabel(RawLabelId(index), index, index) }
        return ControlFlowGraph(
            code = RawCode(null, null, null, instructions, labels, emptyList(), emptyList()),
            blocks = blocks,
            edges = emptyList(),
            entryBlock = blocks.first().id,
        )
    }

    private fun facts(edges: List<ControlFlowEdge>): ControlFlowFacts {
        val outgoing = edges.groupBy { it.from }
        val incoming = edges.groupBy { it.to }
        return ControlFlowFacts(
            blocks = edges.flatMapTo(linkedSetOf()) { listOf(it.from, it.to) },
            normalEdges = edges,
            outgoing = outgoing,
            incoming = incoming,
            predecessors = incoming.mapValues { (_, values) -> values.map { it.from } },
            instructionToBlock = emptyArray(),
            originalBranches = emptyMap(),
            switches = emptyMap(),
            explicitTerminalBlocks = emptySet(),
            dominators = emptyMap(),
            postDominators = emptyMap(),
        )
    }
}
