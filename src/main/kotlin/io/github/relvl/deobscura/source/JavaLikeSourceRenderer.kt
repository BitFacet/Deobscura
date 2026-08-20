package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalysis
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.controlflow.*
import io.github.relvl.deobscura.expression.ComparisonOperator
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.*

/**
 * First source-facing renderer. It intentionally renders only facts already present in SourceStructure
 * and Expression IR; anything unresolved remains explicit block/goto-style fallback instead of being
 * guessed into Java syntax.
 */
class JavaLikeSourceRenderer {
    private val expressionRenderer = SourceExpressionRenderer()
    fun renderClass(rawClass: RawClass, analyses: Map<SourceMethodKey, MethodAnalysis>): String = buildString {
        val packageName = rawClass.internalName.substringBeforeLast('/', missingDelimiterValue = "")
            .replace('/', '.')
        val simpleName = rawClass.internalName.substringAfterLast('/')
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName;")
            appendLine()
        }

        append(classModifiers(rawClass.accessFlags))
        append(classKind(rawClass.accessFlags))
        append(' ')
        append(simpleName)
        rawClass.superName?.takeUnless { it == "java/lang/Object" || rawClass.accessFlags and ACC_INTERFACE != 0 }
            ?.let { append(" extends ${sourceName(it)}") }
        if (rawClass.interfaces.isNotEmpty()) {
            append(if (rawClass.accessFlags and ACC_INTERFACE != 0) " extends " else " implements ")
            append(rawClass.interfaces.joinToString { sourceName(it) })
        }
        appendLine(" {")

        rawClass.fields.forEach { field ->
            append("    ")
            append(memberModifiers(field.accessFlags))
            append("${formatType(field.type)} ${field.name};")
            appendLine()
        }
        if (rawClass.fields.isNotEmpty() && rawClass.methods.isNotEmpty()) appendLine()

        rawClass.methods.forEachIndexed { index, method ->
            val analysis = analyses[SourceMethodKey(method.name, method.descriptor)]
            appendLine(renderMethod(simpleName, method, analysis).trimEnd().prependIndent("    "))
            if (index != rawClass.methods.lastIndex) appendLine()
        }

