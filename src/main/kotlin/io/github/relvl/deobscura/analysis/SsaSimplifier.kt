package io.github.relvl.deobscura.analysis

/**
 * Canonicalizes SSA value aliases and removes phi nodes that no longer merge distinct values.
 *
 * Local loads/stores and JVM stack-copy instructions have already been eliminated before this
 * stage, so copy propagation here is expressed as ValueId alias propagation. Later rewrites (for
 * example constant folding) can make a previously non-trivial phi trivial; running this pass again
 * then removes the redundant value without introducing a separate copy instruction.
 */
class SsaSimplifier {
    fun simplify(analysis: SsaAnalysis): SsaSimplificationResult {
        val aliases = mutableMapOf<ValueId, ValueId>()

        fun resolve(id: ValueId): ValueId {
            var current = id
            val seen = mutableSetOf<ValueId>()
            while (true) {
                val next = aliases[current] ?: return current
                if (!seen.add(current)) {
                    throw SsaInconsistencyException("Cyclic SSA simplification alias involving ${current.value}.")
                }
                current = next
            }
        }

        var changed: Boolean
        do {
            changed = false
            analysis.phiNodes.forEach { phi ->
                if (phi.output in aliases) return@forEach
                val distinctInputs = phi.inputs.asSequence().map { resolve(it.value) }.filter { it != phi.output }.distinct().toList()
                if (distinctInputs.size == 1) {
                    aliases[phi.output] = distinctInputs.single()
                    changed = true
                }
            }
        } while (changed)

        if (aliases.isEmpty()) {
            return SsaSimplificationResult(
                analysis = analysis,
                propagatedAliasCount = 0,
                removedPhiCount = 0,
            )
        }

        // Compress aliases before rewriting the graph so every use directly names its canonical
        // definition instead of retaining an alias chain.
        aliases.keys.toList().forEach { id -> aliases[id] = resolve(id) }

        val phiNodes = analysis.phiNodes.asSequence().filterNot { it.output in aliases }.map { phi -> phi.copy(inputs = phi.inputs.map { it.copy(value = resolve(it.value)) }) }.toList()

        phiNodes.firstOrNull { phi ->
            phi.inputs.asSequence().map { it.value }.filter { it != phi.output }.distinct().count() <= 1
        }?.let { phi ->
            throw SsaInconsistencyException(
                "SSA simplification left trivial phi ${phi.output.value} in block ${phi.blockId.value}.",
            )
        }

        val values = linkedMapOf<ValueId, SsaValueDefinition>()
        analysis.values.forEach { (id, definition) ->
            if (id in aliases) return@forEach
            values[id] = when (definition) {
                is SsaValueDefinition.Root -> definition
                is SsaValueDefinition.Instruction -> definition
                is SsaValueDefinition.Phi -> definition.copy(inputs = definition.inputs.map { it.copy(value = resolve(it.value)) })
            }
        }

        val operations = analysis.operations.map { operation ->
            operation.copy(inputs = operation.inputs.map(::resolve))
        }
        val uses = rebuildSsaUses(values, operations, phiNodes, "Simplified")
        val constants = linkedMapOf<ValueId, SsaConstant>()
        analysis.constants.forEach { (id, constant) ->
            val canonical = resolve(id)
            if (canonical !in values) return@forEach
            val previous = constants.putIfAbsent(canonical, constant)
            if (previous != null && previous != constant) {
                throw SsaInconsistencyException(
                    "SSA simplification merged conflicting constants for value ${canonical.value}.",
                )
            }
        }

        return SsaSimplificationResult(
            analysis = analysis.copy(
                values = values,
                operations = operations,
                phiNodes = phiNodes,
                uses = uses,
                constants = constants,
            ),
            propagatedAliasCount = aliases.size,
            removedPhiCount = analysis.phiNodes.size - phiNodes.size,
        )
    }

}

data class SsaSimplificationResult(
    val analysis: SsaAnalysis,
    val propagatedAliasCount: Int,
    val removedPhiCount: Int,
)
