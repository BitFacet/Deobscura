package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaPhiLocation
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionMaterialization
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmType
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceBooleanCarrierRendererTest {
    @Test
    fun `uses semantic boolean phi directly in boolean context`() {
        val valueId = ValueId(1)
        val expression = ExpressionAnalysis(
            values = mapOf(
                valueId to ExpressionValue(
                    valueId,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    ExpressionNode.Phi(BasicBlockId(1), SsaPhiLocation.Local(0), emptyList()),
                ),
            ),
            statements = emptyList(),
            materialization = ExpressionMaterialization(booleanValues = setOf(valueId)),
        )

        assertEquals(
            "v1",
            SourceExpressionRenderer().renderValue(valueId, expression, JvmType.BooleanType),
        )
    }
}
