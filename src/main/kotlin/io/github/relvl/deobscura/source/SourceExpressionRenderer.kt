package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.analysis.JvmValueType
import io.github.relvl.deobscura.analysis.ValueOrigin
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.expression.*
import io.github.relvl.deobscura.raw.JvmComputationalType
import io.github.relvl.deobscura.raw.JvmReferenceType
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

    fun renderValue(id: ValueId, expression: ExpressionAnalysis, expectedType: JvmType): String =
        if (expectedType == JvmType.BooleanType) renderBooleanValue(id, expression) else renderValue(id, expression)

    fun renderCondition(condition: BranchCondition, expression: ExpressionAnalysis): String {
        renderThreeWayComparison(condition, expression)?.let { return it }

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

    fun renderStatement(
        statement: ExpressionStatement,
        expression: ExpressionAnalysis,
        methodReturnType: JvmType? = null,
    ): String = when (statement) {
        is ExpressionStatement.FieldWrite -> {
            val fieldName = deobfuscation.fieldName(
                statement.field.ownerInternalName,
                statement.field.name,
                statement.field.descriptor,
            )
            val target = statement.receiver?.let { "${renderValue(it, expression)}.$fieldName" }
                ?: "${sourceName(statement.field.ownerInternalName)}.$fieldName"
            "$target = ${renderValue(statement.value, expression, statement.field.type)}"
        }
        is ExpressionStatement.ArrayWrite -> {
            val componentType = arrayComponentType(statement.array, expression)
            val value = componentType?.let { renderValue(statement.value, expression, it) }
                ?: renderValue(statement.value, expression)
            "${renderValue(statement.array, expression)}[${renderValue(statement.index, expression)}] = $value"
        }
        is ExpressionStatement.Call -> renderCall(statement.method, statement.receiver, statement.arguments, expression)
        is ExpressionStatement.DynamicCall ->
            renderStringConcat(statement.callSite, statement.arguments, expression)
                ?: "/* invokedynamic ${statement.callSite.name} */ (${renderArguments(statement.arguments, statement.callSite.type.parameterTypes, expression)})"
        is ExpressionStatement.Return -> statement.value?.let { value ->
            val rendered = methodReturnType?.let { renderValue(value, expression, it) } ?: renderValue(value, expression)
            "return $rendered"
        } ?: "return"
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
            renderStringConcat(node.callSite, node.arguments, expression)
                ?: "/* invokedynamic ${node.callSite.name} */ (${renderArguments(node.arguments, node.callSite.type.parameterTypes, expression)})"
        is ExpressionNode.NewObject -> "new ${sourceName(node.internalName)} /* uninitialized */"
        is ExpressionNode.ConstructObject ->
            "new ${sourceName(node.internalName)}(${renderArguments(node.arguments, node.constructor.type.parameterTypes, expression)})"
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

    private fun renderThreeWayComparison(
        condition: BranchCondition,
        expression: ExpressionAnalysis,
    ): String? {
        if (condition.right != BranchOperand.Zero) return null
        val compare = (expression.values[condition.left]?.node as? ExpressionNode.ThreeWayCompare) ?: return null
        val left = renderValue(compare.left, expression, PRECEDENCE_RELATIONAL)
        val right = renderValue(compare.right, expression, PRECEDENCE_RELATIONAL + 1)

        val directOperator = when (compare.nanResult) {
            null -> condition.operator
            -1 -> when (condition.operator) {
                ComparisonOperator.EQ,
                ComparisonOperator.NE,
                ComparisonOperator.GT,
                ComparisonOperator.GE -> condition.operator
                ComparisonOperator.LT -> null
                ComparisonOperator.LE -> null
            }
            1 -> when (condition.operator) {
                ComparisonOperator.EQ,
                ComparisonOperator.NE,
                ComparisonOperator.LT,
                ComparisonOperator.LE -> condition.operator
                ComparisonOperator.GT -> null
                ComparisonOperator.GE -> null
            }
            else -> return null
        }
        if (directOperator != null) return "$left ${directOperator.symbol} $right"

        val opposite = when (condition.operator) {
            ComparisonOperator.LT -> ComparisonOperator.GE
            ComparisonOperator.LE -> ComparisonOperator.GT
            ComparisonOperator.GT -> ComparisonOperator.LE
            ComparisonOperator.GE -> ComparisonOperator.LT
            ComparisonOperator.EQ, ComparisonOperator.NE -> return null
        }
        return "!($left ${opposite.symbol} $right)"
    }

    private fun renderBooleanValue(id: ValueId, expression: ExpressionAnalysis): String {
        val value = expression.values[id] ?: return "v${id.value} != 0"
        val constant = (value.node as? ExpressionNode.Constant)?.value
        if (constant?.equals(0) == true) return "false"
        if (constant?.equals(1) == true) return "true"
        if (value.type == JvmValueType.Computational(JvmComputationalType.BOOLEAN)) {
            return renderValue(id, expression)
        }
        return "${renderValue(id, expression, PRECEDENCE_EQUALITY)} != 0"
    }

    private fun arrayComponentType(array: ValueId, expression: ExpressionAnalysis): JvmType? {
        val reference = (expression.values[array]?.type as? JvmValueType.Reference)?.referenceType
        val arrayType = (reference as? JvmReferenceType.Exact)?.type as? JvmType.ArrayType
        return arrayType?.componentType
    }

    private fun renderArguments(
        arguments: List<ValueId>,
        parameterTypes: List<JvmType>,
        expression: ExpressionAnalysis,
    ): String = arguments.mapIndexed { index, argument ->
        parameterTypes.getOrNull(index)?.let { renderValue(argument, expression, it) } ?: renderValue(argument, expression)
    }.joinToString()

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
        is ExpressionNode.DynamicCall -> if (isStringConcatCallSite(node.callSite)) PRECEDENCE_ADDITIVE else PRECEDENCE_PRIMARY
        is ExpressionNode.Unary, is ExpressionNode.Conversion -> PRECEDENCE_UNARY
        else -> PRECEDENCE_PRIMARY
    }

    private fun parenthesizeBoolean(id: ValueId, expression: ExpressionAnalysis): String {
        val rendered = renderValue(id, expression, PRECEDENCE_UNARY)
        val value = expression.values[id] ?: return rendered
        return if (id in expression.materialization.inlineValues && precedence(value.node) < PRECEDENCE_UNARY) "($rendered)" else rendered
    }

    private fun renderStringConcat(
        callSite: DynamicCallSite,
        arguments: List<ValueId>,
        expression: ExpressionAnalysis,
    ): String? {
        if (!isStringConcatCallSite(callSite)) return null
        val recipe = (callSite.bootstrapArguments.firstOrNull() as? Any) as? String ?: return null
        val constants = callSite.bootstrapArguments.drop(1)
        val parts = mutableListOf<StringConcatPart>()
        val literal = StringBuilder()
        var argumentIndex = 0
        var constantIndex = 0

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                parts += StringConcatPart.Literal(literal.toString())
                literal.setLength(0)
            }
        }

        for (character in recipe) {
            when (character) {
                '\u0001' -> {
                    flushLiteral()
                    val argument = arguments.getOrNull(argumentIndex) ?: return null
                    val expectedType = callSite.type.parameterTypes.getOrNull(argumentIndex)
                    val rendered = renderStringConcatArgument(argument, expectedType, expression)
                    parts += StringConcatPart.Expression(rendered)
                    argumentIndex++
                }
                '\u0002' -> {
                    val constant = constants.getOrNull(constantIndex) ?: return null
                    val rendered = renderStringConcatConstant(constant) ?: return null
                    literal.append(rendered)
                    constantIndex++
                }
                else -> literal.append(character)
            }
        }
        flushLiteral()
        if (argumentIndex != arguments.size || constantIndex != constants.size) return null

        if (parts.isEmpty()) return "\"\""
        val renderedParts = parts.map { part ->
            when (part) {
                is StringConcatPart.Literal -> formatConstant(part.value)
                is StringConcatPart.Expression -> part.source
            }
        }.toMutableList()
        if (parts.first() !is StringConcatPart.Literal) renderedParts.add(0, "\"\"")
        return renderedParts.joinToString(" + ")
    }

    private fun renderStringConcatArgument(
        argument: ValueId,
        expectedType: JvmType?,
        expression: ExpressionAnalysis,
    ): String {
        if (expectedType != JvmType.BooleanType) {
            return renderValue(argument, expression, PRECEDENCE_ADDITIVE + 1)
        }

        val value = expression.values[argument]
        val constant = (value?.node as? ExpressionNode.Constant)?.value
        if (constant?.equals(0) == true) return "false"
        if (constant?.equals(1) == true) return "true"
        if (value?.type == JvmValueType.Computational(JvmComputationalType.BOOLEAN)) {
            return renderValue(argument, expression, PRECEDENCE_ADDITIVE + 1)
        }
        return "(${renderValue(argument, expression, PRECEDENCE_EQUALITY)} != 0)"
    }

    private fun isStringConcatCallSite(callSite: DynamicCallSite): Boolean =
        callSite.bootstrapMethod.owner().descriptorString() == "Ljava/lang/invoke/StringConcatFactory;" &&
            callSite.bootstrapMethod.methodName() == "makeConcatWithConstants"

    private fun renderStringConcatConstant(constant: java.lang.constant.ConstantDesc): String? =
        when (val value: Any = constant) {
            is String, is Int, is Long, is Float, is Double -> value.toString()
            else -> null
        }

    private sealed interface StringConcatPart {
        data class Literal(val value: String) : StringConcatPart
        data class Expression(val source: String) : StringConcatPart
    }

    private fun renderCall(
        method: MethodSymbol,
        receiver: ValueId?,
        arguments: List<ValueId>,
        expression: ExpressionAnalysis,
    ): String {
        val renderedArguments = renderArguments(arguments, method.type.parameterTypes, expression)
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
