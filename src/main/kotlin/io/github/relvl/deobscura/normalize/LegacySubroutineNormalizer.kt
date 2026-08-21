package io.github.relvl.deobscura.normalize

import io.github.relvl.deobscura.cfg.*
import io.github.relvl.deobscura.raw.*
import java.lang.constant.ConstantDescs
import java.util.*

/**
 * Expands legacy JSR/RET subroutines into ordinary control flow for later analyses.
 *
 * The original RawCode is never mutated. Each active JSR call stack gets its own copy of every
 * visited basic block and RET becomes a GOTO to the concrete return site. JSR itself is replaced
 * with ACONST_NULL + GOTO: the null value preserves the original return-address stack slot for any
 * ASTORE on the subroutine entry path, while RET no longer consumes that local after expansion.
 */
class LegacySubroutineNormalizer(
    private val graphBuilder: ControlFlowGraphBuilder = ControlFlowGraphBuilder(),
) {
    fun normalize(code: RawCode): LegacySubroutineNormalizationResult {
        if (!code.hasLegacySubroutines()) {
            return LegacySubroutineNormalizationResult(
                code = code,
                jsrCallSiteCount = 0,
                clonedBlockCount = 0,
                normalizedInstructionCount = code.instructions.size,
                provenance = null,
            )
        }

        val graph = graphBuilder.build(code)
        val entryBlock = requireNotNull(graph.entryBlock) { "Legacy subroutine method has no entry block." }
        val blockByInstruction = blockByInstruction(graph, code.instructions.size)
        val labelPositions = code.labels.associate { it.id to it.instructionIndex }
        val ordinaryEdgesByBlock = graph.edges.filter { it.kind != ControlFlowEdgeKind.EXCEPTION }.groupBy { it.from }
        val exceptionEdgesByBlock = graph.edges.filter { it.kind == ControlFlowEdgeKind.EXCEPTION }.groupBy { it.from }

        val root = BlockInstance(entryBlock, LegacyContext.EMPTY)
        val discovered = linkedSetOf(root)
        val queue = ArrayDeque<BlockInstance>()
        queue += root
        val jsrCallSiteCount = code.instructions.count { instruction ->
            instruction is RawBranchInstruction && instruction.opcode.mnemonic in LEGACY_JSR_OPCODES
        }

        fun blockForInstruction(index: Int): BasicBlockId {
            require(index in blockByInstruction.indices) {
                "Instruction $index is outside 0..${blockByInstruction.lastIndex}."
            }
            return BasicBlockId(blockByInstruction[index])
        }

        fun targetBlock(label: RawLabelId): BasicBlockId {
            val index = requireNotNull(labelPositions[label]) { "Unknown label ${label.value}." }
            require(index in code.instructions.indices) {
                "Branch label ${label.value} targets instruction $index outside the instruction stream."
            }
            return blockForInstruction(index)
        }

        fun discover(instance: BlockInstance) {
            if (discovered.add(instance)) queue += instance
        }

        while (queue.isNotEmpty()) {
            val instance = queue.removeFirst()
            val block = graph.block(instance.block)
            when (val terminator = code.instructions[block.endInstructionIndexExclusive - 1]) {
                is RawRetInstruction -> {
                    val frame = instance.context.current ?: throw LegacySubroutineNormalizationException(
                        "RET in block ${block.id.value} executes outside a JSR context.",
                    )
                    discover(BlockInstance(blockForInstruction(frame.returnInstructionIndex), instance.context.exit()))
                }

                is RawBranchInstruction -> if (terminator.opcode.mnemonic in LEGACY_JSR_OPCODES) {
                    val returnInstructionIndex = block.endInstructionIndexExclusive
                    if (returnInstructionIndex !in code.instructions.indices) {
                        throw LegacySubroutineNormalizationException(
                            "JSR at instruction ${block.endInstructionIndexExclusive - 1} has no return site.",
                        )
                    }
                    val target = targetBlock(terminator.target)
                    val nested = instance.context.enter(
                        LegacyFrame(callSiteInstructionIndex = block.endInstructionIndexExclusive - 1, returnInstructionIndex = returnInstructionIndex),
                    )
                    discover(BlockInstance(target, nested))
                } else {
                    ordinaryEdgesByBlock[instance.block].orEmpty().forEach { edge ->
                        discover(BlockInstance(edge.to, instance.context))
                    }
                }

                else -> ordinaryEdgesByBlock[instance.block].orEmpty().forEach { edge ->
                    discover(BlockInstance(edge.to, instance.context))
                }
            }

            exceptionEdgesByBlock[instance.block].orEmpty().forEach { edge ->
                discover(BlockInstance(edge.to, instance.context))
            }
        }

        val orderedInstances = discovered.toList()
        val instanceLabels = orderedInstances.associateWith { RawLabelId(0) }.toMutableMap()
        var nextLabelId = 0
        orderedInstances.forEach { instanceLabels[it] = RawLabelId(nextLabelId++) }

        val instructions = mutableListOf<RawInstruction>()
        val instructionOrigins = mutableListOf<LegacyInstructionOrigin>()
        val labels = mutableListOf<RawLabel>()
        val lineNumbers = mutableListOf<RawLineNumber>()
        val emittedRanges = mutableMapOf<BlockInstance, EmittedRange>()

        fun remapTarget(label: RawLabelId, context: LegacyContext): RawLabelId {
            val target = targetBlock(label)
            return requireNotNull(instanceLabels[BlockInstance(target, context)]) {
                "Target block ${target.value} was not discovered for legacy context $context."
            }
        }

        fun ordinaryEdges(instance: BlockInstance): List<ControlFlowEdge> = ordinaryEdgesByBlock[instance.block].orEmpty()

        fun recordOrigin(
            instance: BlockInstance,
            originalInstructionIndex: Int?,
            kind: LegacySyntheticInstructionKind?,
        ) {
            instructionOrigins += LegacyInstructionOrigin(
                originalBlock = instance.block,
                context = instance.context.toProvenance(),
                originalInstructionIndex = originalInstructionIndex,
                syntheticKind = kind,
            )
        }

        orderedInstances.forEach { instance ->
            val block = graph.block(instance.block)
            val blockStart = instructions.size
            labels += RawLabel(
                id = instanceLabels.getValue(instance),
                instructionIndex = blockStart,
                bytecodeOffset = null,
            )

            val lineNumbersByInstruction = code.lineNumbers.groupBy { it.instructionIndex }
            for (originalIndex in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                lineNumbersByInstruction[originalIndex].orEmpty().forEach { line ->
                    lineNumbers += RawLineNumber(instructionIndex = instructions.size, line = line.line)
                }

                val instruction = code.instructions[originalIndex]

                val rewritten = when {
                    instruction is RawRetInstruction -> {
                        val frame = instance.context.current ?: throw LegacySubroutineNormalizationException(
                            "RET at instruction $originalIndex executes outside a JSR context.",
                        )
                        val destination = BlockInstance(
                            block = blockForInstruction(frame.returnInstructionIndex),
                            context = instance.context.exit(),
                        )
                        RawBranchInstruction(JvmOpcode("goto"), instanceLabels.getValue(destination))
                    }

                    instruction is RawBranchInstruction && instruction.opcode.mnemonic in LEGACY_JSR_OPCODES -> {
                        val directEdge = ordinaryEdges(instance).singleOrNull { it.kind == ControlFlowEdgeKind.JUMP } ?: throw LegacySubroutineNormalizationException(
                            "JSR block ${block.id.value} does not have exactly one direct subroutine edge.",
                        )
                        val nestedContext = instance.context.enter(
                            LegacyFrame(callSiteInstructionIndex = originalIndex, returnInstructionIndex = block.endInstructionIndexExclusive),
                        )
                        instructions += RawConstantInstruction(
                            opcode = JvmOpcode("aconst_null"),
                            type = JvmComputationalType.REFERENCE,
                            value = ConstantDescs.NULL,
                        )
                        recordOrigin(instance, originalIndex, LegacySyntheticInstructionKind.JSR_NULL_SEED)
                        RawBranchInstruction(
                            JvmOpcode("goto"),
                            instanceLabels.getValue(BlockInstance(directEdge.to, nestedContext)),
                        )
                    }

                    instruction is RawBranchInstruction -> instruction.copy(
                        target = remapTarget(instruction.target, instance.context),
                    )

                    instruction is RawSwitchInstruction -> instruction.copy(
                        defaultTarget = remapTarget(instruction.defaultTarget, instance.context),
                        cases = instruction.cases.map { case ->
                            RawSwitchCase(case.value, remapTarget(case.target, instance.context))
                        },
                    )

                    else -> instruction
                }
                instructions += rewritten
                val syntheticKind = when {
                    instruction is RawRetInstruction -> LegacySyntheticInstructionKind.RET_GOTO
                    instruction is RawBranchInstruction && instruction.opcode.mnemonic in LEGACY_JSR_OPCODES -> LegacySyntheticInstructionKind.JSR_GOTO
                    else -> null
                }
                recordOrigin(instance, originalIndex, syntheticKind)
            }

            val originalEnd = instructions.size
            val beforeFallthrough = instructions.size
            appendExplicitFallthroughIfNeeded(instance, ordinaryEdges(instance), instanceLabels, instructions)
            if (instructions.size > beforeFallthrough) {
                repeat(instructions.size - beforeFallthrough) {
                    recordOrigin(instance, null, LegacySyntheticInstructionKind.EXPLICIT_FALLTHROUGH_GOTO)
                }
            }
            emittedRanges[instance] = EmittedRange(blockStart, originalEnd)
        }

        val exceptionHandlers = mutableListOf<RawExceptionHandler>()
        val endLabels = mutableMapOf<Int, RawLabelId>()

        fun labelAt(index: Int): RawLabelId {
            val existingStart = labels.firstOrNull { it.instructionIndex == index }?.id
            if (existingStart != null) return existingStart
            return endLabels.getOrPut(index) {
                RawLabelId(nextLabelId++).also { id ->
                    labels += RawLabel(id = id, instructionIndex = index, bytecodeOffset = null)
                }
            }
        }

        val handlers = resolveHandlers(code)
        orderedInstances.forEach { instance ->
            val block = graph.block(instance.block)
            val range = emittedRanges.getValue(instance)
            handlers.asSequence().filter { block.startInstructionIndex >= it.start && block.endInstructionIndexExclusive <= it.endExclusive }.forEach { handler ->
                val handlerBlock = blockForInstruction(handler.handlerInstructionIndex)
                val handlerInstance = BlockInstance(handlerBlock, instance.context)
                val handlerLabel = instanceLabels[handlerInstance] ?: throw LegacySubroutineNormalizationException(
                    "Exception handler block ${handlerBlock.value} was not discovered for context ${instance.context}.",
                )
                exceptionHandlers += RawExceptionHandler(
                    tryStart = labelAt(range.start),
                    tryEnd = labelAt(range.originalEndExclusive),
                    handler = handlerLabel,
                    catchType = handler.catchType,
                )
            }
        }

        val normalized = RawCode(
            maxStack = code.maxStack,
            maxLocals = code.maxLocals,
            bytecodeLength = null,
            instructions = instructions,
            labels = labels.sortedBy { it.id.value },
            exceptionHandlers = exceptionHandlers.distinct(),
            lineNumbers = lineNumbers,
        )
        check(!normalized.hasLegacySubroutines()) { "Normalizer left JSR/RET instructions in the output." }
        check(instructionOrigins.size == normalized.instructions.size) {
            "Legacy provenance has ${instructionOrigins.size} origin(s) for ${normalized.instructions.size} normalized instruction(s)."
        }
        graphBuilder.build(normalized)

        return LegacySubroutineNormalizationResult(
            code = normalized,
            jsrCallSiteCount = jsrCallSiteCount,
            clonedBlockCount = orderedInstances.groupingBy { it.block }.eachCount().values.sumOf { count -> (count - 1).coerceAtLeast(0) },
            normalizedInstructionCount = normalized.instructions.size,
            provenance = LegacySubroutineProvenance(instructionOrigins),
        )
    }

    private fun appendExplicitFallthroughIfNeeded(
        instance: BlockInstance,
        ordinaryEdges: List<ControlFlowEdge>,
        instanceLabels: Map<BlockInstance, RawLabelId>,
        instructions: MutableList<RawInstruction>,
    ) {
        val originalTerminator = instructions.lastOrNull() ?: return
        when (originalTerminator) {
            is RawSwitchInstruction -> Unit
            is RawRetInstruction -> error("RET must already be normalized.")
            is RawBranchInstruction -> {
                if (originalTerminator.opcode.mnemonic in UNCONDITIONAL_JUMPS) return
                val fallthrough = ordinaryEdges.singleOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH } ?: return
                instructions += RawBranchInstruction(
                    JvmOpcode("goto"),
                    instanceLabels.getValue(BlockInstance(fallthrough.to, instance.context)),
                )
            }

            else -> {
                val fallthrough = ordinaryEdges.singleOrNull { it.kind == ControlFlowEdgeKind.FALLTHROUGH } ?: return
                instructions += RawBranchInstruction(
                    JvmOpcode("goto"),
                    instanceLabels.getValue(BlockInstance(fallthrough.to, instance.context)),
                )
            }
        }
    }

    private fun resolveHandlers(code: RawCode): List<ResolvedHandler> {
        val positions = code.labels.associate { it.id to it.instructionIndex }
        return code.exceptionHandlers.map { handler ->
            ResolvedHandler(
                start = requireNotNull(positions[handler.tryStart]),
                endExclusive = requireNotNull(positions[handler.tryEnd]),
                handlerInstructionIndex = requireNotNull(positions[handler.handler]),
                catchType = handler.catchType,
            )
        }
    }

    private fun blockByInstruction(graph: ControlFlowGraph, instructionCount: Int): IntArray = IntArray(instructionCount).also { result ->
        graph.blocks.forEach { block ->
            for (index in block.startInstructionIndex until block.endInstructionIndexExclusive) {
                result[index] = block.id.value
            }
        }
    }

    private fun RawCode.hasLegacySubroutines(): Boolean = instructions.any { instruction ->
        instruction is RawRetInstruction || instruction is RawBranchInstruction && instruction.opcode.mnemonic in LEGACY_JSR_OPCODES
    }

    private data class BlockInstance(
        val block: BasicBlockId,
        val context: LegacyContext,
    )

    private data class LegacyFrame(
        val callSiteInstructionIndex: Int,
        val returnInstructionIndex: Int,
    )

    private data class LegacyContext(
        val frames: List<LegacyFrame>,
    ) {
        val current: LegacyFrame?
            get() = frames.lastOrNull()

        fun enter(frame: LegacyFrame): LegacyContext {
            if (frames.size >= MAX_LEGACY_SUBROUTINE_DEPTH) {
                throw LegacySubroutineNormalizationException(
                    "Legacy subroutine nesting exceeds $MAX_LEGACY_SUBROUTINE_DEPTH levels.",
                )
            }
            return LegacyContext(frames + frame)
        }

        fun toProvenance(): LegacySubroutineContext = LegacySubroutineContext(
            frames.map { frame ->
                LegacySubroutineFrame(
                    callSiteInstructionIndex = frame.callSiteInstructionIndex,
                    returnInstructionIndex = frame.returnInstructionIndex,
                )
            },
        )

        fun exit(): LegacyContext {
            if (frames.isEmpty()) {
                throw LegacySubroutineNormalizationException("Cannot leave an empty legacy subroutine context.")
            }
            return LegacyContext(frames.dropLast(1))
        }

        companion object {
            val EMPTY = LegacyContext(emptyList())
        }
    }

    private data class EmittedRange(
        val start: Int,
        val originalEndExclusive: Int,
    )

    private data class ResolvedHandler(
        val start: Int,
        val endExclusive: Int,
        val handlerInstructionIndex: Int,
        val catchType: String?,
    )

    private companion object {
        val LEGACY_JSR_OPCODES = setOf("jsr", "jsr_w")
        val UNCONDITIONAL_JUMPS = setOf("goto", "goto_w")
        const val MAX_LEGACY_SUBROUTINE_DEPTH = 64
    }
}

