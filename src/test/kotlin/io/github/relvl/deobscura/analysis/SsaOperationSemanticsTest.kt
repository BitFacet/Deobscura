package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsaOperationSemanticsTest {
    private val semantics = SsaOperationSemantics()

    @Test
    fun `classifies explicitly safe arithmetic as discardable`() {
        assertTrue(semantics.canDiscardWhenResultUnused(operator("iadd")))
        assertTrue(semantics.canDiscardWhenResultUnused(operator("fdiv", JvmComputationalType.FLOAT)))
    }

    @Test
    fun `keeps integer division and unknown operators conservatively`() {
        assertFalse(semantics.canDiscardWhenResultUnused(operator("idiv")))
        assertFalse(semantics.canDiscardWhenResultUnused(operator("unknown")))
    }

    @Test
    fun `keeps control flow conservatively`() {
        val branch = RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(1))
        assertFalse(semantics.canDiscardWhenResultUnused(branch))
    }

    private fun operator(
        mnemonic: String,
        type: JvmComputationalType = JvmComputationalType.INT,
    ) = RawOperatorInstruction(JvmOpcode(mnemonic), type)
}
