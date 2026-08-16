package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.BasicBlockId

data class FrameAnalysis(
    val entryFrames: Map<BasicBlockId, FrameState>,
    val exitFrames: Map<BasicBlockId, FrameState>,
    val frameMergeCount: Long,
    val valueMergeCount: Long,
)
