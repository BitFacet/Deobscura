package io.github.relvl.deobscura.analysis

import java.util.ArrayDeque

/**
 * Removes SSA values that do not contribute to any retained operation.
 *
 * Liveness is traced backwards from operations that must stay observable. This deliberately uses
 * mark-and-sweep rather than zero-use peeling: dead phi cycles and other mutually-referential SSA
 * values then disappear naturally instead of looking live only because they reference each other.
 */
class SsaDeadValueEliminator(
    private val operationSemantics: SsaOperationSemantics = SsaOperationSemantics(),
) {
    fun eliminate(analysis: SsaAnalysis): SsaDeadValueEliminationResult {
        val operationsByOutput = linkedMapOf<ValueId, ValueOperation>()
        analysis.operations.forEach { operation ->
            operation.output?.let { output ->
                if (output !in analysis.values) {
                    throw SsaInconsistencyException(
                        "SSA instruction ${operation.instructionIndex} defines unknown value ${output.value}.",
                    )
                }
                val previous = operationsByOutput.put(output, operation)
                if (previous != null) {
                    throw SsaInconsistencyException("SSA value ${output.value} is defined by multiple operations.")
                }
            }
            operation.inputs.forEach { input ->
                if (input !in analysis.values) {
                    throw SsaInconsistencyException("SSA use refers to undefined value ${input.value}.")
                }
            }
        }

        val phiByOutput = linkedMapOf<ValueId, SsaPhiNode>()
        analysis.phiNodes.forEach { phi ->
            if (phi.output !in analysis.values) {
                throw SsaInconsistencyException("SSA phi defines unknown value ${phi.output.value}.")
            }
            val previous = phiByOutput.put(phi.output, phi)
            if (previous != null) {
                throw SsaInconsistencyException("SSA contains multiple phi nodes defining value ${phi.output.value}.")
            }
            phi.inputs.forEach { input ->
                if (input !in analysis.values) {
                    throw SsaInconsistencyException("SSA phi use refers to undefined value ${input.value}.")
                }
            }
        }

        val liveValues = linkedSetOf<ValueId>()
        val worklist = ArrayDeque<ValueId>()

        fun mark(id: ValueId) {
            if (liveValues.add(id)) worklist.addLast(id)
        }

        // Output-less operations remain in the current SSA representation, so their inputs are
        // roots of liveness. Output-producing operations are roots only when discarding them could
        // change observable JVM behaviour.
        analysis.operations.forEach { operation ->
            val output = operation.output
            if (output == null) {
                operation.inputs.forEach(::mark)
            } else if (!operationSemantics.canDiscardWhenResultUnused(operation.instruction)) {
                mark(output)
            }
        }

        while (worklist.isNotEmpty()) {
            val id = worklist.removeFirst()
            when (analysis.values[id]
                ?: throw SsaInconsistencyException("SSA liveness refers to undefined value ${id.value}.")) {
                is SsaValueDefinition.Root -> Unit
                is SsaValueDefinition.Phi -> {
                    val phi = phiByOutput[id]
                        ?: throw SsaInconsistencyException("SSA phi definition ${id.value} has no phi node.")
                    phi.inputs.forEach(::mark)
                }

                is SsaValueDefinition.Instruction -> {
                    val operation = operationsByOutput[id]
                        ?: throw SsaInconsistencyException("SSA instruction value ${id.value} has no defining operation.")
                    operation.inputs.forEach(::mark)
                }
            }
        }

        val removedValueIds = analysis.values.keys - liveValues
        if (removedValueIds.isEmpty()) {
            return SsaDeadValueEliminationResult(analysis, 0, 0, 0)
        }

        val values = analysis.values.filterKeys { it in liveValues }
        val operations = analysis.operations.filter { operation ->
            operation.output?.let { it in liveValues } ?: true
        }
        val phiNodes = analysis.phiNodes.filter { it.output in liveValues }
        val uses = rebuildSsaUses(values, operations, phiNodes, "Dead-value elimination")
        val constants = analysis.constants.filterKeys { it in liveValues }

        return SsaDeadValueEliminationResult(
            analysis = analysis.copy(
                values = values,
                operations = operations,
                phiNodes = phiNodes,
                uses = uses,
                constants = constants,
            ),
            removedOperationCount = analysis.operations.size - operations.size,
            removedValueCount = removedValueIds.size,
            removedPhiNodeCount = analysis.phiNodes.size - phiNodes.size,
        )
    }
}

data class SsaDeadValueEliminationResult(
    val analysis: SsaAnalysis,
    val removedOperationCount: Int,
    val removedValueCount: Int,
    val removedPhiNodeCount: Int,
)
