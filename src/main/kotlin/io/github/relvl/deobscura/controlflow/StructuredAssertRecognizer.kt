package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.*

/** Recognizes the canonical javac assertion guard before general conditional structuring. */
internal class StructuredAssertRecognizer {
    fun recognize(
        facts: ControlFlowFacts,
        branches: Map<BasicBlockId, ExpressionStatement.Branch>,
        expression: ExpressionAnalysis,
    ): StructuredAssertRecognition {
        val statementsByBlock = expression.statements.groupBy { facts.instructionToBlock.getOrNull(it.instructionIndex) }
        val regions = mutableListOf<StructuredRegion.Assert>()
        val consumedHeaders = linkedSetOf<BasicBlockId>()

        branches.forEach { (gateHeader, gateBranch) ->
            if (gateHeader in consumedHeaders) return@forEach
            val gateCondition = gateBranch.condition ?: return@forEach
            if (!isAssertionsDisabledGuard(gateCondition, expression)) return@forEach

            val gateEdges = facts.outgoing[gateHeader].orEmpty()
            val skipEdge = gateEdges.singleOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL } ?: return@forEach
            val checkEdge = gateEdges.singleOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH } ?: return@forEach
            val checkHeader = checkEdge.to
            if (checkHeader in consumedHeaders) return@forEach
            if (facts.predecessors[checkHeader].orEmpty().any { it != gateHeader }) return@forEach

            val checkBranch = branches[checkHeader] ?: return@forEach
            val checkCondition = checkBranch.condition ?: return@forEach
            val checkEdges = facts.outgoing[checkHeader].orEmpty()
            val conditional = checkEdges.singleOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL } ?: return@forEach
            val fallthrough = checkEdges.singleOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH } ?: return@forEach

            val continuation = skipEdge.to
            val failure = when {
                conditional.to == continuation && fallthrough.to != continuation -> fallthrough.to
                fallthrough.to == continuation && conditional.to != continuation -> conditional.to
                else -> return@forEach
            }
            if (failure !in facts.explicitTerminalBlocks) return@forEach
            if (facts.predecessors[failure].orEmpty().any { it != checkHeader }) return@forEach

            val assertion = assertionFailure(setOf(failure), statementsByBlock, expression) ?: return@forEach
            val sourceCondition = if (conditional.to == continuation) {
                StructuredCondition.Atomic(checkCondition)
            } else {
                StructuredCondition.Atomic(checkCondition.negated())
            }

            regions += StructuredRegion.Assert(
                header = gateHeader,
                condition = sourceCondition,
                message = assertion.message,
                checkHeader = checkHeader,
                failureBlocks = setOf(failure),
                continuation = continuation,
            )
            consumedHeaders += gateHeader
            consumedHeaders += checkHeader
        }

        return StructuredAssertRecognition(regions, consumedHeaders)
    }

    private fun isAssertionsDisabledGuard(condition: BranchCondition, expression: ExpressionAnalysis): Boolean {
        if (condition.operator != ComparisonOperator.NE || condition.right != BranchOperand.Zero) return false
        val field = expression.values[condition.left]?.node as? ExpressionNode.FieldRead ?: return false
        return field.receiver == null &&
            field.field.name == "\$assertionsDisabled" &&
            field.field.descriptor == "Z"
    }

    private fun assertionFailure(
        blocks: Set<BasicBlockId>,
        statementsByBlock: Map<BasicBlockId?, List<ExpressionStatement>>,
        expression: ExpressionAnalysis,
    ): AssertionFailure? {
        if (blocks.isEmpty()) return null
        val statements = blocks.flatMap { statementsByBlock[it].orEmpty() }
        val throwStatement = statements.filterIsInstance<ExpressionStatement.Throw>().singleOrNull() ?: return null
        if (statements.any { it !is ExpressionStatement.Throw && it !is ExpressionStatement.Branch }) return null
        val constructed = expression.values[throwStatement.value]?.node as? ExpressionNode.ConstructObject ?: return null
        if (constructed.internalName != "java/lang/AssertionError") return null
        if (constructed.constructor.name != "<init>" || constructed.arguments.size > 1) return null
        return AssertionFailure(constructed.arguments.singleOrNull())
    }

    private fun BranchCondition.negated(): BranchCondition = copy(operator = operator.negated())

    private fun ComparisonOperator.negated(): ComparisonOperator = when (this) {
        ComparisonOperator.EQ -> ComparisonOperator.NE
        ComparisonOperator.NE -> ComparisonOperator.EQ
        ComparisonOperator.LT -> ComparisonOperator.GE
        ComparisonOperator.LE -> ComparisonOperator.GT
        ComparisonOperator.GT -> ComparisonOperator.LE
        ComparisonOperator.GE -> ComparisonOperator.LT
    }

    private data class AssertionFailure(val message: ValueId?)
}

internal data class StructuredAssertRecognition(
    val regions: List<StructuredRegion.Assert>,
    val consumedHeaders: Set<BasicBlockId>,
)
