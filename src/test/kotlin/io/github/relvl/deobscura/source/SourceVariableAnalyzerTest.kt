package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.SsaPhiInput
import io.github.relvl.deobscura.analysis.SsaPhiLocation
import io.github.relvl.deobscura.analysis.SsaLocalAccess
import io.github.relvl.deobscura.analysis.SsaLocalAccessKind
import io.github.relvl.deobscura.analysis.SsaValueUse
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.BasicBlock
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmOpcode
import io.github.relvl.deobscura.raw.RawCode
import io.github.relvl.deobscura.raw.RawNopInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceVariableAnalyzerTest {
    private val intType = JvmValueType.Computational(JvmComputationalType.INT)

    @Test
    fun `lowers multi-use two-arm local phi without single-use requirement`() {
        val header = BasicBlockId(0)
        val thenBlock = BasicBlockId(1)
        val elseBlock = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val thenValue = ValueId(1)
        val elseValue = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(thenValue, 1),
            value(elseValue, 2),
            phi(phiValue, continuation, SsaPhiLocation.Local(3), SsaPhiInput(thenValue, thenBlock), SsaPhiInput(elseValue, elseBlock)),
        )
        val flow = flow(
            header,
            header to thenBlock,
            header to elseBlock,
            thenBlock to continuation,
            elseBlock to continuation,
        )
        val ssa = emptySsa(
            uses = mapOf(
                // The old shape-specific reconstruction rejected this because both values have a
                // non-phi use as well as the merge use. Generic out-of-SSA must not care.
                thenValue to listOf(SsaValueUse.Operation(10, 0), SsaValueUse.Phi(phiValue, thenBlock, 0)),
                elseValue to listOf(SsaValueUse.Operation(20, 0), SsaValueUse.Phi(phiValue, elseBlock, 1)),
            ),
            localAccesses = listOf(
                SsaLocalAccess(1, 3, SsaLocalAccessKind.WRITE, thenValue),
                SsaLocalAccess(2, 3, SsaLocalAccessKind.WRITE, elseValue),
            ),
        )

        val analysis = SourceVariableAnalyzer().analyze(rawGraph(flow, expression), ssa, expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertEquals(setOf(phiValue), analysis.loweredPhiValues)
        assertEquals(phiValue, analysis.valueBindings[phiValue])
        assertEquals(null, analysis.valueBindings[thenValue])
        assertEquals(null, analysis.valueBindings[elseValue])
        assertEquals(listOf(phiValue), analysis.declarationsBeforeBlock[header])
        assertEquals(
            setOf(
                SourceVariableAssignment(
                    phiValue, thenValue, continuation, SourceVariableAssignmentSite.Instruction(1),
                ),
                SourceVariableAssignment(
                    phiValue, elseValue, continuation, SourceVariableAssignmentSite.Instruction(2),
                ),
            ),
            analysis.assignments.toSet(),
        )
        assertTrue(analysis.assignmentsAfterBlock.isEmpty())
        assertEquals(SourceVariableOrigin.Local(3), analysis.variables.getValue(phiValue).origin)
        assertTrue(analysis.unresolvedNormalPhiValues.isEmpty())
    }

    @Test
    fun `lowers stack phi through a synthetic source variable`() {
        val header = BasicBlockId(0)
        val left = BasicBlockId(1)
        val right = BasicBlockId(2)
        val join = BasicBlockId(3)
        val leftValue = ValueId(1)
        val rightValue = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(leftValue, 1),
            value(rightValue, 2),
            phi(phiValue, join, SsaPhiLocation.Stack(0), SsaPhiInput(leftValue, left), SsaPhiInput(rightValue, right)),
        )
        val flow = flow(header, header to left, header to right, left to join, right to join)
        val analysis = SourceVariableAnalyzer().analyze(
            rawGraph(flow, expression),
            emptySsa(),
            expression,
            flow,
            basicSourceStructure(flow),
            SourceRewriteAnalysis(),
        )

        assertEquals(SourceVariableOrigin.SyntheticStack(0), analysis.variables.getValue(phiValue).origin)
        assertEquals(setOf(phiValue), analysis.loweredPhiValues)
        assertEquals(
            setOf(
                SourceVariableAssignment(phiValue, leftValue, join, SourceVariableAssignmentSite.BlockExit(left)),
                SourceVariableAssignment(phiValue, rightValue, join, SourceVariableAssignmentSite.BlockExit(right)),
            ),
            analysis.assignments.toSet(),
        )
    }

    @Test
    fun `coalesces connected local phi versions into one variable web`() {
        val entry = BasicBlockId(0)
        val firstLeft = BasicBlockId(1)
        val firstRight = BasicBlockId(2)
        val firstJoin = BasicBlockId(3)
        val secondRight = BasicBlockId(4)
        val secondJoin = BasicBlockId(5)
        val firstLeftValue = ValueId(1)
        val firstRightValue = ValueId(2)
        val firstPhi = ValueId(3)
        val secondValue = ValueId(4)
        val secondPhi = ValueId(5)
        val firstPhiNode = phi(
            firstPhi,
            firstJoin,
            SsaPhiLocation.Local(2),
            SsaPhiInput(firstLeftValue, firstLeft),
            SsaPhiInput(firstRightValue, firstRight),
        )
        val secondPhiNode = phi(
            secondPhi,
            secondJoin,
            SsaPhiLocation.Local(2),
            SsaPhiInput(firstPhi, firstJoin),
            SsaPhiInput(secondValue, secondRight),
        )
        val expression = expression(
            value(firstLeftValue, 1),
            value(firstRightValue, 2),
            firstPhiNode,
            value(secondValue, 4),
            secondPhiNode,
        )
        val ssa = emptySsa(
            uses = mapOf(firstPhi to listOf(SsaValueUse.Phi(secondPhi, firstJoin, 0))),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, firstLeft, firstRight, firstJoin, secondRight, secondJoin),
            edges = listOf(
                edge(entry, firstLeft),
                edge(entry, firstRight),
                edge(firstLeft, firstJoin),
                edge(firstRight, firstJoin),
                edge(firstJoin, secondJoin),
                edge(secondRight, secondJoin),
                // Keep the synthetic test connected while preserving a unique edge from secondRight.
                ControlFlowEdge(entry, secondRight, ControlFlowEdgeKind.CONDITIONAL),
            ),
            entryBlock = entry,
        )

        val analysis = SourceVariableAnalyzer().analyze(rawGraph(flow, expression), ssa, expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertEquals(1, analysis.variables.size)
        val variable = analysis.variables.values.single()
        assertEquals(setOf(firstPhi, secondPhi), variable.phiValues)
        assertEquals(variable.id, analysis.valueBindings[firstPhi])
        assertEquals(variable.id, analysis.valueBindings[secondPhi])
    }

    @Test
    fun `keeps edge-sensitive phi unresolved when predecessor has another successor`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val other = BasicBlockId(2)
        val join = BasicBlockId(3)
        val otherPredecessor = BasicBlockId(4)
        val left = ValueId(1)
        val right = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(left, 1),
            value(right, 4),
            phi(phiValue, join, SsaPhiLocation.Local(1), SsaPhiInput(left, predecessor), SsaPhiInput(right, otherPredecessor)),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, other, join, otherPredecessor),
            edges = listOf(
                edge(entry, predecessor),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(entry, otherPredecessor),
                edge(otherPredecessor, join),
            ),
            entryBlock = entry,
        )

        val analysis = SourceVariableAnalyzer().analyze(rawGraph(flow, expression), emptySsa(), expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertTrue(analysis.variables.isEmpty())
        assertEquals(setOf(phiValue), analysis.unresolvedNormalPhiValues)
    }

    @Test
    fun `lowers edge-sensitive phi when predecessor keeps explicit source control`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val other = BasicBlockId(2)
        val join = BasicBlockId(3)
        val otherPredecessor = BasicBlockId(4)
        val left = ValueId(1)
        val right = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(left, 1),
            value(right, 4),
            phi(phiValue, join, SsaPhiLocation.Stack(0), SsaPhiInput(left, predecessor), SsaPhiInput(right, otherPredecessor)),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, other, join, otherPredecessor),
            edges = listOf(
                edge(entry, predecessor),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(entry, otherPredecessor),
                edge(otherPredecessor, join),
            ),
            entryBlock = entry,
        )

        val analysis = SourceVariableAnalyzer().analyze(
            rawGraph(flow, expression),
            emptySsa(),
            expression,
            flow,
            sourceStructureWithFallback(flow, predecessor),
            SourceRewriteAnalysis(),
        )

        assertEquals(setOf(phiValue), analysis.loweredPhiValues)
        assertEquals(
            setOf(
                SourceVariableAssignment(phiValue, left, join, SourceVariableAssignmentSite.Edge(predecessor, join)),
                SourceVariableAssignment(phiValue, right, join, SourceVariableAssignmentSite.BlockExit(otherPredecessor)),
            ),
            analysis.assignments.toSet(),
        )
        assertEquals(1, analysis.assignmentsOnEdge[predecessor to join]?.size)
        assertTrue(analysis.unresolvedNormalPhiValues.isEmpty())
    }

    @Test
    fun `defers edge copy that reads another reconstructed source variable`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val other = BasicBlockId(2)
        val join = BasicBlockId(3)
        val otherPredecessor = BasicBlockId(4)
        val left = ValueId(1)
        val right = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(left, 1),
            value(right, 4),
            phi(phiValue, join, SsaPhiLocation.Stack(0), SsaPhiInput(left, predecessor), SsaPhiInput(right, otherPredecessor)),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, other, join, otherPredecessor),
            edges = listOf(
                edge(entry, predecessor),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(entry, otherPredecessor),
                edge(otherPredecessor, join),
            ),
            entryBlock = entry,
        )
        val rewriteTarget = ValueId(90)
        val rewrites = SourceRewriteAnalysis(
            loopPhiFamilies = mapOf(
                rewriteTarget to SourceLoopPhiFamily(
                    slot = 7,
                    target = rewriteTarget,
                    phiValues = setOf(ValueId(91)),
                    initialValue = left,
                    assignments = emptySet(),
                ),
            ),
        )

        val analysis = SourceVariableAnalyzer().analyze(
            rawGraph(flow, expression),
            emptySsa(),
            expression,
            flow,
            sourceStructureWithFallback(flow, predecessor),
            rewrites,
        )

        assertTrue(analysis.variables.isEmpty())
        assertEquals(setOf(phiValue), analysis.unresolvedNormalPhiValues)
    }

    @Test
    fun `store provenance lowers local phi even when merge edge is conditional`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val other = BasicBlockId(2)
        val join = BasicBlockId(3)
        val otherPredecessor = BasicBlockId(4)
        val left = ValueId(1)
        val right = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(left, 1),
            value(right, 4),
            phi(phiValue, join, SsaPhiLocation.Local(1), SsaPhiInput(left, predecessor), SsaPhiInput(right, otherPredecessor)),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, other, join, otherPredecessor),
            edges = listOf(
                edge(entry, predecessor),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(entry, otherPredecessor),
                edge(otherPredecessor, join),
            ),
            entryBlock = entry,
        )
        val ssa = emptySsa(
            localAccesses = listOf(
                SsaLocalAccess(1, 1, SsaLocalAccessKind.WRITE, left),
                SsaLocalAccess(4, 1, SsaLocalAccessKind.WRITE, right),
            ),
        )

        val analysis = SourceVariableAnalyzer().analyze(rawGraph(flow, expression), ssa, expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertEquals(setOf(phiValue), analysis.loweredPhiValues)
        assertEquals(null, analysis.valueBindings[left])
        assertEquals(null, analysis.valueBindings[right])
        assertEquals(
            setOf(
                SourceVariableAssignment(phiValue, left, join, SourceVariableAssignmentSite.Instruction(1)),
                SourceVariableAssignment(phiValue, right, join, SourceVariableAssignmentSite.Instruction(4)),
            ),
            analysis.assignments.toSet(),
        )
        assertTrue(analysis.assignmentsAfterBlock.isEmpty())
        assertTrue(analysis.unresolvedNormalPhiValues.isEmpty())
    }


    @Test
    fun `uses unique earlier local write when value flows through branching predecessor`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val other = BasicBlockId(2)
        val otherPredecessor = BasicBlockId(3)
        val join = BasicBlockId(4)
        val inherited = ValueId(1)
        val updated = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(inherited, 0),
            value(updated, 4),
            phi(
                phiValue,
                join,
                SsaPhiLocation.Local(3),
                SsaPhiInput(inherited, predecessor),
                SsaPhiInput(updated, otherPredecessor),
            ),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, other, otherPredecessor, join),
            edges = listOf(
                edge(entry, predecessor),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(entry, otherPredecessor),
                edge(otherPredecessor, join),
            ),
            entryBlock = entry,
        )
        val graph = graphWithInstructionBlocks(
            flow,
            entry to (0 until 1),
            predecessor to (1 until 2),
            other to (2 until 3),
            otherPredecessor to (4 until 5),
            join to (5 until 6),
        )
        val ssa = emptySsa(
            localAccesses = listOf(
                SsaLocalAccess(0, 3, SsaLocalAccessKind.WRITE, inherited),
                SsaLocalAccess(4, 3, SsaLocalAccessKind.WRITE, updated),
            ),
        )

        val analysis = SourceVariableAnalyzer().analyze(graph, ssa, expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertEquals(setOf(phiValue), analysis.loweredPhiValues)
        assertEquals(listOf(phiValue), analysis.declarationsBeforeBlock[entry])
        assertEquals(
            setOf(
                SourceVariableAssignment(phiValue, inherited, join, SourceVariableAssignmentSite.Instruction(0)),
                SourceVariableAssignment(phiValue, updated, join, SourceVariableAssignmentSite.Instruction(4)),
            ),
            analysis.assignments.toSet(),
        )
        assertTrue(analysis.unresolvedNormalPhiValues.isEmpty())
    }


    @Test
    fun `does not use an earlier local write that does not dominate the phi predecessor`() {
        val entry = BasicBlockId(0)
        val predecessor = BasicBlockId(1)
        val writeBlock = BasicBlockId(2)
        val other = BasicBlockId(3)
        val join = BasicBlockId(4)
        val inherited = ValueId(1)
        val updated = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(inherited, 2),
            value(updated, 3),
            phi(
                phiValue, join, SsaPhiLocation.Local(3),
                SsaPhiInput(inherited, predecessor),
                SsaPhiInput(updated, other),
            ),
        )
        val flow = SsaControlFlowGraph(
            blocks = setOf(entry, predecessor, writeBlock, other, join),
            edges = listOf(
                ControlFlowEdge(entry, predecessor, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(entry, writeBlock, ControlFlowEdgeKind.FALLTHROUGH),
                ControlFlowEdge(predecessor, join, ControlFlowEdgeKind.CONDITIONAL),
                ControlFlowEdge(predecessor, other, ControlFlowEdgeKind.FALLTHROUGH),
                edge(writeBlock, other),
                edge(other, join),
            ),
            entryBlock = entry,
        )
        val graph = graphWithInstructionBlocks(
            flow,
            entry to (0 until 1),
            predecessor to (1 until 2),
            writeBlock to (2 until 3),
            other to (3 until 4),
            join to (4 until 5),
        )
        val ssa = emptySsa(
            localAccesses = listOf(
                // This store creates the same ValueId, but only on the sibling path. It cannot
                // implement the inherited input that reaches the phi through `predecessor`.
                SsaLocalAccess(2, 3, SsaLocalAccessKind.WRITE, inherited),
                SsaLocalAccess(3, 3, SsaLocalAccessKind.WRITE, updated),
            ),
        )

        val analysis = SourceVariableAnalyzer().analyze(graph, ssa, expression, flow, basicSourceStructure(flow), SourceRewriteAnalysis())

        assertTrue(analysis.variables.isEmpty())
        assertEquals(setOf(phiValue), analysis.unresolvedNormalPhiValues)
    }

    @Test
    fun `keeps conservative exceptional phi separate from normal out of SSA`() {
        val entry = BasicBlockId(0)
        val handler = BasicBlockId(1)
        val left = ValueId(1)
        val right = ValueId(2)
        val phiValue = ValueId(3)
        val expression = expression(
            value(left, 0),
            value(right, 0),
            phi(phiValue, handler, SsaPhiLocation.Local(1), SsaPhiInput(left), SsaPhiInput(right)),
        )

        val flow = SsaControlFlowGraph(setOf(entry, handler), listOf(edge(entry, handler)), entry)
        val analysis = SourceVariableAnalyzer().analyze(
            rawGraph(flow, expression),
            emptySsa(),
            expression,
            flow,
            basicSourceStructure(flow),
            SourceRewriteAnalysis(),
        )

        assertTrue(analysis.variables.isEmpty())
        assertEquals(setOf(phiValue), analysis.exceptionalPhiValues)
        assertTrue(analysis.unresolvedNormalPhiValues.isEmpty())
    }

    private fun basicSourceStructure(flow: SsaControlFlowGraph): SourceStructureAnalysis {
        val nodes = flow.blocks.sortedBy { it.value }.map { block ->
            SourceNode.BasicBlock(block, SourceProvenance(setOf(block)))
        }
        return SourceStructureAnalysis(
            root = SourceBlock(flow.blocks, nodes),
            accountedBlocks = flow.blocks,
            consumptions = emptyList(),
        )
    }

    private fun sourceStructureWithFallback(
        flow: SsaControlFlowGraph,
        fallback: BasicBlockId,
    ): SourceStructureAnalysis {
        val nodes = flow.blocks.sortedBy { it.value }.map { block ->
            val provenance = SourceProvenance(setOf(block))
            if (block == fallback) {
                SourceNode.ProjectionFallback(block, SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK, provenance)
            } else {
                SourceNode.BasicBlock(block, provenance)
            }
        }
        return SourceStructureAnalysis(
            root = SourceBlock(flow.blocks, nodes),
            accountedBlocks = flow.blocks,
            consumptions = emptyList(),
        )
    }

    private fun expression(vararg values: ExpressionValue) = ExpressionAnalysis(values.associateBy { it.id }, emptyList())

    private fun value(id: ValueId, instruction: Int) = ExpressionValue(
        id = id,
        type = intType,
        node = ExpressionNode.Root(ValueOrigin.Instruction(instruction)),
        instructionIndices = listOf(instruction),
    )

    private fun phi(
        id: ValueId,
        block: BasicBlockId,
        location: SsaPhiLocation,
        vararg inputs: SsaPhiInput,
    ) = ExpressionValue(id, intType, ExpressionNode.Phi(block, location, inputs.toList()))

    private fun flow(entry: BasicBlockId, vararg edges: Pair<BasicBlockId, BasicBlockId>): SsaControlFlowGraph {
        val blocks = buildSet {
            add(entry)
            edges.forEach { (from, to) -> add(from); add(to) }
        }
        return SsaControlFlowGraph(blocks, edges.map { (from, to) -> edge(from, to) }, entry)
    }

    private fun edge(from: BasicBlockId, to: BasicBlockId) =
        ControlFlowEdge(from, to, ControlFlowEdgeKind.FALLTHROUGH)

    private fun emptySsa(
        uses: Map<ValueId, List<SsaValueUse>> = emptyMap(),
        localAccesses: List<SsaLocalAccess> = emptyList(),
    ) = SsaAnalysis(
        values = emptyMap(),
        operations = emptyList(),
        phiNodes = emptyList(),
        uses = uses,
        eliminatedLocalInstructionCount = 0,
        localAccesses = localAccesses,
    )


    private fun graphWithInstructionBlocks(
        flow: SsaControlFlowGraph,
        vararg ranges: Pair<BasicBlockId, IntRange>,
    ): ControlFlowGraph {
        val byBlock = ranges.toMap()
        val maxInstruction = byBlock.values.maxOfOrNull { it.last } ?: 0
        val blocks = flow.blocks.sortedBy { it.value }.map { block ->
            val range = byBlock[block] ?: (0 until 0)
            BasicBlock(
                id = block,
                startInstructionIndex = range.first,
                endInstructionIndexExclusive = if (range.isEmpty()) range.first else range.last + 1,
                predecessors = flow.edges.filter { it.to == block }.map { it.from }.distinct(),
                successors = flow.edges.filter { it.from == block }.map { it.to }.distinct(),
            )
        }
        return ControlFlowGraph(
            code = RawCode(
                0, 0, 0,
                List(maxInstruction + 1) { RawNopInstruction(JvmOpcode("nop")) },
                emptyList(), emptyList(), emptyList(),
            ),
            blocks = blocks,
            edges = flow.edges,
            entryBlock = flow.entryBlock,
        )
    }

    private fun rawGraph(flow: SsaControlFlowGraph, expression: ExpressionAnalysis): ControlFlowGraph {
        val valuePredecessors = expression.values.values.asSequence()
            .mapNotNull { it.node as? ExpressionNode.Phi }
            .flatMap { it.inputs.asSequence() }
            .mapNotNull { input -> input.predecessor?.let { input.value to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, blocks) -> blocks.distinct().singleOrNull() }
        val instructionsByBlock = linkedMapOf<BasicBlockId, MutableList<Int>>()
        expression.values.values.forEach { value ->
            val block = valuePredecessors[value.id] ?: return@forEach
            instructionsByBlock.getOrPut(block) { mutableListOf() }.addAll(value.instructionIndices)
        }
        val maxInstruction = expression.values.values.flatMap { it.instructionIndices }.maxOrNull() ?: 0
        val blocks = flow.blocks.sortedBy { it.value }.map { block ->
            val instructions = instructionsByBlock[block].orEmpty()
            val start = instructions.minOrNull() ?: 0
            val end = instructions.maxOrNull()?.plus(1) ?: start
            BasicBlock(
                id = block,
                startInstructionIndex = start,
                endInstructionIndexExclusive = end,
                predecessors = flow.edges.filter { it.to == block }.map { it.from }.distinct(),
                successors = flow.edges.filter { it.from == block }.map { it.to }.distinct(),
            )
        }
        return ControlFlowGraph(
            code = RawCode(0, 0, 0, List(maxInstruction + 1) { RawNopInstruction(JvmOpcode("nop")) }, emptyList(), emptyList(), emptyList()),
            blocks = blocks,
            edges = flow.edges,
            entryBlock = flow.entryBlock,
        )
    }
}
