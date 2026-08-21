package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalysis
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdge
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.controlflow.*
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.expression.ComparisonOperator
import io.github.relvl.deobscura.expression.ExpressionNode
import io.github.relvl.deobscura.expression.ExpressionStatement
import io.github.relvl.deobscura.expression.ExpressionValue
import io.github.relvl.deobscura.raw.*
import io.github.relvl.deobscura.resolution.MethodOverrideAnalysis

/**
 * First source-facing renderer. It intentionally renders only facts already present in SourceStructure
 * and Expression IR; anything unresolved remains explicit block/goto-style fallback instead of being
 * guessed into Java syntax.
 */
class JavaLikeSourceRenderer(
    private val deobfuscation: DeobfuscationPlan = DeobfuscationPlan(),
    private val methodOverrides: MethodOverrideAnalysis = MethodOverrideAnalysis.EMPTY,
) {
    fun renderClass(rawClass: RawClass, analyses: Map<SourceMethodKey, MethodAnalysis>): String = buildString {
        val sourceInternalName = deobfuscation.classInternalName(rawClass.internalName)
        val packageName = sourceInternalName.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')
        val simpleName = sourceInternalName.substringAfterLast('/')
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName;")
            appendLine()
        }

        append(classModifiers(rawClass.accessFlags))
        append(classKind(rawClass.accessFlags))
        append(' ')
        append(simpleName)
        rawClass.superName?.takeUnless { it == "java/lang/Object" || rawClass.accessFlags and ACC_INTERFACE != 0 }?.let { append(" extends ${deobfuscation.sourceClassName(it)}") }
        if (rawClass.interfaces.isNotEmpty()) {
            append(if (rawClass.accessFlags and ACC_INTERFACE != 0) " extends " else " implements ")
            append(rawClass.interfaces.joinToString { deobfuscation.sourceClassName(it) })
        }
        appendLine(" {")

        rawClass.fields.forEach { field ->
            append("    ")
            append(memberModifiers(field.accessFlags))
            append("${formatType(field.type)} ${deobfuscation.fieldName(rawClass.internalName, field.name, field.descriptor)};")
            appendLine()
        }
        if (rawClass.fields.isNotEmpty() && rawClass.methods.isNotEmpty()) appendLine()

        val trivialNoArgConstructor = rawClass.methods.firstOrNull { it.name == "<init>" && it.descriptor == "()V" }?.takeIf { it.isTrivialConstructor(rawClass) }

        rawClass.methods.forEachIndexed { index, method ->
            val analysis = analyses[SourceMethodKey(method.name, method.descriptor)]
            appendLine(
                renderMethod(
                    rawClass.internalName,
                    simpleName,
                    method,
                    analysis,
                    trivialNoArgConstructor != null,
                ).trimEnd().prependIndent("    "),
            )
            if (index != rawClass.methods.lastIndex) appendLine()
        }

        appendLine("}")
    }

    private fun renderMethod(
        methodOwnerInternalName: String,
        ownerSimpleName: String,
        method: RawMethod,
        analysis: MethodAnalysis?,
        hasTrivialNoArgConstructor: Boolean,
    ): String = buildString {
        if (method.name == "<clinit>") {
            appendLine("static {")
            if (analysis == null) {
                appendLine("    /* analysis unavailable */")
            } else {
                renderBody(
                    analysis,
                    1,
                    this,
                    RenderMethodContext(methodOwnerInternalName, method, hasTrivialNoArgConstructor),
                )
            }
            appendLine("}")
            return@buildString
        }

        if (method.name != "<init>" && method.accessFlags and ACC_BRIDGE == 0 && methodOverrides.overridesSuperMethod(methodOwnerInternalName, method.name, method.descriptor)) {
            appendLine("@Override")
        }

        append(memberModifiers(method.accessFlags))
        if (method.name == "<init>") {
            append(ownerSimpleName)
        } else {
            append(formatType(method.type.returnType))
            append(' ')
            append(deobfuscation.methodName(methodOwnerInternalName, method.name, method.descriptor))
        }
        append('(')
        append(method.type.parameterTypes.mapIndexed { index, type -> "${formatType(type)} arg$index" }.joinToString())
        append(')')
        if (method.exceptions.isNotEmpty()) {
            append(" throws ")
            append(method.exceptions.joinToString { deobfuscation.sourceClassName(it) })
        }

        if (method.code == null) {
            appendLine(";")
        } else if (analysis == null) {
            appendLine(" {")
            appendLine("    /* analysis unavailable */")
            appendLine("}")
        } else {
            appendLine(" {")
            renderBody(
                analysis,
                1,
                this,
                RenderMethodContext(methodOwnerInternalName, method, hasTrivialNoArgConstructor),
            )
            appendLine("}")
        }
    }

    private fun renderBody(
        analysis: MethodAnalysis,
        indent: Int,
        out: StringBuilder,
        method: RenderMethodContext,
    ) {
        val context = RenderContext(analysis, method)
        renderSourceBlock(
            analysis.sourceStructure.root,
            context,
            SourceValueBindings.EMPTY.withSourceLocals(context.sourceLocalBindings),
            indent,
            out,
            tailPosition = method.allowsImplicitReturn,
        )
        if (analysis.sourceStructure.issues.isNotEmpty()) {
            appendIndented(out, indent, "/* source projection debt: ${analysis.sourceStructure.issues.size} issue(s) */")
        }
    }

    private fun renderSourceBlock(
        block: SourceBlock,
        context: RenderContext,
        bindings: SourceValueBindings,
        indent: Int,
        out: StringBuilder,
        instructionRanges: List<IntRange> = emptyList(),
        suppressMonitor: Boolean = false,
        tailPosition: Boolean = false,
    ) {
        block.nodes.forEachIndexed { index, node ->
            val nodeTailPosition = tailPosition && index == block.nodes.lastIndex
            when (node) {
                is SourceNode.BasicBlock -> renderPhysicalBlock(
                    node.block,
                    context,
                    bindings,
                    indent,
                    out,
                    fallback = false,
                    instructionRanges = instructionRanges,
                    suppressMonitor = suppressMonitor,
                    tailPosition = nodeTailPosition,
                )

                is SourceNode.Unstructured -> {
                    appendIndented(out, indent, "/* unresolved control flow: ${node.diagnostics.joinToString { it.reason.diagnosticName }} */")
                    renderPhysicalBlock(node.block, context, bindings, indent, out, fallback = true, instructionRanges = instructionRanges, suppressMonitor = suppressMonitor)
                }

                is SourceNode.ProjectionFallback -> {
                    appendIndented(out, indent, "/* projection fallback: ${node.reason.name.lowercase().replace('_', '-')} */")
                    renderPhysicalBlock(node.block, context, bindings, indent, out, fallback = true, instructionRanges = instructionRanges, suppressMonitor = suppressMonitor)
                }

                is SourceNode.Structured -> renderStructured(node, context, bindings, indent, out, nodeTailPosition)
            }
        }
    }

    private fun renderStructured(
        node: SourceNode.Structured,
        context: RenderContext,
        bindings: SourceValueBindings,
        indent: Int,
        out: StringBuilder,
        tailPosition: Boolean,
    ) {
        when (val region = node.region) {
            is StructuredRegion.If -> {
                renderPhysicalBlock(region.header, context, bindings, indent, out, includeControl = false)
                if (region.header in context.analysis.sourceLocals.consumedIfHeaders) return
                context.twoArmAssignmentsByHeader[region.header].orEmpty().forEach { phi ->
                    val target = context.analysis.expression.values.getValue(phi)
                    appendIndented(out, indent, expressionRenderer(bindings).renderLocalDeclaration(target) + ";")
                }
                appendIndented(out, indent, "if (${renderCondition(region.condition, context.analysis, bindings)}) {")
                renderPart(node, SourceRegionPartKind.THEN, 0, context, bindings, indent + 1, out, tailPosition)
                region.thenExit?.let { renderArmExit(it, indent + 1, out) }
                val elsePart = node.parts.firstOrNull { it.kind == SourceRegionPartKind.ELSE }
                if (elsePart != null) {
                    appendIndented(out, indent, "} else {")
                    renderSourceBlock(
                        elsePart.body,
                        context,
                        bindings,
                        indent + 1,
                        out,
                        elsePart.instructionRanges,
                        tailPosition = tailPosition,
                    )
                    region.elseExit?.let { renderArmExit(it, indent + 1, out) }
                }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.While -> {
                val loopBindings = bindings.withInlineValues(context.loopConditionInlineValuesByHeader[region.header].orEmpty())
                renderPhysicalBlock(region.header, context, loopBindings, indent, out, includeControl = false)
                val condition = if (region.negateCondition) region.condition.negated() else region.condition
                appendIndented(out, indent, "while (${renderCondition(condition, context.analysis, loopBindings)}) {")
                renderPart(node, SourceRegionPartKind.LOOP_BODY, 0, context, bindings, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.Switch -> {
                renderPhysicalBlock(region.header, context, bindings, indent, out, includeControl = false)
                appendIndented(out, indent, "switch (${expressionRenderer(bindings).renderValue(region.selector, context.analysis.expression)}) {")
                node.parts.filter { it.kind == SourceRegionPartKind.SWITCH_CASE }.sortedBy { it.ordinal }.forEach { part ->
                    val case = region.cases[part.ordinal]
                    case.labels.forEach { appendIndented(out, indent + 1, "case $it:") }
                    if (case.isDefault) appendIndented(out, indent + 1, "default:")
                    renderSourceBlock(part.body, context, bindings, indent + 2, out)
                    renderSwitchTransfers(case.transfers, indent + 2, out)
                }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryCatch -> {
                renderHoistedDeclarations(region.header, context, bindings, indent, out)
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, bindings, indent + 1, out, tailPosition)
                region.catches.forEachIndexed { index, catch ->
                    val parameterName = "e$index"
                    val catchBindings = bindings.withExceptionParameter(
                        context.analysis.graph.block(catch.entry).startInstructionIndex,
                        parameterName,
                    )
                    appendIndented(out, indent, "} catch (${catch.catchTypes.joinToString(" | ") { deobfuscation.sourceClassName(it) }} $parameterName) {")
                    renderPart(
                        node,
                        SourceRegionPartKind.CATCH_BODY,
                        index,
                        context,
                        catchBindings,
                        indent + 1,
                        out,
                        tailPosition,
                    )
                }
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryFinally -> {
                renderHoistedDeclarations(region.header, context, bindings, indent, out)
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, bindings, indent + 1, out)
                appendIndented(out, indent, "} finally {")
                renderPart(node, SourceRegionPartKind.FINALLY_BODY, 0, context, bindings, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.TryCatchFinally -> {
                renderHoistedDeclarations(region.header, context, bindings, indent, out)
                appendIndented(out, indent, "try {")
                renderPart(node, SourceRegionPartKind.TRY_BODY, 0, context, bindings, indent + 1, out)
                region.catches.forEachIndexed { index, catch ->
                    val parameterName = "e$index"
                    val catchBindings = bindings.withExceptionParameter(
                        context.analysis.graph.block(catch.entry).startInstructionIndex,
                        parameterName,
                    )
                    appendIndented(out, indent, "} catch (${catch.catchTypes.joinToString(" | ") { deobfuscation.sourceClassName(it) }} $parameterName) {")
                    renderPart(node, SourceRegionPartKind.CATCH_BODY, index, context, catchBindings, indent + 1, out)
                }
                appendIndented(out, indent, "} finally {")
                renderPart(node, SourceRegionPartKind.FINALLY_BODY, 0, context, bindings, indent + 1, out)
                appendIndented(out, indent, "}")
            }

            is StructuredRegion.Synchronized -> {
                renderPhysicalBlock(region.header, context, bindings, indent, out, includeControl = false)
                appendIndented(out, indent, "synchronized (/* monitor local[${region.monitorSlot}] */) {")
                renderPart(
                    node,
                    SourceRegionPartKind.SYNCHRONIZED_BODY,
                    0,
                    context,
                    bindings,
                    indent + 1,
                    out,
                    tailPosition,
                )
                appendIndented(out, indent, "}")
            }
        }
    }

    private fun renderHoistedDeclarations(
        regionHeader: BasicBlockId,
        context: RenderContext,
        bindings: SourceValueBindings,
        indent: Int,
        out: StringBuilder,
    ) {
        context.hoistedValuesByRegionHeader[regionHeader].orEmpty().forEach { valueId ->
            val value = context.analysis.expression.values.getValue(valueId)
            appendIndented(out, indent, expressionRenderer(bindings).renderLocalDeclaration(value) + ";")
        }
    }

    private fun renderPart(node: SourceNode.Structured, kind: SourceRegionPartKind, ordinal: Int, context: RenderContext, bindings: SourceValueBindings, indent: Int, out: StringBuilder, tailPosition: Boolean = false) {
        node.parts.firstOrNull { it.kind == kind && it.ordinal == ordinal }?.let {
            renderSourceBlock(it.body, context, bindings, indent, out, it.instructionRanges, kind == SourceRegionPartKind.SYNCHRONIZED_BODY, tailPosition)
        }
    }

    private fun renderPhysicalBlock(
        block: BasicBlockId,
        context: RenderContext,
        bindings: SourceValueBindings,
        indent: Int,
        out: StringBuilder,
        fallback: Boolean = false,
        includeControl: Boolean = true,
        instructionRanges: List<IntRange> = emptyList(),
        suppressMonitor: Boolean = false,
        tailPosition: Boolean = false,
    ) {
        if (fallback) appendIndented(out, indent, "B${block.value}:")
        val contentIndent = indent + if (fallback) 1 else 0
        context.phisByBlock[block].orEmpty().forEach phiLoop@{ value ->
            if (value.id in context.analysis.sourceLocals.conditionalAssignments || value.id in context.analysis.sourceLocals.twoArmAssignments || value.id in context.analysis.sourceLocals.loopAssignments || value.id in context.familyPhiValues) return@phiLoop
            val conditional = context.analysis.sourceLocals.conditionalValues[value.id]
            val rendered = if (conditional == null) {
                expressionRenderer(bindings).renderDefinition(value, context.analysis.expression)
            } else {
                val condition = renderCondition(conditional.condition, context.analysis, bindings)
                expressionRenderer(bindings).renderConditionalDefinition(
                    value,
                    condition,
                    conditional.thenValue,
                    conditional.elseValue,
                    context.analysis.expression,
                )
            }
            appendIndented(out, contentIndent, "$rendered;")
        }
        val events = context.eventsByBlock[block].orEmpty().filter { event -> instructionRanges.isEmpty() || instructionRanges.any { event.instructionIndex in it } }
        events.forEachIndexed eventLoop@{ index, event ->
            when (event) {
                is BlockEvent.Value -> {
                    if (event.value.id in context.analysis.sourceLocals.suppressedDefinitions || event.value.id in context.loopConditionInlineValues) return@eventLoop
                    if (event.value.id in context.hoistedValues) {
                        appendIndented(
                            out,
                            contentIndent,
                            expressionRenderer(bindings).renderLocalDefinitionAssignment(
                                event.value,
                                context.analysis.expression,
                            ) + ";",
                        )
                        return@eventLoop
                    }
                    val declaration = context.localDeclarationByInitializer[event.value.id]
                    if (declaration != null) {
                        val target = context.analysis.expression.values.getValue(declaration.first)
                        appendIndented(
                            out,
                            contentIndent,
                            expressionRenderer(bindings).renderLocalDeclaration(
                                target,
                                declaration.second,
                                context.analysis.expression,
                            ) + ";",
                        )
                        return@eventLoop
                    }
                    val assignment = context.localAssignmentByValue[event.value.id]
                    if (assignment != null) {
                        val target = context.analysis.expression.values.getValue(assignment)
                        appendIndented(
                            out,
                            contentIndent,
                            expressionRenderer(bindings).renderLocalAssignment(
                                assignment,
                                event.value.id,
                                target.type,
                                context.analysis.expression,
                            ) + ";",
                        )
                        return@eventLoop
                    }
                    appendIndented(
                        out,
                        contentIndent,
                        expressionRenderer(bindings).renderDefinition(event.value, context.analysis.expression) + ";",
                    )
                }

                is BlockEvent.Statement -> {
                    if (!includeControl && event.statement.isStructuralControl()) return@eventLoop
                    if (suppressMonitor && event.statement is ExpressionStatement.Monitor) return@eventLoop
                    if (event.statement.isRedundantConstructorInvocation(context)) return@eventLoop
                    if (tailPosition && index == events.lastIndex && event.statement is ExpressionStatement.Return && event.statement.value == null) return@eventLoop
                    val rendered = renderStatement(event.statement, block, context, bindings)
                    appendIndented(out, contentIndent, rendered + ";")
                }
            }
        }
        if (fallback) renderFallbackTransfers(block, context, indent + 1, out)
    }

    private fun renderStatement(statement: ExpressionStatement, block: BasicBlockId, context: RenderContext, bindings: SourceValueBindings): String {
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
                        append(expressionRenderer(bindings).renderCondition(condition, context.analysis.expression))
                        append(")")
                        taken?.let { append(" goto B${it.value}") }
                        fallthrough?.let { append(" else goto B${it.value}") }
                    }
                }
            }

            is ExpressionStatement.Switch -> {
                val cases = outgoing.filter { it.kind == ControlFlowEdgeKind.SWITCH }.joinToString { edge -> edge.switchValue?.let { "$it: B${edge.to.value}" } ?: "default: B${edge.to.value}" }
                "switch (${expressionRenderer(bindings).renderValue(statement.selector, context.analysis.expression)}) -> [$cases]"
            }

            else -> expressionRenderer(bindings).renderStatement(
                statement,
                context.analysis.expression,
                context.method.raw.type.returnType,
            )
        }
    }

    private fun renderFallbackTransfers(block: BasicBlockId, context: RenderContext, indent: Int, out: StringBuilder) {
        val outgoing = context.outgoingByBlock[block].orEmpty()
        val terminalControl = context.eventsByBlock[block].orEmpty().lastOrNull()?.let { it as? BlockEvent.Statement }?.statement
        if (terminalControl is ExpressionStatement.Branch || terminalControl is ExpressionStatement.Switch) return
        outgoing.filter { it.to in context.analysis.optimization.controlFlow.blocks }.forEach { edge -> appendIndented(out, indent, "goto B${edge.to.value};") }
    }

    private fun renderArmExit(exit: StructuredArmExit, indent: Int, out: StringBuilder) {
        when (exit.kind) {
            StructuredArmExitKind.RETURN_OR_THROW -> Unit
            StructuredArmExitKind.CONTINUE -> appendIndented(out, indent, "continue;")
            StructuredArmExitKind.BREAK -> appendIndented(out, indent, "break;")
        }
    }

    private fun renderSwitchTransfers(transfers: List<StructuredRegionTransfer>, indent: Int, out: StringBuilder) {
        transfers.forEach { transfer ->
            when (transfer.kind) {
                StructuredRegionTransferKind.BREAK_SWITCH, StructuredRegionTransferKind.BREAK_LOOP -> appendIndented(out, indent, "break;")
                StructuredRegionTransferKind.CONTINUE_LOOP -> appendIndented(out, indent, "continue;")
                StructuredRegionTransferKind.CASE_FALLTHROUGH, StructuredRegionTransferKind.NORMAL_SWITCH_COMPLETION, StructuredRegionTransferKind.RETURN_OR_THROW -> Unit
            }
        }
    }

    private fun expressionRenderer(bindings: SourceValueBindings) = SourceExpressionRenderer(deobfuscation, bindings)

    private fun renderCondition(condition: StructuredCondition, analysis: MethodAnalysis, bindings: SourceValueBindings): String = when (condition) {
        is StructuredCondition.Atomic -> expressionRenderer(bindings).renderCondition(condition.condition, analysis.expression)
        is StructuredCondition.And -> condition.terms.joinToString(" && ") { term ->
            val rendered = renderCondition(term, analysis, bindings)
            if (term is StructuredCondition.Or) "($rendered)" else rendered
        }

        is StructuredCondition.Or -> condition.terms.joinToString(" || ") { term ->
            val rendered = renderCondition(term, analysis, bindings)
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

    private fun ExpressionStatement.isStructuralControl(): Boolean = this is ExpressionStatement.Branch || this is ExpressionStatement.Switch || this is ExpressionStatement.Monitor

    private fun ExpressionStatement.isRedundantConstructorInvocation(context: RenderContext): Boolean {
        val call = this as? ExpressionStatement.Call ?: return false
        if (call.method.invocationKind != io.github.relvl.deobscura.expression.InvocationKind.SPECIAL || call.method.name != "<init>") {
            return false
        }
        if (call.arguments.isNotEmpty() || call.method.descriptor != "()V") return false
        val receiverOrigin = call.receiver?.let { context.analysis.expression.values[it]?.node as? ExpressionNode.Root }?.origin
        if (receiverOrigin !is io.github.relvl.deobscura.analysis.ValueOrigin.This) return false

        return if (call.method.ownerInternalName == context.method.ownerInternalName) {
            context.method.hasTrivialNoArgConstructor && context.method.raw.descriptor != "()V"
        } else {
            true
        }
    }

    private fun RawMethod.isTrivialConstructor(owner: RawClass): Boolean {
        val instructions = code?.instructions?.filterNot { it is RawNopInstruction } ?: return false
        if (instructions.size != 3) return false
        val loadThis = instructions[0] as? RawLocalInstruction ?: return false
        val superCall = instructions[1] as? RawInvokeInstruction ?: return false
        val returnInstruction = instructions[2] as? RawReturnInstruction ?: return false
        return loadThis.operation == LocalOperation.LOAD && loadThis.slot == 0 && loadThis.type == JvmComputationalType.REFERENCE && superCall.opcode.mnemonic == "invokespecial" && superCall.owner == owner.superName && superCall.name == "<init>" && superCall.descriptor == "()V" && returnInstruction.type == JvmComputationalType.VOID
    }

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

    private fun formatType(type: JvmType): String = type.formatTypeName(deobfuscation::sourceClassName)

    private data class RenderMethodContext(
        val ownerInternalName: String,
        val raw: RawMethod,
        val hasTrivialNoArgConstructor: Boolean,
    ) {
        val allowsImplicitReturn: Boolean
            get() = raw.name == "<init>" || raw.name == "<clinit>" || raw.type.returnType == JvmType.VoidType
    }

    private class RenderContext(val analysis: MethodAnalysis, val method: RenderMethodContext) {
        val outgoingByBlock: Map<BasicBlockId, List<ControlFlowEdge>> = analysis.optimization.controlFlow.edges.groupBy { it.from }
        val phisByBlock: Map<BasicBlockId, List<ExpressionValue>> = analysis.expression.values.values.filter { it.node is ExpressionNode.Phi }.groupBy { (it.node as ExpressionNode.Phi).blockId }
        val eventsByBlock: Map<BasicBlockId, List<BlockEvent>> = buildEvents(analysis)
        val loopConditionInlineValuesByHeader: Map<BasicBlockId, Set<ValueId>> = buildLoopConditionInlineValues(analysis)
        val loopConditionInlineValues: Set<ValueId> = loopConditionInlineValuesByHeader.values.flatten().toSet()
        val localDeclarationByInitializer: Map<ValueId, Pair<ValueId, ValueId>> = buildMap {
            analysis.sourceLocals.conditionalAssignments.forEach { (phi, assignment) ->
                put(assignment.initialValue, phi to assignment.initialValue)
            }
            analysis.sourceLocals.loopAssignments.forEach { (phi, assignment) ->
                put(assignment.initialValue, phi to assignment.initialValue)
            }
            analysis.sourceLocals.localFamilies.values.forEach { family ->
                put(family.initialValue, family.target to family.initialValue)
            }
        }
        val localAssignmentByValue: Map<ValueId, ValueId> = buildMap {
            analysis.sourceLocals.conditionalAssignments.forEach { (phi, assignment) ->
                put(assignment.assignedValue, phi)
            }
            analysis.sourceLocals.twoArmAssignments.forEach { (phi, assignment) ->
                put(assignment.thenValue, phi)
                put(assignment.elseValue, phi)
            }
            analysis.sourceLocals.loopAssignments.forEach { (phi, assignment) ->
                put(assignment.updatedValue, phi)
            }
            analysis.sourceLocals.localFamilies.values.forEach { family ->
                family.assignedValues.forEach { put(it, family.target) }
            }
        }
        val familyPhiValues: Set<ValueId> = analysis.sourceLocals.localFamilies.values.flatMapTo(linkedSetOf()) { it.phiValues }
        val sourceLocalBindings: Map<ValueId, ValueId> = buildMap {
            analysis.sourceLocals.localFamilies.values.forEach { family ->
                family.phiValues.forEach { put(it, family.target) }
                put(family.initialValue, family.target)
                family.assignedValues.forEach { put(it, family.target) }
            }
        }
        val hoistedValuesByRegionHeader: Map<BasicBlockId, List<ValueId>> = buildHoistedValues(
            analysis,
            localDeclarationByInitializer.keys + localAssignmentByValue.keys + sourceLocalBindings.keys,
        )
        val hoistedValues: Set<ValueId> = hoistedValuesByRegionHeader.values.flatten().toSet()
        val twoArmAssignmentsByHeader: Map<BasicBlockId, List<ValueId>> = analysis.sourceLocals.twoArmAssignments.entries.groupBy(
            keySelector = { it.value.header },
            valueTransform = { it.key },
        )

        companion object {
            /**
             * Values evaluated in a loop header must remain part of the repeated condition. The
             * generic materializer intentionally keeps effectful calls explicit before structuring;
             * once a natural loop is known, a single-use instruction value consumed only by that
             * header's branch can safely be rendered directly in the condition instead.
             */
            private fun buildLoopConditionInlineValues(analysis: MethodAnalysis): Map<BasicBlockId, Set<ValueId>> {
                val instructionToBlock = instructionToBlock(analysis)
                val operationsByIndex = analysis.ssa.operations.associateBy { it.instructionIndex }
                return buildMap {
                    analysis.structuredControlFlow.regions.filterIsInstance<StructuredRegion.While>().forEach { region ->
                        val values = structuredConditionInputs(region.condition).filterTo(linkedSetOf()) { value ->
                            if (value in analysis.expression.materialization.inlineValues) return@filterTo false
                            val definition = analysis.ssa.values[value] as? io.github.relvl.deobscura.analysis.SsaValueDefinition.Instruction ?: return@filterTo false
                            val definitionBlock = instructionToBlock[definition.instructionIndex] ?: return@filterTo false
                            val uses = analysis.ssa.uses[value].orEmpty()
                            val use = uses.singleOrNull() as? io.github.relvl.deobscura.analysis.SsaValueUse.Operation ?: return@filterTo false
                            val useBlock = instructionToBlock[use.instructionIndex] ?: return@filterTo false
                            if (definitionBlock != useBlock) return@filterTo false
                            val operation = operationsByIndex[use.instructionIndex] ?: return@filterTo false
                            operation.instruction is RawBranchInstruction
                        }
                        if (values.isNotEmpty()) put(region.header, values)
                    }
                }
            }

            private fun structuredConditionInputs(condition: StructuredCondition): Set<ValueId> = when (condition) {
                is StructuredCondition.Atomic -> conditionInputs(condition.condition).toSet()
                is StructuredCondition.And -> condition.terms.flatMapTo(linkedSetOf(), ::structuredConditionInputs)
                is StructuredCondition.Or -> condition.terms.flatMapTo(linkedSetOf(), ::structuredConditionInputs)
            }

            private fun buildHoistedValues(analysis: MethodAnalysis, projectedValues: Set<ValueId>): Map<BasicBlockId, List<ValueId>> {
                val instructionToBlock = instructionToBlock(analysis)
                val usesByValue = buildMap<ValueId, MutableSet<BasicBlockId>> {
                    fun record(value: ValueId, block: BasicBlockId) {
                        getOrPut(value) { linkedSetOf() } += block
                    }

                    analysis.expression.values.values.forEach { value ->
                        val useBlock = when (val node = value.node) {
                            is ExpressionNode.Phi -> node.blockId
                            else -> value.instructionIndices.lastOrNull()?.let(instructionToBlock::get)
                        } ?: return@forEach
                        expressionInputs(value.node).forEach { record(it, useBlock) }
                    }
                    analysis.expression.statements.forEach { statement ->
                        val block = instructionToBlock[statement.instructionIndex] ?: return@forEach
                        statementInputs(statement).forEach { record(it, block) }
                    }
                }
                val definitionBlock = analysis.expression.values.values.mapNotNull { value ->
                    val block = value.instructionIndices.lastOrNull()?.let(instructionToBlock::get) ?: return@mapNotNull null
                    value.id to block
                }.toMap()
                val exceptionRegions = analysis.structuredControlFlow.regions.mapNotNull { region ->
                    when (region) {
                        is StructuredRegion.TryCatch -> ExceptionScope(region.header, region.tryBlocks, region.coveredBlocks)
                        is StructuredRegion.TryFinally -> ExceptionScope(region.header, region.tryBlocks, region.coveredBlocks)
                        is StructuredRegion.TryCatchFinally -> ExceptionScope(region.header, region.tryBlocks, region.coveredBlocks)
                        else -> null
                    }
                }
                val result = linkedMapOf<BasicBlockId, MutableList<ValueId>>()
                definitionBlock.forEach { (value, block) ->
                    if (value in projectedValues || value in analysis.expression.materialization.inlineValues || value in analysis.sourceLocals.suppressedDefinitions) return@forEach
                    val uses = usesByValue[value].orEmpty()
                    if (uses.isEmpty()) return@forEach
                    val scopes = exceptionRegions.filter { block in it.tryBlocks && uses.any { use -> use !in it.tryBlocks } }
                    if (scopes.isEmpty()) return@forEach
                    val scope = scopes.maxByOrNull { it.coveredBlocks.size } ?: return@forEach
                    result.getOrPut(scope.header) { mutableListOf() } += value
                }
                return result.mapValues { (_, values) ->
                    values.sortedBy { value -> analysis.expression.values.getValue(value).instructionIndices.lastOrNull() ?: Int.MAX_VALUE }
                }
            }

            private fun instructionToBlock(analysis: MethodAnalysis): Map<Int, BasicBlockId> = buildMap {
                analysis.graph.blocks.forEach { block ->
                    for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
                }
            }

            private fun expressionInputs(node: ExpressionNode): List<ValueId> = when (node) {
                is ExpressionNode.Root, is ExpressionNode.Constant, is ExpressionNode.NewObject -> emptyList()
                is ExpressionNode.Phi -> node.inputs.map { it.value }
                is ExpressionNode.Unary -> listOf(node.operand)
                is ExpressionNode.Binary -> listOf(node.left, node.right)
                is ExpressionNode.Increment -> listOf(node.operand)
                is ExpressionNode.ThreeWayCompare -> listOf(node.left, node.right)
                is ExpressionNode.Conversion -> listOf(node.operand)
                is ExpressionNode.FieldRead -> listOfNotNull(node.receiver)
                is ExpressionNode.ArrayRead -> listOf(node.array, node.index)
                is ExpressionNode.ArrayLength -> listOf(node.array)
                is ExpressionNode.Call -> listOfNotNull(node.receiver) + node.arguments
                is ExpressionNode.DynamicCall -> node.arguments
                is ExpressionNode.ConstructObject -> node.arguments
                is ExpressionNode.NewArray -> node.dimensions
                is ExpressionNode.Cast -> listOf(node.operand)
                is ExpressionNode.InstanceOf -> listOf(node.operand)
                is ExpressionNode.Raw -> node.inputs
            }

            private fun statementInputs(statement: ExpressionStatement): List<ValueId> = when (statement) {
                is ExpressionStatement.FieldWrite -> listOfNotNull(statement.receiver) + statement.value
                is ExpressionStatement.ArrayWrite -> listOf(statement.array, statement.index, statement.value)
                is ExpressionStatement.Call -> listOfNotNull(statement.receiver) + statement.arguments
                is ExpressionStatement.DynamicCall -> statement.arguments
                is ExpressionStatement.Return -> listOfNotNull(statement.value)
                is ExpressionStatement.Throw -> listOf(statement.value)
                is ExpressionStatement.Monitor -> listOf(statement.value)
                is ExpressionStatement.Branch -> statement.condition?.let(::conditionInputs).orEmpty()
                is ExpressionStatement.Switch -> listOf(statement.selector)
                is ExpressionStatement.Raw -> statement.inputs
            }

            private fun conditionInputs(condition: io.github.relvl.deobscura.expression.BranchCondition): List<ValueId> =
                listOf(condition.left) + ((condition.right as? io.github.relvl.deobscura.expression.BranchOperand.Value)?.value?.let(::listOf).orEmpty())

            private data class ExceptionScope(
                val header: BasicBlockId,
                val tryBlocks: Set<BasicBlockId>,
                val coveredBlocks: Set<BasicBlockId>,
            )

            private fun buildEvents(analysis: MethodAnalysis): Map<BasicBlockId, List<BlockEvent>> {
                val instructionToBlock = buildMap<Int, BasicBlockId> {
                    analysis.graph.blocks.forEach { block ->
                        for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) put(index, block.id)
                    }
                }
                return buildMap<BasicBlockId, MutableList<BlockEvent>> {
                    analysis.expression.values.values.filter { it.instructionIndices.isNotEmpty() && it.id !in analysis.expression.materialization.inlineValues }.forEach { value ->
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

        data class Value(override val instructionIndex: Int, val value: ExpressionValue) : BlockEvent {
            override val order = 0
        }

        data class Statement(override val instructionIndex: Int, val statement: ExpressionStatement) : BlockEvent {
            override val order = 1
        }
    }

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
        const val ACC_PROTECTED = 0x0004
        const val ACC_STATIC = 0x0008
        const val ACC_FINAL = 0x0010
        const val ACC_BRIDGE = 0x0040
        const val ACC_SYNCHRONIZED = 0x0020
        const val ACC_NATIVE = 0x0100
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
        const val ACC_ANNOTATION = 0x2000
        const val ACC_ENUM = 0x4000
    }
}

data class SourceMethodKey(val name: String, val descriptor: String)