class LegacySubroutineNormalizationException(message: String) : IllegalStateException(message)

data class LegacySubroutineNormalizationResult(
    val code: RawCode,
    val jsrCallSiteCount: Int,
    val clonedBlockCount: Int,
    val normalizedInstructionCount: Int,
    val provenance: LegacySubroutineProvenance?,
) {
    val changed: Boolean
        get() = jsrCallSiteCount > 0
}

data class LegacySubroutineProvenance(
    val instructionOrigins: List<LegacyInstructionOrigin>,
) {
    init {
        require(instructionOrigins.isNotEmpty())
    }

    fun originAt(instructionIndex: Int): LegacyInstructionOrigin? = instructionOrigins.getOrNull(instructionIndex)
}

data class LegacyInstructionOrigin(
    val originalBlock: BasicBlockId,
    val context: LegacySubroutineContext,
    val originalInstructionIndex: Int?,
    val syntheticKind: LegacySyntheticInstructionKind?,
)

data class LegacySubroutineContext(val frames: List<LegacySubroutineFrame>) {
    val parent: LegacySubroutineContext?
        get() = if (frames.isEmpty()) null else LegacySubroutineContext(frames.dropLast(1))
}

data class LegacySubroutineFrame(
    val callSiteInstructionIndex: Int,
    val returnInstructionIndex: Int,
)

enum class LegacySyntheticInstructionKind {
    JSR_NULL_SEED, JSR_GOTO, RET_GOTO, EXPLICIT_FALLTHROUGH_GOTO,
}
