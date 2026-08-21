package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmType
import io.github.relvl.deobscura.raw.formatTypeName

/** Source-like diagnostic rendering of Expression IR while control flow is still block-based. */
class ExpressionIrRenderer {
    fun render(analysis: MethodAnalysis): String = buildString {
        val expression = analysis.expression
        val finalFlow = analysis.optimization.controlFlow
        val instructionToBlock = instructionToBlock(analysis)
        val phisByBlock = expression.values.values.filter { it.node is ExpressionNode.Phi }.groupBy { (it.node as ExpressionNode.Phi).blockId }
        val eventsByBlock = buildMap<BasicBlockId?, MutableList<RenderEvent>> {
            expression.values.values.filter { it.instructionIndices.isNotEmpty() && it.id !in expression.materialization.inlineValues }
                .forEach { value -> // A folded constructor is source-level materialized at invokespecial <init>,
                    // after its argument values have been evaluated.
                    val index = value.instructionIndices.last()
                    getOrPut(instructionToBlock[index]) { mutableListOf() } += RenderEvent.Value(index, value)
                }
            expression.statements.forEach { statement ->
                getOrPut(instructionToBlock[statement.instructionIndex]) { mutableListOf() } += RenderEvent.Statement(statement.instructionIndex, statement)
            }
        }
        val outgoingByBlock = finalFlow.edges.groupBy { it.from }

        for (block in finalFlow.blocks.sortedBy { it.value }) {
            appendLine("    B${block.value}:")
            phisByBlock[block].orEmpty().sortedBy { it.id.value }.forEach { value ->
                appendLine("      ${renderDefinition(value, expression)}")
            }
            eventsByBlock[block].orEmpty().sortedWith(compareBy<RenderEvent> { it.instructionIndex }.thenBy { it.order }).forEach { event ->
                val rendered = when (event) {
                    is RenderEvent.Value -> if (event.value.id in expression.materialization.discardedResultValues) {
                        renderNode(event.value.node, expression)
                    } else {
                        renderDefinition(event.value, expression)
                    }

                    is RenderEvent.Statement -> renderStatement(event.statement, outgoingByBlock[block].orEmpty(), expression)
                }
                appendLine("      $rendered")
            }
        }

        val roots = expression.values.values.filter { it.node is ExpressionNode.Root }.sortedBy { it.id.value }
        if (roots.isNotEmpty()) {
            appendLine("    roots:")
            roots.forEach { value -> appendLine("      v${value.id.value}:${formatValueType(value.type)} = ${renderNode(value.node, expression)}") }
        }
    }

    private fun renderDefinition(value: ExpressionValue, expression: ExpressionAnalysis): String {
        val renderedType = if (value.id in expression.materialization.booleanValues) "boolean" else formatValueType(value.type)
        val node = value.node
        val renderedNode = if (node is ExpressionNode.Phi && value.id in expression.materialization.booleanValues) {
            renderBooleanPhi(node, value.id, expression)
        } else {
            renderNode(node, expression)
        }
        return "v${value.id.value}:$renderedType = $renderedNode" + value.instructionIndices.takeIf { it.size > 1 }?.joinToString(prefix = "  // @", separator = ",@") { it.toString() }.orEmpty()
    }

    private fun renderBooleanPhi(node: ExpressionNode.Phi, output: ValueId, expression: ExpressionAnalysis): String =
        "phi ${formatPhiLocation(node.location)} " + node.inputs.joinToString(prefix = "[", postfix = "]") { input ->
            val predecessor = input.predecessor?.let { "B${it.value}=" } ?: "origin="
            val rendered = when {
                input.value == output -> "v${output.value}"
                expression.values[input.value]?.node is ExpressionNode.Constant -> {
                    val constant = (expression.value(input.value).node as ExpressionNode.Constant).value
                    when {
                        constant.equals(0) -> "false"
                        constant.equals(1) -> "true"
                        else -> ref(input.value, expression)
                    }
                }

                else -> ref(input.value, expression)
            }
            predecessor + rendered
        }

