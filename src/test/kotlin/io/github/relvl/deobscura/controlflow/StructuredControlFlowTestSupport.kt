package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.*

internal fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc

internal fun expression(vararg branches: ExpressionStatement.Branch): ExpressionAnalysis {
    val condition = ValueId(0)
    return ExpressionAnalysis(
        values = mapOf(
            condition to ExpressionValue(
                condition,
                JvmValueType.of(JvmComputationalType.BOOLEAN),
                ExpressionNode.Root(ValueOrigin.Parameter(0)),
            ),
        ),
        statements = branches.toList(),
    )
}

internal fun switchExpression(
    instructionIndex: Int,
    vararg additionalStatements: ExpressionStatement,
): ExpressionAnalysis {
    val selector = ValueId(0)
    return ExpressionAnalysis(
        values = mapOf(
            selector to ExpressionValue(
                selector,
                JvmValueType.of(JvmComputationalType.INT),
                ExpressionNode.Root(ValueOrigin.Parameter(0)),
            ),
        ),
        statements = listOf(ExpressionStatement.Switch(instructionIndex, selector)) + additionalStatements,
    )
}

internal fun branch(instructionIndex: Int): ExpressionStatement.Branch = ExpressionStatement.Branch(
    instructionIndex,
    BranchCondition(ComparisonOperator.NE, ValueId(0), BranchOperand.Zero),
)

internal fun unconditionalBranch(instructionIndex: Int): ExpressionStatement.Branch =
    ExpressionStatement.Branch(instructionIndex, null)

internal fun blocks(count: Int): List<BasicBlock> = List(count) { index ->
    BasicBlock(BasicBlockId(index), index, index + 1, emptyList(), emptyList())
}

internal fun graph(blocks: List<BasicBlock>, edges: List<ControlFlowEdge>) = ControlFlowGraph(
    RawCode(null, null, null, List(blocks.size) { RawNopInstruction(JvmOpcode("nop")) }, emptyList(), emptyList(), emptyList()),
    blocks,
    edges,
    blocks.firstOrNull()?.id,
)

internal fun exceptionGraph(
    blockCount: Int,
    edges: List<ControlFlowEdge>,
    handlers: List<RawExceptionHandler>,
): ControlFlowGraph {
    val blocks = blocks(blockCount)
    val labels = List(blockCount + 1) { index -> RawLabel(RawLabelId(index), index, index) }
    return ControlFlowGraph(
        RawCode(
            null,
            null,
            null,
            List(blockCount) { RawNopInstruction(JvmOpcode("nop")) },
            labels,
            handlers,
            emptyList(),
        ),
        blocks,
        edges,
        blocks.firstOrNull()?.id,
    )
}

internal fun exceptionHandler(
    startInstruction: Int,
    endInstructionExclusive: Int,
    handlerInstruction: Int,
    catchType: String?,
) = RawExceptionHandler(
    tryStart = RawLabelId(startInstruction),
    tryEnd = RawLabelId(endInstructionExclusive),
    handler = RawLabelId(handlerInstruction),
    catchType = catchType,
)

internal fun exceptionEdge(from: BasicBlockId, to: BasicBlockId, catchType: String?) =
    ControlFlowEdge(from, to, ControlFlowEdgeKind.EXCEPTION, catchType = catchType)

internal fun switchEdge(from: BasicBlockId, to: BasicBlockId, value: Int?) =
    ControlFlowEdge(from, to, ControlFlowEdgeKind.SWITCH, switchValue = value)

internal fun edge(from: BasicBlockId, to: BasicBlockId, kind: ControlFlowEdgeKind) =
    ControlFlowEdge(from, to, kind)
