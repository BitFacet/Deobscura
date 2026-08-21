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
    fun `projects unstructured boolean materialization diamond from cfg`() {
        val header = BasicBlockId(0)
        val untakenArm = BasicBlockId(1)
        val takenArm = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val trueValue = ValueId(2)
        val falseValue = ValueId(3)
        val phiValue = ValueId(4)
        val intType = JvmValueType.Computational(JvmComputationalType.INT)
        val condition = BranchCondition(ComparisonOperator.EQ, conditionValue, BranchOperand.Zero)
        val graph = ControlFlowGraph(
            code = RawCode(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList()),
            blocks = listOf(header, untakenArm, takenArm, continuation).map { id ->
                BasicBlock(id, id.value, id.value + 1, emptyList(), emptyList())
            },
            edges = listOf(
                ControlFlowEdge(header, takenArm, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(header, untakenArm, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(untakenArm, continuation, ControlFlowEdgeKind.JUMP),
                ControlFlowEdge(takenArm, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = header,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(
                    conditionValue,
                    intType,
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                    listOf(header.value),
                ),
                trueValue to ExpressionValue(
                    trueValue,
                    intType,
                    ExpressionNode.Constant(constantDesc(1)),
                    listOf(untakenArm.value),
                ),
                falseValue to ExpressionValue(
                    falseValue,
                    intType,
                    ExpressionNode.Constant(constantDesc(0)),
                    listOf(takenArm.value),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    intType,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Stack(0),
                        listOf(
                            SsaPhiInput(trueValue, untakenArm),
                            SsaPhiInput(falseValue, takenArm),
                        ),
                    ),
                ),
            ),
            statements = listOf(
                ExpressionStatement.Branch(header.value, condition),
                ExpressionStatement.Branch(untakenArm.value, null),
            ),
            materialization = ExpressionMaterialization(
                inlineValues = setOf(trueValue, falseValue),
                booleanValues = setOf(phiValue),
            ),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                trueValue to listOf(SsaValueUse.Phi(phiValue, untakenArm, 0)),
                falseValue to listOf(SsaValueUse.Phi(phiValue, takenArm, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph = graph,
            ssa = ssa,
            expression = expression,
            structure = StructuredControlFlowAnalysis(emptyList(), 0, 0),
        )

        assertEquals(
            SourceConditionalValue(StructuredCondition.Atomic(condition), falseValue, trueValue),
            analysis.conditionalValues[phiValue],
        )
        assertEquals(setOf(trueValue, falseValue), analysis.suppressedDefinitions)
        assertEquals(setOf(header), analysis.consumedIfHeaders)
    }

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
    fun `projects inline constant materialization diamond into conditional phi`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val thenValue = ValueId(2)
        val elseValue = ValueId(3)
        val phiValue = ValueId(4)
        val condition = StructuredCondition.Atomic(BranchCondition(ComparisonOperator.EQ, conditionValue, BranchOperand.Zero))
        val region = StructuredRegion.If(
            header = header,
            condition = condition,
            thenEntry = thenBlock,
            thenBlocks = setOf(thenBlock),
            elseEntry = elseBlock,
            elseBlocks = setOf(elseBlock),
            continuation = continuation,
        )
        val intType = JvmValueType.Computational(JvmComputationalType.INT)
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, intType, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                thenValue to ExpressionValue(thenValue, intType, ExpressionNode.Constant(constantDesc(0)), listOf(2)),
                elseValue to ExpressionValue(elseValue, intType, ExpressionNode.Constant(constantDesc(1)), listOf(1)),
                phiValue to ExpressionValue(
                    phiValue,
                    intType,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Stack(0),
                        listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                    ),
                ),
            ),
            statements = listOf(
                ExpressionStatement.Branch(1, null),
                ExpressionStatement.Branch(2, null),
            ),
            materialization = ExpressionMaterialization(inlineValues = setOf(thenValue, elseValue)),
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
            graph = graph(header.value, thenBlock.value, elseBlock.value, continuation.value),
            ssa = ssa,
            expression = expression,
            structure = StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(SourceConditionalValue(condition, thenValue, elseValue), analysis.conditionalValues[phiValue])
        assertEquals(setOf(thenValue, elseValue), analysis.suppressedDefinitions)
        assertEquals(setOf(header), analysis.consumedIfHeaders)
    }

    @Test
    fun `projects empty arm local copy phi into conditional value`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val conditionValue = ValueId(1)
        val inheritedValue = ValueId(2)
        val selectedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val condition = StructuredCondition.Atomic(
            BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero),
        )
        val region = StructuredRegion.If(
            header = header,
            condition = condition,
            thenEntry = thenBlock,
            thenBlocks = setOf(thenBlock),
            elseEntry = null,
            elseBlocks = emptySet(),
            continuation = continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(
                    conditionValue,
                    type,
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
                inheritedValue to ExpressionValue(
                    inheritedValue,
                    type,
                    ExpressionNode.Constant(constantDesc(10)),
                    listOf(header.value),
                ),
                selectedValue to ExpressionValue(
                    selectedValue,
                    type,
                    ExpressionNode.Constant(constantDesc(20)),
                    listOf(header.value),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(
                            SsaPhiInput(inheritedValue, header),
                            SsaPhiInput(selectedValue, thenBlock),
                        ),
                    ),
                ),
            ),
            statements = listOf(ExpressionStatement.Branch(header.value, null)),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                inheritedValue to listOf(SsaValueUse.Phi(phiValue, header, 0)),
                selectedValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph = graph(header.value, thenBlock.value, continuation.value),
            ssa = ssa,
            expression = expression,
            structure = StructuredControlFlowAnalysis(listOf(region), 1, 0),
        )

        assertEquals(
            SourceConditionalValue(condition, selectedValue, inheritedValue),
            analysis.conditionalValues[phiValue],
        )
        assertEquals(setOf(header), analysis.consumedIfHeaders)
        assertTrue(analysis.suppressedDefinitions.isEmpty())
        assertTrue(analysis.conditionalAssignments.isEmpty())
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
    fun `projects dominating inherited value into conditional source assignment`() {
        val before = BasicBlockId(0)
        val header = BasicBlockId(1)
        val thenBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(-1)), listOf(0)),
                assignedValue to ExpressionValue(assignedValue, type, ExpressionNode.Constant(constantDesc(7)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
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
        val controlFlow = SsaControlFlowGraph(
            blocks = setOf(before, header, thenBlock, continuation),
            edges = listOf(
                ControlFlowEdge(before, header, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(header, thenBlock, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(header, continuation, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(thenBlock, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = before,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(before.value, header.value, thenBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
            controlFlow,
        )

        assertEquals(SourceConditionalAssignment(initialValue, assignedValue), analysis.conditionalAssignments[phiValue])
    }

    @Test
    fun `keeps multiply used initial value and declares conditional local at header`() {
        val before = BasicBlockId(0)
        val header = BasicBlockId(1)
        val thenBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(-1)), listOf(0)),
                assignedValue to ExpressionValue(assignedValue, type, ExpressionNode.Constant(constantDesc(7)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
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
                initialValue to listOf(
                    SsaValueUse.Phi(phiValue, header, 0),
                    SsaValueUse.Operation(3, 0),
                ),
                assignedValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )
        val controlFlow = SsaControlFlowGraph(
            blocks = setOf(before, header, thenBlock, continuation),
            edges = listOf(
                ControlFlowEdge(before, header, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(header, thenBlock, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(header, continuation, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(thenBlock, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = before,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(before.value, header.value, thenBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
            controlFlow,
        )

        assertEquals(
            SourceConditionalAssignment(initialValue, assignedValue, declarationHeader = header),
            analysis.conditionalAssignments[phiValue],
        )
    }

    @Test
    fun `projects one arm assignment with root initial value`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Root(ValueOrigin.Parameter(1))),
                assignedValue to ExpressionValue(assignedValue, type, ExpressionNode.Constant(constantDesc(7)), listOf(1)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
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
                initialValue to listOf(
                    SsaValueUse.Phi(phiValue, header, 0),
                    SsaValueUse.Operation(1, 0),
                ),
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

        assertEquals(
            SourceConditionalAssignment(initialValue, assignedValue, declarationHeader = header),
            analysis.conditionalAssignments[phiValue],
        )
    }

    @Test
    fun `projects reconstructed conditional value assigned in one arm`() {
        val outerHeader = BasicBlockId(0)
        val innerHeader = BasicBlockId(1)
        val thenBlock = BasicBlockId(2)
        val elseBlock = BasicBlockId(3)
        val assignedBlock = BasicBlockId(4)
        val continuation = BasicBlockId(5)
        val outerConditionValue = ValueId(1)
        val innerConditionValue = ValueId(2)
        val initialValue = ValueId(3)
        val thenValue = ValueId(4)
        val elseValue = ValueId(5)
        val assignedValue = ValueId(6)
        val phiValue = ValueId(7)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val innerCondition = StructuredCondition.Atomic(
            BranchCondition(ComparisonOperator.NE, innerConditionValue, BranchOperand.Zero),
        )
        val innerRegion = StructuredRegion.If(
            header = innerHeader,
            condition = innerCondition,
            thenEntry = thenBlock,
            thenBlocks = setOf(thenBlock),
            elseEntry = elseBlock,
            elseBlocks = setOf(elseBlock),
            continuation = assignedBlock,
        )
        val outerRegion = StructuredRegion.If(
            header = outerHeader,
            condition = StructuredCondition.Atomic(
                BranchCondition(ComparisonOperator.NE, outerConditionValue, BranchOperand.Zero),
            ),
            thenEntry = innerHeader,
            thenBlocks = setOf(innerHeader, thenBlock, elseBlock, assignedBlock),
            elseEntry = null,
            elseBlocks = emptySet(),
            continuation = continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                outerConditionValue to ExpressionValue(outerConditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                innerConditionValue to ExpressionValue(innerConditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(1))),
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(0)),
                thenValue to ExpressionValue(thenValue, type, ExpressionNode.Constant(constantDesc(1)), listOf(2)),
                elseValue to ExpressionValue(elseValue, type, ExpressionNode.Constant(constantDesc(0)), listOf(3)),
                assignedValue to ExpressionValue(
                    assignedValue,
                    type,
                    ExpressionNode.Phi(
                        assignedBlock,
                        SsaPhiLocation.Stack(0),
                        listOf(SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
                    ),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(SsaPhiInput(initialValue, outerHeader), SsaPhiInput(assignedValue, assignedBlock)),
                    ),
                ),
            ),
            statements = listOf(
                ExpressionStatement.Branch(2, null),
                ExpressionStatement.Branch(3, null),
            ),
        )
        val ssa = SsaAnalysis(
            values = emptyMap(),
            operations = emptyList(),
            phiNodes = emptyList(),
            uses = mapOf(
                thenValue to listOf(SsaValueUse.Phi(assignedValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Phi(assignedValue, elseBlock, 1)),
                initialValue to listOf(SsaValueUse.Phi(phiValue, outerHeader, 0)),
                assignedValue to listOf(SsaValueUse.Phi(phiValue, assignedBlock, 1)),
            ),
            eliminatedLocalInstructionCount = 0,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(0, 1, 2, 3, 4, 5),
            ssa,
            expression,
            // Match the corpus ordering: the outer region is analyzed before the nested
            // materialization diamond that reconstructs assignedValue.
            StructuredControlFlowAnalysis(listOf(outerRegion, innerRegion), 2, 0),
        )

        assertEquals(SourceConditionalValue(innerCondition, thenValue, elseValue), analysis.conditionalValues[assignedValue])
        assertEquals(SourceConditionalAssignment(initialValue, assignedValue), analysis.conditionalAssignments[phiValue])
    }

    @Test
    fun `prefers one arm source local over boolean carrier materialization`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(
                    conditionValue,
                    type,
                    ExpressionNode.Root(ValueOrigin.Parameter(0)),
                ),
                initialValue to ExpressionValue(
                    initialValue,
                    type,
                    ExpressionNode.Constant(constantDesc(0)),
                    listOf(header.value),
                ),
                assignedValue to ExpressionValue(
                    assignedValue,
                    type,
                    ExpressionNode.Constant(constantDesc(1)),
                    listOf(thenBlock.value),
                ),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(
                            SsaPhiInput(initialValue, header),
                            SsaPhiInput(assignedValue, thenBlock),
                        ),
                    ),
                ),
            ),
            statements = emptyList(),
            materialization = ExpressionMaterialization(
                inlineValues = setOf(initialValue),
                booleanValues = setOf(phiValue),
            ),
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

        assertEquals(
            SourceConditionalAssignment(initialValue, assignedValue, declarationHeader = header),
            analysis.conditionalAssignments[phiValue],
        )
        assertTrue(phiValue in analysis.booleanLocals)
    }

    @Test
    fun `projects repeated inherited phi inputs from folded condition`() {
        val header = BasicBlockId(0)
        val foldedBlock = BasicBlockId(1)
        val thenBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val conditionValue = ValueId(1)
        val initialValue = ValueId(2)
        val assignedValue = ValueId(3)
        val phiValue = ValueId(4)
        val type = JvmValueType.Computational(JvmComputationalType.INT)
        val region = StructuredRegion.If(
            header,
            StructuredCondition.Atomic(BranchCondition(ComparisonOperator.NE, conditionValue, BranchOperand.Zero)),
            thenBlock,
            setOf(thenBlock),
            null,
            emptySet(),
            continuation,
        )
        val expression = ExpressionAnalysis(
            values = mapOf(
                conditionValue to ExpressionValue(conditionValue, type, ExpressionNode.Root(ValueOrigin.Parameter(0))),
                initialValue to ExpressionValue(initialValue, type, ExpressionNode.Constant(constantDesc(12)), listOf(0)),
                assignedValue to ExpressionValue(assignedValue, type, ExpressionNode.Constant(constantDesc(10)), listOf(2)),
                phiValue to ExpressionValue(
                    phiValue,
                    type,
                    ExpressionNode.Phi(
                        continuation,
                        SsaPhiLocation.Local(1),
                        listOf(
                            SsaPhiInput(initialValue, header),
                            SsaPhiInput(initialValue, foldedBlock),
                            SsaPhiInput(assignedValue, thenBlock),
                        ),
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
                initialValue to listOf(
                    SsaValueUse.Phi(phiValue, header, 0),
                    SsaValueUse.Phi(phiValue, foldedBlock, 1),
                    SsaValueUse.Operation(1, 0),
                ),
                assignedValue to listOf(SsaValueUse.Phi(phiValue, thenBlock, 2)),
            ),
            eliminatedLocalInstructionCount = 0,
        )
        val controlFlow = SsaControlFlowGraph(
            blocks = setOf(header, foldedBlock, thenBlock, continuation),
            edges = listOf(
                ControlFlowEdge(header, foldedBlock, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(header, continuation, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(foldedBlock, thenBlock, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(foldedBlock, continuation, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(thenBlock, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            ),
            entryBlock = header,
        )

        val analysis = SourceLocalAnalyzer().analyze(
            graph(header.value, foldedBlock.value, thenBlock.value, continuation.value),
            ssa,
            expression,
            StructuredControlFlowAnalysis(listOf(region), 1, 0),
            controlFlow,
        )

        assertEquals(
            SourceConditionalAssignment(initialValue, assignedValue, declarationHeader = header),
            analysis.conditionalAssignments[phiValue],
        )
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
    fun `projects inherited stack phi into conditional source assignment`() {
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

        assertEquals(SourceConditionalAssignment(initialValue, assignedValue), analysis.conditionalAssignments[phiValue])
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
