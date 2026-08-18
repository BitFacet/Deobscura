package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowDiagnostic
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowKind
import io.github.relvl.deobscura.controlflow.UnstructuredControlFlowReason
import kotlin.test.Test
import kotlin.test.assertEquals

class ExceptionResidualFamilyStatsTest {
    @Test
    fun `groups unsupported catch-all regions and keeps bounded representatives`() {
        val stats = ExceptionResidualFamilyStats(representativeLimit = 2)

        stats.record("a.A.one()V", diagnostic("modern family-a"))
        stats.record("b.B.two()V", diagnostic("modern family-a"))
        stats.record("c.C.three()V", diagnostic("modern family-a"))
        stats.record("d.D.four()V", diagnostic("modern family-b"))

        assertEquals(
            listOf(
                "modern family-a=3 [e.g. a.A.one()V, b.B.two()V]",
                "modern family-b=1 [e.g. d.D.four()V]",
            ),
            stats.summaries(),
        )
    }

    @Test
    fun `ignores diagnostics outside unsupported catch-all family`() {
        val stats = ExceptionResidualFamilyStats()
        stats.record(
            "a.A.ifShape()V",
            UnstructuredControlFlowDiagnostic(
                header = BasicBlockId(0),
                kind = UnstructuredControlFlowKind.CONDITIONAL,
                reason = UnstructuredControlFlowReason.UNSUPPORTED_SHAPE,
                exceptionResidualFamily = "must-not-appear",
            ),
        )

        assertEquals(emptyList(), stats.summaries())
    }

    private fun diagnostic(family: String) = UnstructuredControlFlowDiagnostic(
        header = BasicBlockId(0),
        kind = UnstructuredControlFlowKind.EXCEPTION,
        reason = UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED,
        protectedStartInstructionIndex = 0,
        protectedEndInstructionIndexExclusive = 1,
        exceptionResidualFamily = family,
    )
}
