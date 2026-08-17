package io.github.relvl.deobscura.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisParallelismTest {
    @Test
    fun `uses ceiling of three quarters of available processors`() {
        assertEquals(1, analysisWorkerCount(1))
        assertEquals(2, analysisWorkerCount(2))
        assertEquals(3, analysisWorkerCount(4))
        assertEquals(5, analysisWorkerCount(6))
        assertEquals(6, analysisWorkerCount(8))
        assertEquals(9, analysisWorkerCount(12))
        assertEquals(12, analysisWorkerCount(16))
    }

    @Test
    fun `never creates fewer than one worker`() {
        assertEquals(1, analysisWorkerCount(0))
        assertEquals(1, analysisWorkerCount(-4))
    }
}
