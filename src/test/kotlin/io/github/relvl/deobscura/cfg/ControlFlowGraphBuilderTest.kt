package io.github.relvl.deobscura.cfg

import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlFlowGraphBuilderTest {
    private val builder = ControlFlowGraphBuilder()

    @Test
    fun `builds conditional and jump edges`() {
        val branchTarget = RawLabelId(0)
        val endTarget = RawLabelId(1)
        val code = code(
            instructions = listOf(
                nop(),
                RawBranchInstruction(JvmOpcode("ifeq"), branchTarget),
                nop(),
                RawBranchInstruction(JvmOpcode("goto"), endTarget),
                nop(),
                returnVoid(),
            ),
            labels = listOf(
                RawLabel(branchTarget, instructionIndex = 4, bytecodeOffset = 8),
                RawLabel(endTarget, instructionIndex = 5, bytecodeOffset = 9),
            ),
        )

        val graph = builder.build(code)

        assertEquals(4, graph.blocks.size)
        assertTrue(graph.edges.any { it.kind == ControlFlowEdgeKind.CONDITIONAL && it.to == BasicBlockId(2) })
        assertTrue(graph.edges.any { it.kind == ControlFlowEdgeKind.FALLTHROUGH && it.to == BasicBlockId(1) })
        assertTrue(graph.edges.any { it.kind == ControlFlowEdgeKind.JUMP && it.to == BasicBlockId(3) })
    }

    @Test
    fun `adds exception edges for protected blocks`() {
        val tryStart = RawLabelId(0)
        val tryEnd = RawLabelId(1)
        val handler = RawLabelId(2)
        val code = code(
            instructions = listOf(nop(), nop(), returnVoid()),
            labels = listOf(
                RawLabel(tryStart, instructionIndex = 0, bytecodeOffset = 0),
                RawLabel(tryEnd, instructionIndex = 2, bytecodeOffset = 2),
                RawLabel(handler, instructionIndex = 2, bytecodeOffset = 2),
            ),
            handlers = listOf(
                RawExceptionHandler(tryStart, tryEnd, handler, "java/lang/RuntimeException"),
            ),
        )

        val graph = builder.build(code)
        val exceptionEdges = graph.edges.filter { it.kind == ControlFlowEdgeKind.EXCEPTION }

        assertEquals(2, graph.blocks.size)
        assertEquals(1, exceptionEdges.size)
        assertEquals(BasicBlockId(0), exceptionEdges.single().from)
        assertEquals(BasicBlockId(1), exceptionEdges.single().to)
        assertEquals("java/lang/RuntimeException", exceptionEdges.single().catchType)
    }

    @Test
    fun `counts unreachable blocks`() {
        val target = RawLabelId(0)
        val graph = builder.build(
            code(
                instructions = listOf(
                    RawBranchInstruction(JvmOpcode("goto"), target),
                    nop(),
                    returnVoid(),
                ),
                labels = listOf(RawLabel(target, instructionIndex = 2, bytecodeOffset = 4)),
            ),
        )

        assertEquals(1, builder.unreachableBlockCount(graph))
        val unreachable = builder.unreachableBlocks(graph).single()
        assertEquals(BasicBlockId(1), unreachable.id)
        assertEquals(1, unreachable.startInstructionIndex)
        assertEquals(2, unreachable.endInstructionIndexExclusive)
    }

    private fun code(
        instructions: List<RawInstruction>,
        labels: List<RawLabel> = emptyList(),
        handlers: List<RawExceptionHandler> = emptyList(),
    ) = RawCode(
        maxStack = null,
        maxLocals = null,
        bytecodeLength = null,
        instructions = instructions,
        labels = labels,
        exceptionHandlers = handlers,
        lineNumbers = emptyList(),
    )

    private fun nop() = RawNopInstruction(JvmOpcode("nop"))

    private fun returnVoid() = RawReturnInstruction(JvmOpcode("return"), JvmComputationalType.VOID)
}
