package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId

/** True for the synthetic peer-handler entry edges emitted by some exception layouts. */
internal fun isHandlerPeerEntryTransfer(entry: BasicBlockId, target: BasicBlockId, source: BasicBlockId, scopeHandlerEntries: Set<BasicBlockId>): Boolean =
    target == entry && source in scopeHandlerEntries

/** Finds incoming CFG edges that are not owned by a region and are not explicitly permitted. */
internal fun hasExternalEntry(blocks: Set<BasicBlockId>, facts: ControlFlowFacts, allowExternalEntry: (target: BasicBlockId, source: BasicBlockId) -> Boolean): Boolean =
    blocks.any { block ->
        facts.incoming[block].orEmpty().any { edge ->
            edge.from !in blocks && !allowExternalEntry(block, edge.from)
        }
    }

/** Protected scopes may be entered from outside only through their source header. */
internal fun hasExternalProtectedEntry(header: BasicBlockId, protectedBlocks: Set<BasicBlockId>, facts: ControlFlowFacts): Boolean =
    hasExternalEntry(protectedBlocks, facts) { target, _ -> target == header }

/** Normal-flow destinations that leave a protected scope without entering one of its handlers. */
internal fun normalBoundaryTargets(protectedBlocks: Set<BasicBlockId>, handlerEntries: Set<BasicBlockId>, facts: ControlFlowFacts): Set<BasicBlockId> =
    protectedBlocks.asSequence()
        .flatMap { block -> facts.outgoing[block].orEmpty().asSequence() }
        .map { it.to }
        .filter { target -> target !in protectedBlocks && target !in handlerEntries }
        .toCollection(linkedSetOf())
