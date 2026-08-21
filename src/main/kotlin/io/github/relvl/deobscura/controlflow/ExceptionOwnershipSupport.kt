package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId

/** Outcome of one fixed-point ownership expansion step. */
internal enum class ExceptionOwnershipExpansion {
    UNCHANGED, CHANGED, REJECTED,
}

/**
 * Closes an already-owned CFG region over exception constructs whose protected blocks are wholly
 * contained by that region. The containment/fixed-point mechanism is shared by ordinary catch
 * bodies and finally-copy ownership; callers provide the policy for collecting handlers.
 */
internal fun closeOverContainedExceptionRegions(
    owned: MutableSet<BasicBlockId>,
    groups: List<ExceptionGroupTopology>,
    excludeGroup: (ExceptionGroupTopology) -> Boolean,
    revisitContainedGroups: Boolean,
    handlerEntriesFor: (ExceptionGroupTopology) -> Set<BasicBlockId>,
    absorb: (ExceptionGroupTopology, Set<BasicBlockId>, Set<BasicBlockId>) -> ExceptionOwnershipExpansion,
): Boolean {
    val consumedGroups = mutableSetOf<ExceptionTableGroup>()
    var changed: Boolean
    do {
        changed = false
        val containedGroups = groups.filter { candidate ->
            !excludeGroup(candidate) && candidate.protectedBlocks.isNotEmpty() && candidate.protectedBlocks.all { it in owned }
        }
        val entriesByGroup = containedGroups.associateWith(handlerEntriesFor)
        val containedHandlerEntries = entriesByGroup.values.flatMapTo(linkedSetOf()) { it }

        for (candidate in containedGroups) {
            if (!revisitContainedGroups && candidate.group in consumedGroups) continue
            val entries = entriesByGroup.getValue(candidate)
            if (entries.isEmpty()) {
                if (!revisitContainedGroups) consumedGroups += candidate.group
                continue
            }
            if (!revisitContainedGroups) consumedGroups += candidate.group
            when (absorb(candidate, entries, containedHandlerEntries)) {
                ExceptionOwnershipExpansion.UNCHANGED -> Unit
                ExceptionOwnershipExpansion.CHANGED -> changed = true
                ExceptionOwnershipExpansion.REJECTED -> return false
            }
        }
    } while (changed)
    return true
}
