package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId

/** Proven enclosing-region facts available to nested recognizers. */
internal data class LoopFlowContext(
    val loop: StructuredRegion.While,
    val continueTargets: Set<BasicBlockId>,
)
