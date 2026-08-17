package io.github.relvl.deobscura.analysis

/** Rebuilds and validates the SSA def-use index after a structural rewrite. */
internal fun rebuildSsaUses(
    values: Map<ValueId, SsaValueDefinition>,
    operations: List<ValueOperation>,
    phiNodes: List<SsaPhiNode>,
    context: String,
): Map<ValueId, List<SsaValueUse>> {
    val uses = linkedMapOf<ValueId, MutableList<SsaValueUse>>()

    fun register(value: ValueId, use: SsaValueUse) {
        if (value !in values) {
            throw SsaInconsistencyException("$context SSA use refers to undefined value ${value.value}.")
        }
        uses.getOrPut(value) { mutableListOf() } += use
    }

    operations.forEach { operation ->
        operation.output?.let { output ->
            if (output !in values) {
                throw SsaInconsistencyException(
                    "$context instruction ${operation.instructionIndex} defines unknown value ${output.value}.",
                )
            }
        }
        operation.inputs.forEachIndexed { inputIndex, input ->
            register(input, SsaValueUse.Operation(operation.instructionIndex, inputIndex))
        }
    }

    phiNodes.forEach { phi ->
        if (phi.output !in values) {
            throw SsaInconsistencyException("$context phi defines unknown value ${phi.output.value}.")
        }
        phi.inputs.forEachIndexed { inputIndex, input ->
            register(input.value, SsaValueUse.Phi(phi.output, input.predecessor, inputIndex))
        }
    }

    return uses.mapValues { it.value.toList() }
}
