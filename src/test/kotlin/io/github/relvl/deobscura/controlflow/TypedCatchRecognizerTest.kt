package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TypedCatchRecognizerTest {
    @Test
    fun `protected scope can allow a proven synthetic predecessor`() {
        val header = BasicBlockId(0)
        val protectedTail = BasicBlockId(1)
        val finallyCopy = BasicBlockId(2)
        val inside = edge(header, protectedTail, ControlFlowEdgeKind.FALLTHROUGH)
        val syntheticReturn = edge(finallyCopy, protectedTail, ControlFlowEdgeKind.JUMP)
        val facts = ControlFlowFacts(
            blocks = setOf(header, protectedTail, finallyCopy),
            normalEdges = listOf(inside, syntheticReturn),
            outgoing = mapOf(header to listOf(inside), finallyCopy to listOf(syntheticReturn)),
            incoming = mapOf(protectedTail to listOf(inside, syntheticReturn)),
            predecessors = mapOf(protectedTail to listOf(header, finallyCopy)),
            instructionToBlock = emptyArray(),
            originalBranches = emptyMap(),
            switches = emptyMap(),
            explicitTerminalBlocks = emptySet(),
            dominators = emptyMap(),
            postDominators = emptyMap(),
        )
        val scope = TypedCatchScopeTopology(
            header = header,
            protectedBlocks = setOf(header, protectedTail),
            handlersByEntry = emptyMap(),
            protectedRanges = listOf(StructuredProtectedRange(0, 2)),
        )
        val recognizer = TypedCatchRecognizer()
        fun analyze(allowSyntheticReturn: Boolean) = recognizer.analyze(
            scope = scope,
            facts = facts,
            rejectNormalBoundaryWithoutContinuation = false,
            allowLoopBackContinuation = false,
            collectCatch = { _, _, _ -> error("No handlers") },
            hasExternalCatchEntry = { _, _, _ -> false },
            supportsCatchExit = { _, _ -> true },
            hasExternalProtectedEntryCheck = if (allowSyntheticReturn) {
                { sourceHeader, blocks ->
                    hasExternalEntry(blocks, facts) { target, source ->
                        target == sourceHeader || source == finallyCopy
                    }
                }
            } else {
                null
            },
        )

        assertEquals(TypedCatchScopeFailure.PROTECTED_EXTERNAL_ENTRY, analyze(false).failure)
        assertNotNull(analyze(true).proof)
    }
}
