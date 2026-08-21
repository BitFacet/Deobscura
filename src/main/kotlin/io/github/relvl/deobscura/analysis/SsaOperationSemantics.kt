package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.raw.*

/** Observable effects relevant to SSA rewrites and later expression reconstruction. */
enum class OperationEffect {
    MAY_THROW, READS_MEMORY, WRITES_MEMORY, CALLS_CODE, ALLOCATES, SYNCHRONIZES, CONTROL_FLOW, UNKNOWN,
}

data class OperationSemantics(
    val effects: Set<OperationEffect>,
) {
    val canDiscardWhenResultUnused: Boolean
        get() = effects.isEmpty()
}

/**
 * Central conservative semantic classification for JVM operations represented in SSA.
 *
 * Optimizers may only discard operations explicitly proven effect-free here. Unknown instructions
 * remain observable by default so extending the raw IR cannot silently make an unsafe rewrite.
 */
class SsaOperationSemantics {
    fun classify(instruction: RawInstruction): OperationSemantics = when (instruction) {
        is RawConstantInstruction -> when (instruction.type) {
            JvmComputationalType.BOOLEAN,
            JvmComputationalType.BYTE,
            JvmComputationalType.CHAR,
            JvmComputationalType.SHORT,
            JvmComputationalType.INT,
            JvmComputationalType.LONG,
            JvmComputationalType.FLOAT,
            JvmComputationalType.DOUBLE,
                -> PURE

            else -> UNKNOWN
        }

        is RawLocalInstruction,
        is RawIncrementInstruction,
        is RawConversionInstruction,
        is RawStackInstruction,
        is RawNopInstruction,
            -> PURE

        is RawOperatorInstruction -> classifyOperator(instruction.opcode.mnemonic)
        is RawArrayInstruction -> when (instruction.operation) {
            ArrayOperation.LOAD -> effects(OperationEffect.READS_MEMORY, OperationEffect.MAY_THROW)
            ArrayOperation.STORE -> effects(OperationEffect.WRITES_MEMORY, OperationEffect.MAY_THROW)
        }

        is RawFieldInstruction -> classifyField(instruction.opcode.mnemonic)
        is RawInvokeInstruction,
        is RawInvokeDynamicInstruction,
            -> effects(OperationEffect.CALLS_CODE, OperationEffect.MAY_THROW)

        is RawNewObjectInstruction -> effects(
            OperationEffect.ALLOCATES,
            OperationEffect.CALLS_CODE,
            OperationEffect.MAY_THROW,
        )

        is RawNewArrayInstruction,
        is RawNewMultiArrayInstruction,
            -> effects(OperationEffect.ALLOCATES, OperationEffect.MAY_THROW)

        is RawTypeCheckInstruction -> effects(OperationEffect.MAY_THROW)
        is RawMonitorInstruction -> effects(OperationEffect.SYNCHRONIZES, OperationEffect.MAY_THROW)
        is RawBranchInstruction,
        is RawSwitchInstruction,
        is RawReturnInstruction,
        is RawRetInstruction,
            -> effects(OperationEffect.CONTROL_FLOW)

        is RawThrowInstruction -> effects(OperationEffect.CONTROL_FLOW, OperationEffect.MAY_THROW)
        is RawUnknownInstruction -> UNKNOWN
    }

    fun canDiscardWhenResultUnused(instruction: RawInstruction): Boolean = classify(instruction).canDiscardWhenResultUnused

    private fun classifyOperator(mnemonic: String): OperationSemantics = when (mnemonic) {
        "idiv", "irem", "ldiv", "lrem" -> effects(OperationEffect.MAY_THROW)
        in PURE_OPERATOR_MNEMONICS -> PURE
        else -> UNKNOWN
    }

    private fun classifyField(mnemonic: String): OperationSemantics = when (mnemonic) {
        "getfield" -> effects(OperationEffect.READS_MEMORY, OperationEffect.MAY_THROW)
        "putfield" -> effects(OperationEffect.WRITES_MEMORY, OperationEffect.MAY_THROW)
        "getstatic" -> effects(
            OperationEffect.READS_MEMORY,
            OperationEffect.CALLS_CODE,
            OperationEffect.MAY_THROW,
        )

        "putstatic" -> effects(
            OperationEffect.WRITES_MEMORY,
            OperationEffect.CALLS_CODE,
            OperationEffect.MAY_THROW,
        )

        else -> UNKNOWN
    }

    private fun effects(vararg effects: OperationEffect) = OperationSemantics(effects.toSet())

    private companion object {
        val PURE = OperationSemantics(emptySet())
        val UNKNOWN = OperationSemantics(setOf(OperationEffect.UNKNOWN))

        val PURE_OPERATOR_MNEMONICS = setOf(
            "ineg", "lneg", "fneg", "dneg",
            "iadd", "isub", "imul", "iand", "ior", "ixor", "ishl", "ishr", "iushr",
            "ladd", "lsub", "lmul", "land", "lor", "lxor", "lshl", "lshr", "lushr",
            "fadd", "fsub", "fmul", "fdiv", "frem",
            "dadd", "dsub", "dmul", "ddiv", "drem",
            "lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg",
        )
    }
}
