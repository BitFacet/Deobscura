package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId

/** Proven structured-loop facts used by conditional reconstruction. */
internal data class LoopFlowContext(
    val loop: StructuredRegion.While,
    val continueTargets: Set<BasicBlockId>,
)

/**
 * Natural-loop facts independent of whether the loop can already be rendered as a source `while`.
 * This is sufficient for classifying nested region transfers such as labeled break/continue.
 */
internal data class NaturalLoopFlowContext(
    val header: BasicBlockId,
    val blocks: Set<BasicBlockId>,
    val exit: BasicBlockId?,
    val continueTargets: Set<BasicBlockId>,
) {
    fun contains(block: BasicBlockId): Boolean = block != header && block in blocks
}
