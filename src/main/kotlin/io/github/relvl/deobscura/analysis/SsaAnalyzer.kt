package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawLocalInstruction

class SsaAnalyzer {
    fun analyze(
        graph: ControlFlowGraph,
        valueFlow: ValueFlowAnalysis,
    ): SsaAnalysis {
        val mergeDefinitions = valueFlow.values.values.filterIsInstance<ValueDefinition.Merge>().associateBy { it.id }
        val aliases = mutableMapOf<ValueId, ValueId>()
        val directInputs = mergeDefinitions.mapValues { (_, definition) ->
            directPredecessorInputs(graph, valueFlow, definition) ?: definition.inputs
        }

        fun resolve(id: ValueId): ValueId {
            var current = id
            val seen = mutableSetOf<ValueId>()
            while (true) {
                val next = aliases[current] ?: return current
                if (!seen.add(current)) {
                    throw SsaInconsistencyException("Cyclic SSA value alias involving ${current.value}.")
                }
                current = next
            }
        }

        // Frame analysis carries the union of origins downstream. Collapse those propagated merge
        // placeholders by comparing the values arriving from the immediate CFG predecessors.
        var changed: Boolean
        do {
            changed = false
            mergeDefinitions.forEach { (id, _) ->
                if (id in aliases) return@forEach
                val inputs = directInputs.getValue(id).map(::resolve)
                val meaningfulInputs = inputs.filter { it != id }.distinct()
                if (meaningfulInputs.size == 1) {
                    aliases[id] = meaningfulInputs.single()
                    changed = true
                }
            }
        } while (changed)

        val values = linkedMapOf<ValueId, SsaValueDefinition>()
        val phiNodes = mutableListOf<SsaPhiNode>()

        valueFlow.values.forEach { (id, definition) ->
            if (id in aliases) return@forEach
            values[id] = when (definition) {
                is ValueDefinition.Root -> SsaValueDefinition.Root(id, definition.kind, definition.origin)
                is ValueDefinition.Instruction -> SsaValueDefinition.Instruction(
                    id,
                    definition.kind,
                    definition.instructionIndex,
                )
                is ValueDefinition.Merge -> {
                    val location = when (val site = definition.site) {
                        is ValueMergeSite.Local -> SsaPhiLocation.Local(site.slot)
                        is ValueMergeSite.Stack -> SsaPhiLocation.Stack(site.index)
                    }
                    val inputs = directInputs.getValue(id)
                        .map(::resolve)
                        .filter { it != id }
                    if (inputs.distinct().size <= 1) {
                        throw SsaInconsistencyException(
                            "Non-aliased phi ${id.value} in block ${definition.site.blockId.value} has fewer than two distinct inputs.",
                        )
                    }
                    phiNodes += SsaPhiNode(id, definition.site.blockId, location, inputs)
                    SsaValueDefinition.Phi(id, definition.kind, definition.site.blockId, location, inputs)
                }
            }
        }

        val operations = valueFlow.operations
            .asSequence()
            .filterNot { it.instruction is RawLocalInstruction }
            .map { operation ->
                operation.copy(inputs = operation.inputs.map(::resolve))
            }
            .toList()
        val eliminatedLocalInstructionCount = valueFlow.operations.size - operations.size
        val uses = linkedMapOf<ValueId, MutableList<SsaValueUse>>()

        fun registerUse(value: ValueId, use: SsaValueUse) {
            if (value !in values) {
                throw SsaInconsistencyException("Use refers to undefined value ${value.value}.")
            }
            uses.getOrPut(value) { mutableListOf() } += use
        }

        operations.forEach { operation ->
            operation.output?.let { output ->
                if (output !in values) {
                    throw SsaInconsistencyException(
                        "Instruction ${operation.instructionIndex} defines unknown value ${output.value}.",
                    )
                }
            }
            operation.inputs.forEachIndexed { inputIndex, input ->
                registerUse(input, SsaValueUse.Operation(operation.instructionIndex, inputIndex))
            }
        }
        phiNodes.forEach { phi ->
            phi.inputs.forEachIndexed { inputIndex, input ->
                registerUse(input, SsaValueUse.Phi(phi.output, inputIndex))
            }
        }

        val duplicatePhiSites = phiNodes
            .groupBy { it.blockId to it.location }
            .filterValues { it.size > 1 }
        if (duplicatePhiSites.isNotEmpty()) {
            val site = duplicatePhiSites.keys.first()
            throw SsaInconsistencyException("Multiple phi nodes occupy block ${site.first.value} at ${site.second}.")
        }

        return SsaAnalysis(
            values = values,
            operations = operations,
            phiNodes = phiNodes,
            uses = uses.mapValues { it.value.toList() },
            eliminatedLocalInstructionCount = eliminatedLocalInstructionCount,
        )
    }

    private fun directPredecessorInputs(
        graph: ControlFlowGraph,
        valueFlow: ValueFlowAnalysis,
        definition: ValueDefinition.Merge,
    ): List<ValueId>? {
        val site = definition.site
        val incomingEdges = graph.edges.filter { it.to == site.blockId }
        if (incomingEdges.isEmpty()) return null

        // Exceptional flow can leave a protected block at any instruction, so a block-exit state
        // is not a faithful representation of the value entering a handler. Keep the precise
        // frame-origin merge for handlers until value-flow records per-instruction exceptional states.
        if (incomingEdges.any { it.kind == ControlFlowEdgeKind.EXCEPTION }) return null

        return incomingEdges.map { edge ->
            when (site) {
                is ValueMergeSite.Local -> valueFlow.blockExitLocals[edge.from]?.getOrNull(site.slot)
                is ValueMergeSite.Stack -> valueFlow.blockExitStacks[edge.from]?.getOrNull(site.index)
            } ?: return null
        }
    }
}

class SsaInconsistencyException(message: String) : IllegalStateException(message)