        appendLine("}")
    }

    private fun renderMethod(ownerSimpleName: String, method: RawMethod, analysis: MethodAnalysis?): String = buildString {
        if (method.name == "<clinit>") {
            appendLine("static {")
            if (analysis == null) appendLine("    /* analysis unavailable */") else renderBody(analysis, 1, this)
            appendLine("}")
            return@buildString
        }

        append(memberModifiers(method.accessFlags))
        if (method.name == "<init>") {
            append(ownerSimpleName)
        } else {
            append(formatType(method.type.returnType))
            append(' ')
            append(method.name)
        }
        append('(')
        append(method.type.parameterTypes.mapIndexed { index, type -> "${formatType(type)} arg$index" }.joinToString())
        append(')')
        if (method.exceptions.isNotEmpty()) {
            append(" throws ")
            append(method.exceptions.joinToString { sourceName(it) })
        }

        if (method.code == null) {
            appendLine(";")
        } else if (analysis == null) {
            appendLine(" {")
            appendLine("    /* analysis unavailable */")
            appendLine("}")
        } else {
            appendLine(" {")
            renderBody(analysis, 1, this)
            appendLine("}")
        }
    }

    private fun renderBody(analysis: MethodAnalysis, indent: Int, out: StringBuilder) {
        val context = RenderContext(analysis)
        renderSourceBlock(analysis.sourceStructure.root, context, indent, out)
        if (analysis.sourceStructure.issues.isNotEmpty()) {
            appendIndented(out, indent, "/* source projection debt: ${analysis.sourceStructure.issues.size} issue(s) */")
        }
    }

    private fun renderSourceBlock(
        block: SourceBlock,
        context: RenderContext,
        indent: Int,
        out: StringBuilder,
        instructionRanges: List<IntRange> = emptyList(),
        suppressMonitor: Boolean = false,
    ) {
        block.nodes.forEach { node ->
            when (node) {
                is SourceNode.BasicBlock -> renderPhysicalBlock(node.block, context, indent, out, fallback = false, instructionRanges = instructionRanges, suppressMonitor = suppressMonitor)
                is SourceNode.Unstructured -> {
                    appendIndented(out, indent, "/* unresolved control flow: ${node.diagnostics.joinToString { it.reason.diagnosticName }} */")
                    renderPhysicalBlock(node.block, context, indent, out, fallback = true, instructionRanges = instructionRanges, suppressMonitor = suppressMonitor)
                }
                is SourceNode.ProjectionFallback -> {
                    appendIndented(out, indent, "/* projection fallback: ${node.reason.name.lowercase().replace('_', '-')} */")
                    renderPhysicalBlock(node.block, context, indent, out, fallback = true, instructionRanges = instructionRanges, suppressMonitor = suppressMonitor)
                }
                is SourceNode.Structured -> renderStructured(node, context, indent, out)
            }
        }
    }

    private fun renderStructured(node: SourceNode.Structured, context: RenderContext, indent: Int, out: StringBuilder) {
        val region = node.region
        when (region) {
            is StructuredRegion.If -> {
                renderPhysicalBlock(region.header, context, indent, out, includeControl = false)
                appendIndented(out, indent, "if (${renderCondition(region.condition, context.analysis)}) {")
                renderPart(node, SourceRegionPartKind.THEN, 0, context, indent + 1, out)
                region.thenExit?.let { renderArmExit(it, indent + 1, out) }
                val elsePart = node.parts.firstOrNull { it.kind == SourceRegionPartKind.ELSE }
                if (elsePart != null) {
                    appendIndented(out, indent, "} else {")
                    renderSourceBlock(elsePart.body, context, indent + 1, out, elsePart.instructionRanges)
                    region.elseExit?.let { renderArmExit(it, indent + 1, out) }
                }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.While -> {
                renderPhysicalBlock(region.header, context, indent, out, includeControl = false)
                val condition = if (region.negateCondition) region.condition.negated() else region.condition
                appendIndented(out, indent, "while (${renderCondition(condition, context.analysis)}) {")
                renderPart(node, SourceRegionPartKind.LOOP_BODY, 0, context, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.Switch -> {
                renderPhysicalBlock(region.header, context, indent, out, includeControl = false)
                appendIndented(out, indent, "switch (${expressionRenderer.renderValue(region.selector, context.analysis.expression)}) {")
                node.parts.filter { it.kind == SourceRegionPartKind.SWITCH_CASE }
                    .sortedBy { it.ordinal }
                    .forEach { part ->
                        val case = region.cases[part.ordinal]
                        case.labels.forEach { appendIndented(out, indent + 1, "case $it:") }
                        if (case.isDefault) appendIndented(out, indent + 1, "default:")
                        renderSourceBlock(part.body, context, indent + 2, out)
                        renderSwitchTransfers(case.transfers, indent + 2, out)
                    }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryCatch -> {
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, indent + 1, out)
                region.catches.forEachIndexed { index, catch ->
                    appendIndented(out, indent, "} catch (${catch.catchTypes.joinToString(" | ") { sourceName(it) }} e$index) {")
                    renderPart(node, SourceRegionPartKind.CATCH_BODY, index, context, indent + 1, out)
                }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryFinally -> {
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, indent + 1, out)
                appendIndented(out, indent, "} finally {")
                renderPart(node, SourceRegionPartKind.FINALLY_BODY, 0, context, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryCatchFinally -> {
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, indent + 1, out)
                region.catches.forEachIndexed { index, catch ->
                    appendIndented(out, indent, "} catch (${catch.catchTypes.joinToString(" | ") { sourceName(it) }} e$index) {")
                    renderPart(node, SourceRegionPartKind.CATCH_BODY, index, context, indent + 1, out)
                }
                appendIndented(out, indent, "} finally {")
                renderPart(node, SourceRegionPartKind.FINALLY_BODY, 0, context, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.Synchronized -> {
                renderPhysicalBlock(region.header, context, indent, out, includeControl = false)
                appendIndented(out, indent, "synchronized (/* monitor local[${region.monitorSlot}] */) {")
                renderPart(node, SourceRegionPartKind.SYNCHRONIZED_BODY, 0, context, indent + 1, out)
                appendIndented(out, indent, "}")
            }
        }
    }

    private fun renderPart(
        node: SourceNode.Structured,
        kind: SourceRegionPartKind,
        ordinal: Int,
        context: RenderContext,
        indent: Int,
        out: StringBuilder,
    ) {
        node.parts.firstOrNull { it.kind == kind && it.ordinal == ordinal }
            ?.let { renderSourceBlock(it.body, context, indent, out, it.instructionRanges, kind == SourceRegionPartKind.SYNCHRONIZED_BODY) }
    }

    private fun renderPhysicalBlock(
        block: BasicBlockId,
        context: RenderContext,
        indent: Int,
        out: StringBuilder,
        fallback: Boolean = false,
        includeControl: Boolean = true,
        instructionRanges: List<IntRange> = emptyList(),
        suppressMonitor: Boolean = false,
    ) {
        if (fallback) appendIndented(out, indent, "B${block.value}:")
        val contentIndent = indent + if (fallback) 1 else 0
        context.phisByBlock[block].orEmpty().forEach { value ->
            appendIndented(out, contentIndent, expressionRenderer.renderDefinition(value, context.analysis.expression) + ";")
        }
        context.eventsByBlock[block].orEmpty()
            .filter { event -> instructionRanges.isEmpty() || instructionRanges.any { event.instructionIndex in it } }
            .forEach { event ->
            when (event) {
                is BlockEvent.Value -> appendIndented(
                    out,
                    contentIndent,
                    expressionRenderer.renderDefinition(event.value, context.analysis.expression) + ";",
                )
                is BlockEvent.Statement -> {
                    if (!includeControl && event.statement.isStructuralControl()) return@forEach
                    if (suppressMonitor && event.statement is ExpressionStatement.Monitor) return@forEach
                    val rendered = renderStatement(event.statement, block, context)
                    appendIndented(out, contentIndent, rendered + ";")
                }
            }
        }
        if (fallback) renderFallbackTransfers(block, context, indent + 1, out)
    }

    private fun renderStatement(statement: ExpressionStatement, block: BasicBlockId, context: RenderContext): String {
        val outgoing = context.outgoingByBlock[block].orEmpty()
        return when (statement) {
            is ExpressionStatement.Branch -> {
                val condition = statement.condition
                if (condition == null) {
                    outgoing.joinToString(" ") { "goto B${it.to.value}" }
                } else {
                    val taken = outgoing.firstOrNull { it.kind == ControlFlowEdgeKind.CONDITIONAL }?.to
                    val fallthrough = outgoing.firstOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH }?.to
                    buildString {
                        append("if (")
                        append(expressionRenderer.renderCondition(condition, context.analysis.expression))
                        append(")")
                        taken?.let { append(" goto B${it.value}") }
                        fallthrough?.let { append(" else goto B${it.value}") }
                    }
                }
            }

            is ExpressionStatement.Switch -> {
                val cases = outgoing.filter { it.kind == ControlFlowEdgeKind.SWITCH }
                    .joinToString { edge -> edge.switchValue?.let { "$it: B${edge.to.value}" } ?: "default: B${edge.to.value}" }
                "switch (${expressionRenderer.renderValue(statement.selector, context.analysis.expression)}) -> [$cases]"
            }

            else -> expressionRenderer.renderStatement(statement, context.analysis.expression)
        }
    }

    private fun renderFallbackTransfers(block: BasicBlockId, context: RenderContext, indent: Int, out: StringBuilder) {
        val outgoing = context.outgoingByBlock[block].orEmpty()
        val terminalControl = context.eventsByBlock[block].orEmpty().lastOrNull()?.let { it as? BlockEvent.Statement }?.statement
        if (terminalControl is ExpressionStatement.Branch || terminalControl is ExpressionStatement.Switch) return
        outgoing.filter { it.to in context.analysis.optimization.controlFlow.blocks }
            .forEach { edge -> appendIndented(out, indent, "goto B${edge.to.value};") }
    }

    private fun renderArmExit(exit: StructuredArmExit, indent: Int, out: StringBuilder) {
        when (exit.kind) {
            StructuredArmExitKind.RETURN_OR_THROW -> Unit
            StructuredArmExitKind.CONTINUE -> appendIndented(out, indent, "continue;")
            StructuredArmExitKind.BREAK -> appendIndented(out, indent, "break;")
        }
    }

    private fun renderSwitchTransfers(transfers: List<StructuredRegionTransfer>, indent: Int, out: StringBuilder) {
        transfers.forEach { transfer -> when (transfer.kind) {
            StructuredRegionTransferKind.BREAK_SWITCH, StructuredRegionTransferKind.BREAK_LOOP -> appendIndented(out, indent, "break;")
            StructuredRegionTransferKind.CONTINUE_LOOP -> appendIndented(out, indent, "continue;")
            StructuredRegionTransferKind.CASE_FALLTHROUGH, StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION,
            StructuredRegionTransferKind.RETURN_OR_THROW -> Unit
        } }
    }

    private fun renderCondition(condition: StructuredCondition, analysis: MethodAnalysis): String = when (condition) {
        is StructuredCondition.Atomic -> expressionRenderer.renderCondition(condition.condition, analysis.expression)
        is StructuredCondition.And -> condition.terms.joinToString(" && ") { term ->
            val rendered = renderCondition(term, analysis)
            if (term is StructuredCondition.Or) "($rendered)" else rendered
        }
        is StructuredCondition.Or -> condition.terms.joinToString(" || ") { term ->
            val rendered = renderCondition(term, analysis)
            if (term is StructuredCondition.And) "($rendered)" else rendered
        }
    }

    private fun StructuredCondition.negated(): StructuredCondition = when (this) {
        is StructuredCondition.Atomic -> copy(condition = condition.copy(operator = condition.operator.negated()))
        is StructuredCondition.And -> StructuredCondition.Or(terms.map { it.negated() })
        is StructuredCondition.Or -> StructuredCondition.And(terms.map { it.negated() })
    }

    private fun ComparisonOperator.negated(): ComparisonOperator = when (this) {
        ComparisonOperator.EQ -> ComparisonOperator.NE
        ComparisonOperator.NE -> ComparisonOperator.EQ
        ComparisonOperator.LT -> ComparisonOperator.GE
        ComparisonOperator.LE -> ComparisonOperator.GT
        ComparisonOperator.GT -> ComparisonOperator.LE
        ComparisonOperator.GE -> ComparisonOperator.LT
    }

    private fun ExpressionStatement.isStructuralControl(): Boolean =
        this is ExpressionStatement.Branch || this is ExpressionStatement.Switch || this is ExpressionStatement.Monitor

    private fun appendIndented(out: StringBuilder, indent: Int, line: String) {
        repeat(indent) { out.append("    ") }
        out.appendLine(line)
    }

    private fun classModifiers(access: Int): String = buildString {
        if (access and ACC_PUBLIC != 0) append("public ")
        if (access and ACC_ABSTRACT != 0 && access and ACC_INTERFACE == 0) append("abstract ")
        if (access and ACC_FINAL != 0 && access and ACC_ENUM == 0) append("final ")
    }

    private fun memberModifiers(access: Int): String = buildString {
        when {
            access and ACC_PUBLIC != 0 -> append("public ")
            access and ACC_PROTECTED != 0 -> append("protected ")
            access and ACC_PRIVATE != 0 -> append("private ")
        }
        if (access and ACC_STATIC != 0) append("static ")
        if (access and ACC_ABSTRACT != 0) append("abstract ")
        if (access and ACC_FINAL != 0) append("final ")
        if (access and ACC_NATIVE != 0) append("native ")
        if (access and ACC_SYNCHRONIZED != 0) append("synchronized ")
    }

    private fun classKind(access: Int): String = when {
        access and ACC_ANNOTATION != 0 -> "@interface"
        access and ACC_INTERFACE != 0 -> "interface"
        access and ACC_ENUM != 0 -> "enum"
        else -> "class"
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

    private fun sourceName(internalName: String): String = internalName.removePrefix("class/").replace('/', '.')

    private class RenderContext(val analysis: MethodAnalysis) {
        val outgoingByBlock: Map<BasicBlockId, List<ControlFlowEdge>> = analysis.optimization.controlFlow.edges.groupBy { it.from }
        val phisByBlock: Map<BasicBlockId, List<ExpressionValue>> = analysis.expression.values.values
            .filter { it.node is ExpressionNode.Phi }
            .groupBy { (it.node as ExpressionNode.Phi).blockId }
        val eventsByBlock: Map<BasicBlockId, List<BlockEvent>> = buildEvents(analysis)

        companion object {
            private fun buildEvents(analysis: MethodAnalysis): Map<BasicBlockId, List<BlockEvent>> {
                val instructionToBlock = buildMap<Int, BasicBlockId> {
                    analysis.graph.blocks.forEach { block ->
                        for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
                    }
                }
                return buildMap<BasicBlockId, MutableList<BlockEvent>> {
                    analysis.expression.values.values
                        .filter { it.instructionIndices.isNotEmpty() && it.id !in analysis.expression.materialization.inlineValues }
                        .forEach { value ->
                            val index = value.instructionIndices.last()
                            instructionToBlock[index]?.let { block -> getOrPut(block) { mutableListOf() } += BlockEvent.Value(index, value) }
                        }
                    analysis.expression.statements.forEach { statement ->
                        instructionToBlock[statement.instructionIndex]?.let { block ->
                            getOrPut(block) { mutableListOf() } += BlockEvent.Statement(statement.instructionIndex, statement)
                        }
                    }
                    values.forEach { it.sortWith(compareBy<BlockEvent> { event -> event.instructionIndex }.thenBy { it.order }) }
                }
            }
        }
    }

    private sealed interface BlockEvent {
        val instructionIndex: Int
        val order: Int

        data class Value(override val instructionIndex: Int, val value: ExpressionValue) : BlockEvent { override val order = 0 }
        data class Statement(override val instructionIndex: Int, val statement: ExpressionStatement) : BlockEvent { override val order = 1 }
    }


    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
        const val ACC_PROTECTED = 0x0004
        const val ACC_STATIC = 0x0008
        const val ACC_FINAL = 0x0010
        const val ACC_SYNCHRONIZED = 0x0020
        const val ACC_NATIVE = 0x0100
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
        const val ACC_ANNOTATION = 0x2000
        const val ACC_ENUM = 0x4000
    }
}

data class SourceMethodKey(val name: String, val descriptor: String)
