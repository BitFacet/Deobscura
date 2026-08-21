package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId
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
            directPredecessorInputs(graph, valueFlow, definition) ?: definition.inputs.map { SsaPhiInput(it) }
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

        fun resolve(input: SsaPhiInput): SsaPhiInput = input.copy(value = resolve(input.value))

        // Frame analysis carries the union of origins downstream. Collapse those propagated merge
        // placeholders by comparing the values arriving from the immediate CFG predecessors.
        var changed: Boolean
        do {
            changed = false
            mergeDefinitions.forEach { (id, _) ->
                if (id in aliases) return@forEach
                val meaningfulInputs = directInputs.getValue(id).asSequence().map { resolve(it).value }.filter { it != id }.distinct().toList()
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
                is ValueDefinition.Root -> SsaValueDefinition.Root(id, definition.type, definition.origin)
                is ValueDefinition.Instruction -> SsaValueDefinition.Instruction(
                    id,
                    definition.type,
                    definition.instructionIndex,
                )

                is ValueDefinition.Merge -> {
                    val location = when (val site = definition.site) {
                        is ValueMergeSite.Local -> SsaPhiLocation.Local(site.slot)
                        is ValueMergeSite.Stack -> SsaPhiLocation.Stack(site.index)
                    } // Keep self inputs: on a back-edge they mean that the predecessor carries the
                    // loop-entry value through unchanged. Simplification may ignore them when
                    // deciding whether a phi is trivial, but CFG rewrites still need the mapping.
                    val inputs = directInputs.getValue(id).map(::resolve)
                    val meaningfulInputs = inputs.asSequence().map { it.value }.filter { it != id }.distinct().toList()
                    if (meaningfulInputs.size <= 1) {
                        throw SsaInconsistencyException(
                            "Non-aliased phi ${id.value} in block ${definition.site.blockId.value} has fewer than two distinct inputs.",
                        )
                    }
                    validatePhiInputs(id, definition.site.blockId, inputs)
                    phiNodes += SsaPhiNode(id, definition.site.blockId, location, inputs)
                    SsaValueDefinition.Phi(id, definition.type, definition.site.blockId, location, inputs)
                }
            }
        }

        val operations = valueFlow.operations.asSequence().filterNot { it.instruction is RawLocalInstruction }.map { operation ->
            operation.copy(inputs = operation.inputs.map(::resolve))
        }.toList()
        val eliminatedLocalInstructionCount = valueFlow.operations.size - operations.size
        val uses = rebuildSsaUses(values, operations, phiNodes, "Initial")

        val duplicatePhiSites = phiNodes.groupBy { it.blockId to it.location }.filterValues { it.size > 1 }
        if (duplicatePhiSites.isNotEmpty()) {
            val site = duplicatePhiSites.keys.first()
            throw SsaInconsistencyException("Multiple phi nodes occupy block ${site.first.value} at ${site.second}.")
        }

        return SsaAnalysis(
            values = values,
            operations = operations,
            phiNodes = phiNodes,
            uses = uses,
            eliminatedLocalInstructionCount = eliminatedLocalInstructionCount,
        )
    }

    private fun directPredecessorInputs(
        graph: ControlFlowGraph,
        valueFlow: ValueFlowAnalysis,
        definition: ValueDefinition.Merge,
    ): List<SsaPhiInput>? {
        val site = definition.site
        val incomingEdges = graph.edges.filter { it.to == site.blockId }
        if (incomingEdges.isEmpty()) return null

        // Exceptional flow can leave a protected block at any instruction, so a block-exit state
        // is not a faithful representation of the value entering a handler. Keep the precise
        // frame-origin merge for handlers until value-flow records per-instruction exceptional states.
        if (incomingEdges.any { it.kind == ControlFlowEdgeKind.EXCEPTION }) return null

        // SSA phi semantics are predecessor-based, not edge-based. A conditional or switch may
        // have several CFG edges from the same block to one target, but they all carry the same
        // block-exit SSA state and therefore form one phi input.
        return graph.block(site.blockId).predecessors.distinct().map { predecessor ->
            val value = when (site) {
                is ValueMergeSite.Local -> valueFlow.blockExitLocals[predecessor]?.getOrNull(site.slot)
                is ValueMergeSite.Stack -> valueFlow.blockExitStacks[predecessor]?.getOrNull(site.index)
            } ?: return null
            SsaPhiInput(value = value, predecessor = predecessor)
        }
    }

    private fun validatePhiInputs(id: ValueId, blockId: BasicBlockId, inputs: List<SsaPhiInput>) {
        val addressed = inputs.mapNotNull { it.predecessor }
        if (addressed.isNotEmpty() && addressed.size != inputs.size) {
            throw SsaInconsistencyException("Phi ${id.value} in block ${blockId.value} mixes predecessor and exceptional inputs.")
        }
        if (addressed.size != addressed.distinct().size) {
            throw SsaInconsistencyException("Phi ${id.value} in block ${blockId.value} has duplicate predecessor inputs.")
        }
    }
}

class SsaInconsistencyException(message: String) : IllegalStateException(message)
