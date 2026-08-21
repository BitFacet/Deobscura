package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.analysis.SsaPhiLocation
import io.github.relvl.deobscura.cfg.BasicBlockId

internal fun formatBlocks(blocks: Set<BasicBlockId>): String = blocks.sortedBy { it.value }.joinToString(prefix = "[", postfix = "]") { "B${it.value}" }

internal fun formatPhiLocation(location: SsaPhiLocation): String = when (location) {
    is SsaPhiLocation.Local -> "local[${location.slot}]"
    is SsaPhiLocation.Stack -> "stack[${location.index}]"
}
