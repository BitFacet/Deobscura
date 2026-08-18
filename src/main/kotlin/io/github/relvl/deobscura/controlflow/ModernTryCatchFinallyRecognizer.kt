package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph

/**
 * Reconstructs modern mixed-handler families where a typed catch scope and a catch-all finally
 * protect the same source try, with the finally handler repeated over the typed catch body.
 */
internal class ModernTryCatchFinallyRecognizer(
    private val typedCatchRecognizer: TypedCatchRecognizer,
) {
    fun recognize(
        graph: ControlFlowGraph,
        topology: ExceptionGroupTopology,
        header: BasicBlockId,
        exceptionTopology: ExceptionTopology,
        facts: ControlFlowFacts,
    ): ModernTryCatchFinallyRecognition? {
        val group = topology.group
        val catchAllHandlers = group.handlers.filter { it.catchType == null }
        val typedHandlers = group.handlers.filter { it.catchType != null }
        if (catchAllHandlers.size != 1 || typedHandlers.isEmpty()) return null

        val catchAll = catchAllHandlers.single()
        val handlerInstructionIndex = exceptionLabelPosition(exceptionTopology.labelPositions, catchAll.handler)
        val handlerEntry = facts.instructionToBlock.getOrNull(handlerInstructionIndex) ?: return null

        val peers = exceptionTopology.catchAllPeersByHandlerInstructionIndex[handlerInstructionIndex].orEmpty()
        if (peers.isEmpty() || peers.first() !== topology) return null

        // The physical table splits one source try/catch/finally around typed handlers. Before we
        // know the normal finally copies, use the peer union only as the boundary set for proving
        // the exceptional cleanup body.
        val familyProtectedBlocks = peers.flatMapTo(linkedSetOf()) { candidate ->
            extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
        }
        val finallyShape = ModernFinallyRecognizer.analyzeBranchingFinallyShape(
            graph = graph,
            handlerEntry = handlerEntry,
            protectedBlocks = familyProtectedBlocks,
            facts = facts,
        ) ?: return null
        val continuation = finallyShape.continuation ?: return null

        val finallyCopyBlocks = finallyShape.normalCopies.flatMapTo(linkedSetOf()) { it.blocks }
        val familyBuild = buildFinallyFamilyTopology(
            anchor = topology,
            catchAllHandlerInstructionIndex = handlerInstructionIndex,
            exceptionTopology = exceptionTopology,
            excludedBlocks = finallyShape.handlerBlocks + finallyCopyBlocks,
            continuation = continuation,
            facts = facts,
        )
        val family = familyBuild.topology ?: return null
        if (family.mixedGroups.isEmpty()) return null

        val mixedSignatures = family.mixedGroups
            .map { candidate -> exceptionHandlerSignature(candidate.group.handlers, exceptionTopology.labelPositions) }
            .distinct()
        if (mixedSignatures.size != 1) return null

        val sourceScope = family.typedTopology.scopes.singleOrNull() ?: return null
        val sourceTryBlocks = family.mixedGroups.flatMapTo(linkedSetOf()) { candidate ->
            extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - finallyShape.handlerBlocks
        val typedEntries = sourceScope.handlersByEntry.keys
        val typedScope = typedCatchRecognizer.analyze(
            scope = sourceScope.copy(
                header = header,
                protectedBlocks = sourceTryBlocks,
            ),
            facts = facts,
            rejectNormalBoundaryWithoutContinuation = false,
            allowLoopBackContinuation = false,
            collectCatch = { entry, _, scopeContinuation ->
                collectCatchBodyBeforeFinally(
                    typedCatchRecognizer = typedCatchRecognizer,
                    entry = entry,
                    protectedBlocks = sourceTryBlocks,
                    otherHandlerEntries = typedEntries + handlerEntry,
                    finallyCopyBlocks = finallyCopyBlocks,
                    continuation = scopeContinuation,
                    facts = facts,
                )?.let { blocks -> TypedCatchHandlerCollection(blocks, null) }
            },
            hasExternalCatchEntry = { entry, blocks, entries ->
                typedCatchRecognizer.hasExternalHandlerEntry(entry, blocks, entries, facts)
            },
            supportsCatchExit = { _, _ -> true },
            requiredContinuation = continuation,
        )
        val catches = typedScope.proof?.catches ?: return null

        val protectedRanges = family.protectedRanges
        if (protectedRanges.isEmpty()) return null
        return ModernTryCatchFinallyRecognition(
            region = StructuredRegion.TryCatchFinally(
                header = header,
                tryBlocks = sourceTryBlocks,
                catches = catches,
                handlerEntry = finallyShape.handlerEntry,
                handlerBlocks = finallyShape.handlerBlocks,
                finallyBodyInstructionRanges = finallyShape.bodyInstructionRanges,
                normalCopyInstructionIndices = finallyShape.normalCopies.flatMap { it.instructionRanges },
                normalCopyBlocks = finallyCopyBlocks,
                continuation = continuation,
                protectedStartInstructionIndex = protectedRanges.first().startInstructionIndex,
                protectedEndInstructionIndexExclusive = protectedRanges.last().endInstructionIndexExclusive,
                protectedRanges = protectedRanges,
            ),
            consumedGroupKeys = family.groups.mapTo(linkedSetOf()) { candidate ->
                ExceptionRegionKey(candidate.group.envelope.start, candidate.group.envelope.endExclusive)
            },
        )
    }
}

internal data class ModernTryCatchFinallyRecognition(
    val region: StructuredRegion.TryCatchFinally,
    val consumedGroupKeys: Set<ExceptionRegionKey>,
)
