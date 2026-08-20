package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmType
import java.lang.constant.ConstantDescs

/** Minimal source-facing rendering for Expression IR. Names remain deliberately technical. */
internal class SourceExpressionRenderer(
    private val deobfuscation: DeobfuscationPlan = DeobfuscationPlan(),
    private val bindings: SourceValueBindings = SourceValueBindings.EMPTY,
) {
    fun renderDefinition(value: ExpressionValue, expression: ExpressionAnalysis): String {
        val rendered = renderNode(value.node, expression)
        return if (value.node is ExpressionNode.Phi) {
            "/* phi */ var v${value.id.value} = $rendered"
        } else {
            "var v${value.id.value} = $rendered"
        }
    }

    fun renderValue(id: ValueId, expression: ExpressionAnalysis): String =
        renderValue(id, expression, PRECEDENCE_LOWEST)

    fun renderCondition(condition: BranchCondition, expression: ExpressionAnalysis): String {
        val left = expression.values[condition.left]
        val isBoolean = condition.left in expression.materialization.booleanValues ||
            left?.type == io.github.relvl.deobscura.analysis.JvmValueType.Computational(
                io.github.relvl.deobscura.raw.JvmComputationalType.BOOLEAN,
            )
        if (isBoolean && condition.right == BranchOperand.Zero) {
            return when (condition.operator) {
                ComparisonOperator.EQ -> "!${parenthesizeBoolean(condition.left, expression)}"
                ComparisonOperator.NE -> renderValue(condition.left, expression, PRECEDENCE_CONDITIONAL)
                else -> "${renderValue(condition.left, expression)} ${condition.operator.symbol} 0"
            }
        }
        val precedence = when (condition.operator) {
            ComparisonOperator.EQ, ComparisonOperator.NE -> PRECEDENCE_EQUALITY
            else -> PRECEDENCE_RELATIONAL
        }
        return "${renderValue(condition.left, expression, precedence)} ${condition.operator.symbol} " +
            when (val right = condition.right) {
                is BranchOperand.Value -> renderValue(right.value, expression, precedence + 1)
                BranchOperand.Zero -> "0"
                BranchOperand.Null -> "null"
            }
    }

    fun renderStatement(statement: ExpressionStatement, expression: ExpressionAnalysis): String = when (statement) {
        is ExpressionStatement.FieldWrite -> {
            val fieldName = deobfuscation.fieldName(
                statement.field.ownerInternalName,
                statement.field.name,
                statement.field.descriptor,
            )
            val target = statement.receiver?.let { "${renderValue(it, expression)}.$fieldName" }
                ?: "${sourceName(statement.field.ownerInternalName)}.$fieldName"
            "$target = ${renderValue(statement.value, expression)}"
        }
        is ExpressionStatement.ArrayWrite ->
            "${renderValue(statement.array, expression)}[${renderValue(statement.index, expression)}] = ${renderValue(statement.value, expression)}"
        is ExpressionStatement.Call -> renderCall(statement.method, statement.receiver, statement.arguments, expression)
        is ExpressionStatement.DynamicCall ->
            "/* invokedynamic ${statement.callSite.name} */ (${statement.arguments.joinToString { renderValue(it, expression) }})"
        is ExpressionStatement.Return -> statement.value?.let { "return ${renderValue(it, expression)}" } ?: "return"
        is ExpressionStatement.Throw -> "throw ${renderValue(statement.value, expression)}"
        is ExpressionStatement.Monitor ->
            "/* ${if (statement.operation == MonitorOperation.ENTER) "monitor-enter" else "monitor-exit"} ${renderValue(statement.value, expression)} */"
        is ExpressionStatement.Branch -> "/* branch */"
        is ExpressionStatement.Switch -> "/* switch ${renderValue(statement.selector, expression)} */"
        is ExpressionStatement.Raw ->
            "/* ${statement.opcode}${statement.inputs.joinToString(prefix = "(", postfix = ")") { renderValue(it, expression) }} */"
    }

    private fun renderNode(node: ExpressionNode, expression: ExpressionAnalysis): String = when (node) {
        is ExpressionNode.Root -> when (val origin = node.origin) {
            is io.github.relvl.deobscura.analysis.ValueOrigin.This -> "this"
            is io.github.relvl.deobscura.analysis.ValueOrigin.Parameter -> "arg${origin.index}"
            is io.github.relvl.deobscura.analysis.ValueOrigin.ExceptionHandler -> bindings.exceptionParameter(origin.handlerInstructionIndex) ?: "caught"
            is io.github.relvl.deobscura.analysis.ValueOrigin.ReturnAddress -> "/* return-address@${origin.returnInstructionIndex} */ null"
            is io.github.relvl.deobscura.analysis.ValueOrigin.Instruction -> "v${origin.index}"
        }
        is ExpressionNode.Phi -> node.inputs.joinToString(prefix = "phi(", postfix = ")") { "v${it.value.value}" }
        is ExpressionNode.Constant -> formatConstant(node.value)
        is ExpressionNode.Unary, is ExpressionNode.Binary, is ExpressionNode.Increment, is ExpressionNode.Conversion ->
            renderInlineNode(node, expression, precedence(node))
        is ExpressionNode.ThreeWayCompare ->
            "cmp(${renderValue(node.left, expression)}, ${renderValue(node.right, expression)})"
        is ExpressionNode.FieldRead -> {
            val fieldName = deobfuscation.fieldName(node.field.ownerInternalName, node.field.name, node.field.descriptor)
            node.receiver?.let { "${renderValue(it, expression)}.$fieldName" }
                ?: "${sourceName(node.field.ownerInternalName)}.$fieldName"
        }
        is ExpressionNode.ArrayRead -> "${renderValue(node.array, expression)}[${renderValue(node.index, expression)}]"
        is ExpressionNode.ArrayLength -> "${renderValue(node.array, expression)}.length"
        is ExpressionNode.Call -> renderCall(node.method, node.receiver, node.arguments, expression)
        is ExpressionNode.DynamicCall ->
            "/* invokedynamic ${node.callSite.name} */ (${node.arguments.joinToString { renderValue(it, expression) }})"
        is ExpressionNode.NewObject -> "new ${sourceName(node.internalName)} /* uninitialized */"
        is ExpressionNode.ConstructObject ->
            "new ${sourceName(node.internalName)}(${node.arguments.joinToString { renderValue(it, expression) }})"
        is ExpressionNode.NewArray -> renderNewArray(node, expression)
        is ExpressionNode.Cast -> "(${formatType(node.targetType)}) ${renderValue(node.operand, expression)}"
        is ExpressionNode.InstanceOf -> "${renderValue(node.operand, expression)} instanceof ${formatType(node.targetType)}"
        is ExpressionNode.Raw ->
            "/* ${node.opcode}${node.inputs.joinToString(prefix = "(", postfix = ")") { renderValue(it, expression) }} */ null"
    }

    private fun renderValue(id: ValueId, expression: ExpressionAnalysis, parentPrecedence: Int): String {
        val value = expression.values[id] ?: return "v${id.value}"
        if (value.node is ExpressionNode.Root) return renderNode(value.node, expression)
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
        val value = expression.values[id] ?: return rendered
        return if (id in expression.materialization.inlineValues && precedence(value.node) < PRECEDENCE_UNARY) "($rendered)" else rendered
    }

    private fun renderCall(
        method: MethodSymbol,
        receiver: ValueId?,
        arguments: List<ValueId>,
        expression: ExpressionAnalysis,
    ): String {
        val renderedArguments = arguments.joinToString { renderValue(it, expression) }
        if (method.invocationKind == InvocationKind.SPECIAL && method.name == "<init>" && receiver != null) {
            val receiverOrigin = (expression.values[receiver]?.node as? ExpressionNode.Root)?.origin
            if (receiverOrigin is ValueOrigin.This) {
                return if (method.ownerInternalName == receiverOrigin.ownerInternalName) {
                    "this($renderedArguments)"
                } else {
                    "super($renderedArguments)"
                }
            }
        }

        val target = receiver?.let { renderValue(it, expression) } ?: sourceName(method.ownerInternalName)
        val methodName = deobfuscation.methodName(method.ownerInternalName, method.name, method.descriptor)
        return "$target.$methodName($renderedArguments)"
    }

    private fun renderNewArray(node: ExpressionNode.NewArray, expression: ExpressionAnalysis): String {
        var component: JvmType = node.arrayType
        repeat(node.dimensions.size) { component = (component as? JvmType.ArrayType)?.componentType ?: component }
        val dimensions = node.dimensions.joinToString("") { "[${renderValue(it, expression)}]" }
        return "new ${formatType(component)}$dimensions"
    }

    private fun formatValueType(type: io.github.relvl.deobscura.analysis.JvmValueType): String = when (type) {
        is io.github.relvl.deobscura.analysis.JvmValueType.Computational -> type.type.name.lowercase()
        is io.github.relvl.deobscura.analysis.JvmValueType.Reference -> when (val reference = type.referenceType) {
            is io.github.relvl.deobscura.raw.JvmReferenceType.Exact -> formatType(reference.type)
            io.github.relvl.deobscura.raw.JvmReferenceType.Null,
            io.github.relvl.deobscura.raw.JvmReferenceType.Unknown -> "Object"
        }
    }

    private fun formatType(type: JvmType): String = when (type) {
        JvmType.BooleanType -> "boolean"
        JvmType.ByteType -> "byte"
        JvmType.CharType -> "char"
        JvmType.ShortType -> "short"
        JvmType.IntType -> "int"
        JvmType.LongType -> "long"
        JvmType.FloatType -> "float"
        JvmType.DoubleType -> "double"
        JvmType.VoidType -> "void"
        is JvmType.ObjectType -> sourceName(type.internalName)
        is JvmType.ArrayType -> "${formatType(type.componentType)}[]"
    }

    private fun formatConstant(value: Any): String {
        if (value == ConstantDescs.NULL) return "null"
        return when (value) {
            is String -> buildString {
                append('"')
                value.forEach { appendJavaCharacter(it, quote = '"') }
                append('"')
            }
            is Char -> buildString {
                append('\'')
                appendJavaCharacter(value, quote = '\'')
                append('\'')
            }
            else -> value.toString()
        }
    }

    private fun StringBuilder.appendJavaCharacter(value: Char, quote: Char) {
        when (value) {
            '\\' -> append("\\\\")
            quote -> append('\\').append(value)
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000C' -> append("\\f")
            '\r' -> append("\\r")
            else -> {
                if (Character.isSurrogate(value) || Character.isISOControl(value)) {
                    append("\\u")
                    append(value.code.toString(16).padStart(4, '0'))
                } else {
                    append(value)
                }
            }
        }
    }

    private fun sourceName(internalName: String): String = deobfuscation.classInternalName(internalName).replace('/', '.')

    private companion object {
        const val PRECEDENCE_LOWEST = 0
        const val PRECEDENCE_CONDITIONAL = 1
        const val PRECEDENCE_BIT_OR = 2
        const val PRECEDENCE_BIT_XOR = 3
        const val PRECEDENCE_BIT_AND = 4
        const val PRECEDENCE_EQUALITY = 5
        const val PRECEDENCE_RELATIONAL = 6
        const val PRECEDENCE_SHIFT = 7
        const val PRECEDENCE_ADDITIVE = 8
        const val PRECEDENCE_MULTIPLICATIVE = 9
        const val PRECEDENCE_UNARY = 10
        const val PRECEDENCE_PRIMARY = 11
    }
}


/** Lexical source names assigned to JVM-root values already represented by source syntax. */
internal data class SourceValueBindings(
    private val exceptionParameters: Map<Int, String> = emptyMap(),
) {
    fun exceptionParameter(handlerInstructionIndex: Int): String? = exceptionParameters[handlerInstructionIndex]

    fun withExceptionParameter(handlerInstructionIndex: Int, name: String): SourceValueBindings =
        copy(exceptionParameters = exceptionParameters + (handlerInstructionIndex to name))

    companion object {
        val EMPTY = SourceValueBindings()
    }
}