    private fun renderNode(node: ExpressionNode, expression: ExpressionAnalysis): String = when (node) {
        is ExpressionNode.Root -> renderRoot(node.origin)
        is ExpressionNode.Phi -> "phi ${formatPhiLocation(node.location)} " + node.inputs.joinToString(prefix = "[", postfix = "]") { input ->
            input.predecessor?.let { "B${it.value}=v${input.value.value}" } ?: "origin=v${input.value.value}"
        }

        is ExpressionNode.Constant -> formatConstant(node.value)
        is ExpressionNode.Unary -> renderInlineNode(node, expression, precedence(node))
        is ExpressionNode.Binary -> renderInlineNode(node, expression, precedence(node))
        is ExpressionNode.Increment -> renderInlineNode(node, expression, precedence(node))

        is ExpressionNode.ThreeWayCompare -> buildString {
            append("cmp(${ref(node.left, expression)}, ${ref(node.right, expression)})")
            node.nanResult?.let { append(" nan=$it") }
        }

        is ExpressionNode.Conversion -> renderInlineNode(node, expression, precedence(node))
        is ExpressionNode.FieldRead -> node.receiver?.let { "${ref(it, expression)}.${node.field.name}" } ?: "${sourceName(node.field.ownerInternalName)}.${node.field.name}"

        is ExpressionNode.ArrayRead -> "${ref(node.array, expression)}[${ref(node.index, expression)}]"
        is ExpressionNode.ArrayLength -> "${ref(node.array, expression)}.length"
        is ExpressionNode.Call -> renderCall(node.method, node.receiver, node.arguments, expression)
        is ExpressionNode.DynamicCall -> "invokedynamic ${node.callSite.name}(${node.arguments.joinToString { ref(it, expression) }})"
        is ExpressionNode.NewObject -> "new ${sourceName(node.internalName)} /* uninitialized */"
        is ExpressionNode.ConstructObject -> "new ${sourceName(node.internalName)}(${node.arguments.joinToString { ref(it, expression) }})"
        is ExpressionNode.NewArray -> renderNewArray(node, expression)
        is ExpressionNode.Cast -> "(${formatJvmType(node.targetType)}) ${ref(node.operand, expression)}"
        is ExpressionNode.InstanceOf -> "${ref(node.operand, expression)} instanceof ${formatJvmType(node.targetType)}"
        is ExpressionNode.Raw -> "${node.opcode}${node.inputs.joinToString(prefix = "(", postfix = ")") { ref(it, expression) }}"
    }

    private fun renderStatement(
        statement: ExpressionStatement,
        outgoing: List<io.github.relvl.deobscura.cfg.ControlFlowEdge>,
        expression: ExpressionAnalysis,
    ): String = when (statement) {
        is ExpressionStatement.FieldWrite -> {
            val target = statement.receiver?.let { "${ref(it, expression)}.${statement.field.name}" } ?: "${sourceName(statement.field.ownerInternalName)}.${statement.field.name}"
            "$target = ${ref(statement.value, expression)}"
        }

        is ExpressionStatement.ArrayWrite -> "${ref(statement.array, expression)}[${ref(statement.index, expression)}] = ${ref(statement.value, expression)}"
        is ExpressionStatement.Call -> renderCall(statement.method, statement.receiver, statement.arguments, expression)
        is ExpressionStatement.DynamicCall -> "invokedynamic ${statement.callSite.name}(${statement.arguments.joinToString { ref(it, expression) }})"

        is ExpressionStatement.Return -> statement.value?.let { "return ${ref(it, expression)}" } ?: "return"
        is ExpressionStatement.Throw -> "throw ${ref(statement.value, expression)}"
        is ExpressionStatement.Monitor -> "${if (statement.operation == MonitorOperation.ENTER) "monitor-enter" else "monitor-exit"} ${ref(statement.value, expression)}"
        is ExpressionStatement.Branch -> renderBranch(statement, outgoing, expression)
        is ExpressionStatement.Switch -> renderSwitch(statement.selector, outgoing, expression)
        is ExpressionStatement.Raw -> "${statement.opcode}${statement.inputs.joinToString(prefix = "(", postfix = ")") { ref(it, expression) }}"
    }

