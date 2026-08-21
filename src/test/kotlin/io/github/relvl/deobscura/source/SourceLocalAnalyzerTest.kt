package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredCondition
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.RawCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceLocalAnalyzerTest {
    @Test
    fun `projects constant materialization diamond into conditional phi`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val thenValue = ValueId(2)
        val elseValue = ValueId(3)
        val phiValue = ValueId(4)
        val condition = StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero))
        val region = StructuredRegion.If(
            header = header,
            condition = condition,
            thenEntry = thenBlock,
            thenBlocks = setOf(thenBlock),
            elseEntry = elseBlock,
            elseBlocks = setOf(elseBlock),
            continuation = continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(
                    conditionValue,
                    JvmValueType.Computational(JvmComputationalType.BOOLEAN),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
                thenValue to ExpressionValue(
                    thenValue,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(1)),
                    listOf(1),
                ),
                elseValue to ExpressionValue(
                    elseValue,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    ExpressionNode.Constant(constantDesc(0)),
                    listOf(2),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                    ),
                ),
            ),
            statements = listOf(
                ExpressionStatement.Branch(1, null),
                ExpressionStatement.Branch(2, null),
            ),
        )
        val ssa = SsaAnalysis(
            values = mapOf(
                conditionValue to SsaValueDefinition.Root(conditionValue, JvmValueType.Computational(JvmComputationalType.BOOLEAN), ValueOrigin.Parameter(0)),
                thenValue to SsaValueDefinition.Instruction(thenValue, JvmValueType.Computational(JvmComputationalType.INT), 1),
                elseValue to SsaValueDefinition.Instruction(elseValue, JvmValueType.Computational(JvmComputationalType.INT), 2),
                phiValue to SsaValueDefinition.Phi(
                    phiValue,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    continuation,
                    SsaPhiLocation.Local(1),
                    listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                ),
            ),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                thenValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Phi(phiValue, elseBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph = graph(header.value, thenBlock.value, elseBlock.value, continuation.value),
            ssa = ssa,
            expression = expression,
            structure = StructuredControlFlowAnalysis(
                regions = listOf(region),
                conditionalBranchCount = 1,
                switchCount = 0,
            ),
        )

        assertEquals(SourceConditionalValue(condition, thenValue, elseValue), analysis.conditionalValues[phiValue])
        assertEquals(setOf(thenValue, elseValue), analysis.suppressedDefinitions)
        assertEquals(setOf(header), analysis.consumedIfHeaders)
    }

    @Test
    fun `projects inherited value phi into conditional source assignment`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val condition = StructuredCondition.Atomic(BranchCondition(ComparisonOperator.EQ, conditionValue, BranchOperand.Zero))
        val region = StructuredRegion.If(
            header = header,
            condition = condition,
            thenEntry = thenBlock,
            thenBlocks = setOf(thenBlock),
            elseEntry = null,
            elseBlocks = emptySet(),
            continuation = continuation,
        )
        val longType = JvmValueType.Computational(JvmComputationalType.LONG)
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(
                    conditionValue,
                    JvmValueType.Computational(JvmComputationalType.INT),
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
                initialValue to ExpressionValue(
                    initialValue,
                    longType,
                    ExpressionNode.Constant(constantDesc(0L)),
                    listOf(0),
                ),
                assignedValue to ExpressionValue(
                    assignedValue,
                    longType,
                    ExpressionNode.Constant(constantDesc(5L)),
                    listOf(1),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    longType,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(SsaPhiInput(initialValue, header), SsaPhiInput(assignedValue, thenBlock)),
                    ),
                ),
            ),
            statements = listOf(ExpressionStatement.Branch(0, null)),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                initialValue to listOf(SsaValueUse.Phi(phiValue, header, 0)),
                assignedValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph = graph(header.value, thenBlock.value, continuation.value),
            ssa = ssa,
            expression = expression,
            structure = StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(SourceConditionalAssignment(initialValue, assignedValue), analysis.conditionalAssignments[phiValue])
        assertTrue(analysis.conditionalValues.isEmpty())
        assertTrue(analysis.consumedIfHeaders.isEmpty())
    }

    @Test
    fun `projects two arm local phi into source assignments`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val thenValue = ValueId(2)
        val elseValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            elseBlock,
            setOf(elseBlock),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                thenValue to ExpressionValue(thenValue, type, ExpressionNode.Constant(constantDesc(1)), listOf(1)),
                elseValue to ExpressionValue(elseValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                    ),
                ),
            ),
            statements = listOf(ExpressionStatement.Monitor(1, MonitorOperation.ENTER, conditionValue)),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                thenValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Phi(phiValue, elseBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(header.value, thenBlock.value, elseBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(SourceTwoArmAssignment(header, thenValue, elseValue), analysis.twoArmAssignments[phiValue])
        assertTrue(analysis.conditionalValues.isEmpty())
        assertTrue(analysis.consumedIfHeaders.isEmpty())
    }

    @Test
    fun `projects two arm stack phi into source assignments`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val thenValue = ValueId(2)
        val elseValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            elseBlock,
            setOf(elseBlock),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                thenValue to ExpressionValue(
                    thenValue,
                    JvmValueType.Computational(JvmComputationalType.BYTE),
                    ExpressionNode.Constant(constantDesc(1)),
                    listOf(1),
                ),
                elseValue to ExpressionValue(elseValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Stack(0),
                        listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                    ),
                ),
            ),
            statements = listOf(ExpressionStatement.Monitor(1, MonitorOperation.ENTER, conditionValue)),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                thenValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Phi(phiValue, elseBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(header.value, thenBlock.value, elseBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(SourceTwoArmAssignment(header, thenValue, elseValue), analysis.twoArmAssignments[phiValue])
        assertTrue(analysis.conditionalValues.isEmpty())
        assertTrue(analysis.consumedIfHeaders.isEmpty())
    }

    @Test
    fun `does not project inherited stack phi as source local`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.EQ, ValueId(1), BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(0)),
                assignedValue to ExpressionValue(assignedValue, type, ExpressionNode.Constant(constantDesc(1)), listOf(1)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Stack(0),
                        listOf(SsaPhiInput(initialValue, header), SsaPhiInput(assignedValue, thenBlock)),
                    ),
                ),
            ),
            statements = emptyList(),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                initialValue to listOf(SsaValueUse.Phi(phiValue, header, 0)),
                assignedValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(header.value, thenBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertTrue(analysis.conditionalAssignments.isEmpty())
    }

    @Test
    fun `keeps diamond when an arm has semantic work`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val thenValue = ValueId(2)
        val elseValue = ValueId(3)
        val phiValue = ValueId(4)
        val conditionValue = ValueId(1)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            elseBlock,
            setOf(elseBlock),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, JvmValueType.Computational(JvmComputationalType.BOOLEAN), ExpressionNode.Root(ValueOrigin.Parameter(0))),
                thenValue to ExpressionValue(thenValue, JvmValueType.Computational(JvmComputationalType.INT), ExpressionNode.Constant(constantDesc(1)), listOf(1)),
                elseValue to ExpressionValue(elseValue, JvmValueType.Computational(JvmComputationalType.INT), ExpressionNode.Constant(constantDesc(0)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue, JvmValueType.Computational(JvmComputationalType.INT), ExpressionNode.Phi(continuation, SsaPhiLocation.Local(1), listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)))
                ),
            ),
            statements = listOf(ExpressionStatement.Return(1, null)),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(), operations = emptyList(), phiNodes = emptyList(),
            uses = mapOf(
                thenValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Phi(phiValue, elseBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(header.value, thenBlock.value, elseBlock.value, continuation.value), ssa, expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertTrue(analysis.conditionalValues.isEmpty())
        assertTrue(analysis.consumedIfHeaders.isEmpty())
    }

    @Test
    fun `projects loop carried local phi into declaration and update`() {
        val initializerBlock = BasicBlockId(0)
        val header = BasicBlockId(1)
        val body = BasicBlockId(2)
        val exit = BasicBlockId(3)
        val initialValue = ValueId(1)
        val phiValue = ValueId(2)
        val updatedValue = ValueId(3)
        val conditionValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.While(
            header = header,
            condition = StructuredCondition.Atomic(BranchCondition(ComparisonOperator.LT, phiValue, BranchOperand.Value(conditionValue))),
            negateCondition = false,
            bodyEntry = body,
            bodyBlocks = setOf(body),
            exit = exit,
            latches = setOf(body),
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(initializerBlock.value)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        header,
                        SsaPhiLocation.Local(1),
                        listOf(SsaPhiInput(initialValue, initializerBlock), SsaPhiInput(updatedValue, body)),
                    ),
                ),
                updatedValue to ExpressionValue(
                    updatedValue,
                    type,
                    ExpressionNode.Increment(phiValue, 1),
                    listOf(body.value),
                ),
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
            ),
            statements = emptyList(),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                initialValue to listOf(SsaValueUse.Phi(phiValue, initializerBlock, 0)),
                updatedValue to listOf(SsaValueUse.Phi(phiValue, body, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(initializerBlock.value, header.value, body.value, exit.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(SourceLoopAssignment(header, initialValue, updatedValue), analysis.loopAssignments[phiValue])
    }

    @Test
    fun `groups nested loop phis from one local slot into source local family`() {
        val before = BasicBlockId(0)
        val outerHeader = BasicBlockId(1)
        val outerBody = BasicBlockId(2)
        val innerHeader = BasicBlockId(3)
        val updateBlock = BasicBlockId(4)
        val joinBlock = BasicBlockId(5)
        val exit = BasicBlockId(6)
        val initial = ValueId(1)
        val outerPhi = ValueId(2)
        val innerPhi = ValueId(3)
        val joinPhi = ValueId(4)
        val update = ValueId(5)
        val condition = ValueId(6)
        val type = JvmValueType.Reference(io.github.relvl.deobscura.raw.JvmReferenceType.Exact(io.github.relvl.deobscura.raw.JvmType.ObjectType("java/lang/Object")))
        val region = StructuredRegion.While(
            header = outerHeader,
            condition = StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, condition, BranchOperand.Zero)),
            negateCondition = false,
            bodyEntry = outerBody,
            bodyBlocks = setOf(outerBody, innerHeader, updateBlock, joinBlock),
            exit = exit,
            latches = setOf(joinBlock),
        )
        fun phi(id: ValueId, block: BasicBlockId, inputs: List<SsaPhiInput>) = ExpressionValue(
            id, type, ExpressionNode.Phi(block, SsaPhiLocation.Local(2), inputs),
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                initial to ExpressionValue(initial, type, ExpressionNode.Constant(constantDesc(java.lang.constant.ConstantDescs.NULL)), listOf(0)),
                outerPhi to phi(outerPhi, outerHeader, listOf(SsaPhiInput(initial, before), SsaPhiInput(innerPhi, joinBlock))),
                innerPhi to phi(innerPhi, innerHeader, listOf(SsaPhiInput(outerPhi, outerBody), SsaPhiInput(joinPhi, joinBlock))),
                joinPhi to phi(joinPhi, joinBlock, listOf(SsaPhiInput(innerPhi, innerHeader), SsaPhiInput(update, updateBlock))),
                update to ExpressionValue(update, type, ExpressionNode.Constant(constantDesc(java.lang.constant.ConstantDescs.NULL)), listOf(4)),
                condition to ExpressionValue(condition, JvmValueType.Computational(JvmComputationalType.INT), ExpressionNode.Root(ValueOrigin.Parameter(0))),
            ),
            statements = emptyList(),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = emptyMap(),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(0, 1, 2, 3, 4, 5, 6),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
            SsaControlFlowGraph(
                setOf(before, outerHeader, outerBody, innerHeader, updateBlock, joinBlock, exit),
                listOf(
                    ControlFlowEdge(before, outerHeader, ControlFlowEdgeKind.FALLTHROUGH),
                    ControlFlowEdge(outerHeader, outerBody, ControlFlowEdgeKind.FALLTHROUGH),
                    ControlFlowEdge(outerBody, innerHeader, ControlFlowEdgeKind.FALLTHROUGH),
                    ControlFlowEdge(innerHeader, joinBlock, ControlFlowEdgeKind.FALLTHROUGH),
                    ControlFlowEdge(updateBlock, joinBlock, ControlFlowEdgeKind.FALLTHROUGH),
                    ControlFlowEdge(joinBlock, outerHeader, ControlFlowEdgeKind.JUMP),
                ),
                before,
            ),
        )

        assertEquals(
            SourceLocalFamily(
                2,
                outerPhi,
                setOf(outerPhi, innerPhi, joinPhi),
                initial,
                setOf(SourceLocalFamilyAssignment(updateBlock, update)),
            ),
            analysis.localFamilies[outerPhi],
        )
    }

    private fun graph(vararg ids: Int): ControlFlowGraph = ControlFlowGraph(
        code = RawCode(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList()),
        blocks = ids.map { id -> BasicBlock(BasicBlockId(id), id, id + 1, emptyList(), emptyList()) },
        edges = emptyList(),
        entryBlock = ids.firstOrNull()?.let(::BasicBlockId),
    )

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc
}
