package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.raw.*

/** Bytecode shape shared by ordinary and fragmented synchronized recognition. */
internal data class MonitorCleanupHandlerShape(
    val monitorExitInstructionIndex: Int,
    val throwInstructionIndex: Int,
)

/**
 * Proves either the canonical stored-exception cleanup or the stack-preserved variant.
 */
internal fun recognizeHandlerShape(
    instructions: List<RawInstruction>,
    handlerStart: Int,
    monitorSlot: Int,
): MonitorCleanupHandlerShape? {
    val first = instructions.getOrNull(handlerStart) as? RawLocalInstruction ?: return null
    if (first.operation == LocalOperation.STORE && first.type == JvmComputationalType.REFERENCE) {
        val monitorLoad = instructions.getOrNull(handlerStart + 1) as? RawLocalInstruction ?: return null
        val monitorExit = instructions.getOrNull(handlerStart + 2) as? RawMonitorInstruction ?: return null
        val exceptionReload = instructions.getOrNull(handlerStart + 3) as? RawLocalInstruction ?: return null
        if (instructions.getOrNull(handlerStart + 4) !is RawThrowInstruction) return null
        if (monitorLoad.operation != LocalOperation.LOAD || monitorLoad.slot != monitorSlot) return null
        if (monitorExit.opcode.mnemonic != "monitorexit") return null
        if (exceptionReload.operation != LocalOperation.LOAD || exceptionReload.slot != first.slot) return null
        return MonitorCleanupHandlerShape(handlerStart + 2, handlerStart + 4)
    }

    if (first.operation != LocalOperation.LOAD || first.slot != monitorSlot) return null
    val monitorExit = instructions.getOrNull(handlerStart + 1) as? RawMonitorInstruction ?: return null
    if (monitorExit.opcode.mnemonic != "monitorexit") return null
    if (instructions.getOrNull(handlerStart + 2) !is RawThrowInstruction) return null
    return MonitorCleanupHandlerShape(handlerStart + 1, handlerStart + 2)
}

/** Recovers the synthetic local used to hold the monitor value before monitorenter. */
internal fun monitorSlotBeforeEnter(
    instructions: List<RawInstruction>,
    monitorEnterInstructionIndex: Int,
): Int? {
    val immediate = instructions.getOrNull(monitorEnterInstructionIndex - 1) as? RawLocalInstruction
    if (immediate?.operation == LocalOperation.STORE && immediate.type == JvmComputationalType.REFERENCE) {
        return immediate.slot
    }
    if (immediate?.operation != LocalOperation.LOAD || immediate.type != JvmComputationalType.REFERENCE) return null
    val store = instructions.getOrNull(monitorEnterInstructionIndex - 2) as? RawLocalInstruction ?: return null
    return store.slot.takeIf {
        store.operation == LocalOperation.STORE && store.type == JvmComputationalType.REFERENCE && store.slot == immediate.slot
    }
}