    private fun renderBranch(
        statement: ExpressionStatement.Branch,
        outgoing: List<io.github.relvl.deobscura.cfg.ControlFlowEdge>,
        expression: ExpressionAnalysis,
    ): String {
        val condition = statement.condition ?: return "branch ${outgoingTargets(outgoing)}"
        val taken = outgoing.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to
        val fallthrough = outgoing.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH }?.to
        val suffix = when {
            taken != null && fallthrough != null -> " -> B${taken.value} else B${fallthrough.value}"
            taken != null -> " -> B${taken.value}"
            else -> " ${outgoingTargets(outgoing)}"
        }
        return "if (${renderCondition(condition, expression)})$suffix"
    }

    private fun renderSwitch(
        selector: ValueId,
        outgoing: List<io.github.relvl.deobscura.cfg.ControlFlowEdge>,
        expression: ExpressionAnalysis,
    ): String {
        val cases = outgoing.filter { it.kind == ControlFlowEdgeKind.SWITCH }.joinToString { edge -> edge.switchValue?.let { "$it:B${edge.to.value}" } ?: "default:B${edge.to.value}" }
        return "switch (${ref(selector, expression)}) [$cases]"
    }

    internal fun renderCondition(
        condition: BranchCondition,
        expression: ExpressionAnalysis,
        negate: Boolean = false,
    ): String {
        val effective = if (negate) condition.copy(operator = condition.operator.negated()) else condition
        val left = expression.values[effective.left]
        val isBoolean = effective.left in expression.materialization.booleanValues || left?.type?.isBoolean == true
        if (isBoolean && effective.right == BranchOperand.Zero) {
            return when (effective.operator) {
                ComparisonOperator.EQ -> "!${parenthesizeBoolean(effective.left, expression)}"
                ComparisonOperator.NE -> renderValue(effective.left, expression, PRECEDENCE_CONDITIONAL)
                else -> "${ref(effective.left, expression)} ${effective.operator.symbol} 0"
            }
        }
        val comparisonPrecedence = when (effective.operator) {
            ComparisonOperator.EQ, ComparisonOperator.NE -> PRECEDENCE_EQUALITY
            else -> PRECEDENCE_RELATIONAL
        }
        val renderedLeft = renderValue(effective.left, expression, comparisonPrecedence)
        return "$renderedLeft ${effective.operator.symbol} " + when (val right = effective.right) {
            is BranchOperand.Value -> renderValue(right.value, expression, comparisonPrecedence + 1)
            BranchOperand.Zero -> "0"
            BranchOperand.Null -> "null"
        }
    }

    private fun ComparisonOperator.negated(): ComparisonOperator = when (this) {
        ComparisonOperator.EQ -> ComparisonOperator.NE
        ComparisonOperator.NE -> ComparisonOperator.EQ
        ComparisonOperator.LT -> ComparisonOperator.GE
        ComparisonOperator.LE -> ComparisonOperator.GT
        ComparisonOperator.GT -> ComparisonOperator.LE
        ComparisonOperator.GE -> ComparisonOperator.LT
    }

    private fun renderCall(
        method: MethodSymbol,
        receiver: ValueId?,
        arguments: List<ValueId>,
        expression: ExpressionAnalysis,
    ): String {
        val target = receiver?.let { ref(it, expression) } ?: sourceName(method.ownerInternalName)
        return "$target.${method.name}(${arguments.joinToString { ref(it, expression) }})"
    }

    private fun renderNewArray(node: ExpressionNode.NewArray, expression: ExpressionAnalysis): String {
        var component: JvmType = node.arrayType
        repeat(node.dimensions.size) {
            component = (component as? JvmType.ArrayType)?.componentType ?: component
        }
        val base = formatJvmType(component)
        val dimensions = node.dimensions.joinToString("") { "[${ref(it, expression)}]" }
        val remainingDepth = arrayDepth(node.arrayType) - node.dimensions.size
        return "new $base$dimensions${"[]".repeat(remainingDepth.coerceAtLeast(0))}"
    }

    private fun arrayDepth(type: JvmType.ArrayType): Int {
        var depth = 1
        var current: JvmType = type.componentType
        while (current is JvmType.ArrayType) {
            depth++
            current = current.componentType
        }
        return depth
    }

    private fun renderRoot(origin: ValueOrigin): String = when (origin) {
        is ValueOrigin.This -> "this"
        is ValueOrigin.Parameter -> "parameter[${origin.index}]"
        is ValueOrigin.ExceptionHandler -> "exception@${origin.handlerInstructionIndex}"
        is ValueOrigin.ReturnAddress -> "return-address@${origin.returnInstructionIndex}"
        is ValueOrigin.Instruction -> "instruction@${origin.index}"
    }

    private fun formatConstant(value: Any): String = when (value) {
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        is Char -> "'$value'"
        else -> value.toString()
    }

    private fun ref(id: ValueId, expression: ExpressionAnalysis): String = renderValue(id, expression, PRECEDENCE_LOWEST)

    private fun renderValue(id: ValueId, expression: ExpressionAnalysis, parentPrecedence: Int): String {
        val value = expression.values[id] ?: return "v${id.value}"
        val root = value.node as? ExpressionNode.Root
        if (root != null) return renderRoot(root.origin)
        if (id !in expression.materialization.inlineValues) return "v${id.value}"

        val precedence = precedence(value.node)
        val rendered = renderInlineNode(value.node, expression, precedence)
        return if (precedence < parentPrecedence) "($rendered)" else rendered
    }

    private fun renderInlineNode(node: ExpressionNode, expression: ExpressionAnalysis, ownPrecedence: Int): String = when (node) {
        is ExpressionNode.Constant -> formatConstant(node.value)
        is ExpressionNode.Unary -> "${node.operator.symbol}${renderValue(node.operand, expression, PRECEDENCE_UNARY)}"
        is ExpressionNode.Binary -> {
            val left = renderValue(node.left, expression, ownPrecedence)
            val right = renderValue(node.right, expression, ownPrecedence + 1)
            "$left ${node.operator.symbol} $right"
        }

        is ExpressionNode.Increment -> {
            val operand = renderValue(node.operand, expression, PRECEDENCE_ADDITIVE)
            if (node.amount >= 0) "$operand + ${node.amount}" else "$operand - ${-node.amount}"
        }

        is ExpressionNode.Conversion -> "(${formatValueType(node.targetType)}) ${renderValue(node.operand, expression, PRECEDENCE_UNARY)}"
        else -> renderNode(node, expression)
    }

    private fun precedence(node: ExpressionNode): Int = when (node) {
        is ExpressionNode.Binary -> when (node.operator) {
            BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE, BinaryOperator.REMAINDER -> PRECEDENCE_MULTIPLICATIVE
            BinaryOperator.ADD, BinaryOperator.SUBTRACT -> PRECEDENCE_ADDITIVE
            BinaryOperator.SHIFT_LEFT, BinaryOperator.SHIFT_RIGHT, BinaryOperator.UNSIGNED_SHIFT_RIGHT -> PRECEDENCE_SHIFT
            BinaryOperator.BIT_AND -> PRECEDENCE_BIT_AND
            BinaryOperator.BIT_XOR -> PRECEDENCE_BIT_XOR
            BinaryOperator.BIT_OR -> PRECEDENCE_BIT_OR
        }

        is ExpressionNode.Increment -> PRECEDENCE_ADDITIVE
        is ExpressionNode.Unary, is ExpressionNode.Conversion -> PRECEDENCE_UNARY
        else -> PRECEDENCE_PRIMARY
    }

    private fun parenthesizeBoolean(id: ValueId, expression: ExpressionAnalysis): String {
        val rendered = renderValue(id, expression, PRECEDENCE_UNARY)
        return if (id in expression.materialization.inlineValues && precedence(expression.value(id).node) < PRECEDENCE_UNARY) {
            "($rendered)"
        } else rendered
    }

    private fun sourceName(internalName: String): String = internalName.removePrefix("class/").replace('/', '.')

    private fun formatValueType(type: JvmValueType): String = type.formatTypeName(::sourceName, nullTypeName = "null", unknownReferenceName = "reference?")

    private fun formatJvmType(type: JvmType): String = type.formatTypeName(::sourceName)

    private fun outgoingTargets(outgoing: List<io.github.relvl.deobscura.cfg.ControlFlowEdge>): String =
        outgoing.filter { it.kind != ControlFlowEdgeKind.EXCEPTION }.joinToString(prefix = "-> [", postfix = "]") { "B${it.to.value}" }

    private companion object {
        const val PRECEDENCE_LOWEST = 0
        const val PRECEDENCE_CONDITIONAL = 1
        const val PRECEDENCE_BIT_OR = 4
        const val PRECEDENCE_BIT_XOR = 5
        const val PRECEDENCE_BIT_AND = 6
        const val PRECEDENCE_EQUALITY = 7
        const val PRECEDENCE_RELATIONAL = 8
        const val PRECEDENCE_SHIFT = 9
        const val PRECEDENCE_ADDITIVE = 10
        const val PRECEDENCE_MULTIPLICATIVE = 11
        const val PRECEDENCE_UNARY = 12
        const val PRECEDENCE_PRIMARY = 13
    }

    private sealed interface RenderEvent {
        val instructionIndex: Int
        val order: Int

        data class Value(
            override val instructionIndex: Int,
            val value: ExpressionValue,
        ) : RenderEvent {
            override val order: Int = 0
        }

        data class Statement(
            override val instructionIndex: Int,
            val statement: ExpressionStatement,
        ) : RenderEvent {
            override val order: Int = 1
        }
    }

    private fun instructionToBlock(analysis: MethodAnalysis): Map<Int, BasicBlockId> = buildMap {
        analysis.graph.blocks.forEach { block ->
            for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
        }
    }
}
