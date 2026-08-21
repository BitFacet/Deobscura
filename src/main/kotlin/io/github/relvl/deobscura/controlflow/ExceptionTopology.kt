package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.raw.RawExceptionHandler
import io.github.relvl.deobscura.raw.RawLabelId

/**
 * Physical exception-table topology derived before source-level exception recognition.
 *
 * This layer owns raw protected-range grouping, conservative coalescing of compiler-split ranges,
 * protected-block projection, and catch-all peer indexing. It deliberately does not decide whether
 * a group represents `catch`, `finally`, or `synchronized`; those are source-level proofs performed
 * by [StructuredExceptionRecognizer].
 */
internal data class ExceptionTopology(
    val labelPositions: Map<RawLabelId, Int>,
    val groups: List<ExceptionGroupTopology>,
    val catchAllPeersByHandlerInstructionIndex: Map<Int, List<ExceptionGroupTopology>>,
)

/**
 * Exception-table topology belonging to one proven finally handler. The duplicated cleanup
 * body itself is proved separately; this descriptor only groups the physical catch-all family,
 * typed source scopes living under it, and the outer protected ownership after compiler scaffolding
 * has been excluded.
 */
internal data class FinallyFamilyTopology(
    val groups: List<ExceptionGroupTopology>,
    val mixedGroups: List<ExceptionGroupTopology>,
    val typedTopology: TypedCatchScopeTopologyForest,
    val protectedBlocks: Set<BasicBlockId>,
    val protectedRanges: List<StructuredProtectedRange>,
)

internal enum class FinallyFamilyTopologyFailure {
    EMPTY_CATCH_ALL_FAMILY, NOT_FAMILY_ANCHOR,
}

internal data class FinallyFamilyTopologyBuild(
    val topology: FinallyFamilyTopology? = null,
    val failure: FinallyFamilyTopologyFailure? = null,
    val typedFailure: TypedCatchTopologyFailure? = null,
)

/**
 * Projects one catch-all peer family into source-oriented exception topology after a finally shape
 * has already identified its compiler-owned blocks. This function does not prove finally body
 * equivalence; it only combines exception-table relationships and typed-scope nesting.
 */
internal fun buildFinallyFamilyTopology(
    anchor: ExceptionGroupTopology,
    catchAllHandlerInstructionIndex: Int,
    exceptionTopology: ExceptionTopology,
    excludedBlocks: Set<BasicBlockId>,
    continuation: BasicBlockId?,
    facts: ControlFlowFacts,
): FinallyFamilyTopologyBuild {
    val groups = exceptionTopology.catchAllPeersByHandlerInstructionIndex[catchAllHandlerInstructionIndex].orEmpty()
    if (groups.isEmpty()) {
        return FinallyFamilyTopologyBuild(failure = FinallyFamilyTopologyFailure.EMPTY_CATCH_ALL_FAMILY)
    }
    if (groups.first() !== anchor) {
        return FinallyFamilyTopologyBuild(failure = FinallyFamilyTopologyFailure.NOT_FAMILY_ANCHOR)
    }

    val mixedGroups = groups.filter { candidate -> candidate.group.handlers.any { it.catchType != null } }
    val typedBuild = buildTypedCatchScopeTopologies(
        groups = mixedGroups,
        labelPositions = exceptionTopology.labelPositions,
        facts = facts,
        excludedBlocks = excludedBlocks,
    )
    if (typedBuild.failure != null) {
        return FinallyFamilyTopologyBuild(typedFailure = typedBuild.failure)
    }

    val protectedBlocks = groups.flatMapTo(linkedSetOf()) { candidate ->
        extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
    } - excludedBlocks - setOfNotNull(continuation)

    val protectedRanges = groups.flatMap { it.group.segments }.map { StructuredProtectedRange(it.range.start, it.range.endExclusive) }.distinct()
        .sortedWith(compareBy<StructuredProtectedRange> { it.startInstructionIndex }.thenBy { it.endInstructionIndexExclusive })

    return FinallyFamilyTopologyBuild(
        topology = FinallyFamilyTopology(
            groups = groups,
            mixedGroups = mixedGroups,
            typedTopology = requireNotNull(typedBuild.topology),
            protectedBlocks = protectedBlocks,
            protectedRanges = protectedRanges,
        ),
    )
}

/** Half-open instruction interval used while grouping raw exception-table entries. */
internal data class ProtectedRange(
    val start: Int, val endExclusive: Int
)

internal data class ExceptionTableSegment(
    val range: ProtectedRange,
    val handlers: List<RawExceptionHandler>,
)

/** Adjacent physical segments that share one handler signature and act as one protected group. */
internal data class ExceptionTableGroup(
    val segments: List<ExceptionTableSegment>,
    val envelope: ProtectedRange = ProtectedRange(segments.first().range.start, segments.last().range.endExclusive),
    val handlers: List<RawExceptionHandler> = segments.first().handlers
)

/** Physical group projected onto CFG ownership and concrete handler entries. */
internal data class ExceptionGroupTopology(
    val group: ExceptionTableGroup,
    val protectedBlocks: Set<BasicBlockId>,
    val handlerEntries: Set<BasicBlockId>,
)

internal data class ExceptionScopeNesting<T>(
    val parentByScope: Map<T, T>,
    val childrenByScope: Map<T, List<T>>,
    val crossingPairs: List<Pair<T, T>>,
) {
    val isLaminar: Boolean get() = crossingPairs.isEmpty()
}

/**
 * Builds containment relationships for a collection of exception scopes represented as CFG block
 * sets. Equal scopes remain peers; strict containment produces one direct parent, and partial
 * overlap is reported explicitly instead of being forced into a tree.
 */
internal fun <T> buildExceptionScopeNesting(scopes: List<T>, blocksOf: (T) -> Set<BasicBlockId>): ExceptionScopeNesting<T> {
    if (scopes.isEmpty()) {
        return ExceptionScopeNesting(emptyMap(), emptyMap(), emptyList())
    }

    val blocks = scopes.associateWith(blocksOf)
    val crossingPairs = mutableListOf<Pair<T, T>>()
    for (i in scopes.indices) {
        val leftScope = scopes[i]
        val left = blocks.getValue(leftScope)
        for (j in i + 1 until scopes.size) {
            val rightScope = scopes[j]
            val right = blocks.getValue(rightScope)
            if (left.isEmpty() || right.isEmpty()) continue
            val overlap = left.any { it in right }
            if (overlap && !left.containsAll(right) && !right.containsAll(left)) {
                crossingPairs += leftScope to rightScope
            }
        }
    }

    val parentByScope = linkedMapOf<T, T>()
    for (scope in scopes) {
        val scopeBlocks = blocks.getValue(scope)
        val parent = scopes.asSequence().filter { candidate -> candidate != scope }.filter { candidate ->
                val candidateBlocks = blocks.getValue(candidate)
                candidateBlocks.size > scopeBlocks.size && candidateBlocks.containsAll(scopeBlocks)
            }.minWithOrNull(compareBy<T> { candidate -> blocks.getValue(candidate).size }.thenBy { candidate -> scopes.indexOf(candidate) })
        if (parent != null) parentByScope[scope] = parent
    }

    val childrenByScope = scopes.associateWith { mutableListOf<T>() }.toMutableMap()
    for ((child, parent) in parentByScope) {
        childrenByScope.getValue(parent) += child
    }

    return ExceptionScopeNesting(
        parentByScope = parentByScope,
        childrenByScope = childrenByScope.mapValues { (_, children) -> children.toList() },
        crossingPairs = crossingPairs,
    )
}

/**
 * Source-level topology of one typed try/catch scope before CFG-body proof. The descriptor is
 * representation-neutral: ordinary exception-table groups and scopes nested under a legacy
 * finally family both project into the same shape before catch bodies are analyzed.
 */
internal data class TypedCatchScopeTopology(
    val header: BasicBlockId,
    val protectedBlocks: Set<BasicBlockId>,
    val handlersByEntry: Map<BasicBlockId, List<RawExceptionHandler>>,
    val protectedRanges: List<StructuredProtectedRange>,
)

internal enum class TypedCatchTopologyFailure {
    INVALID_HANDLER_ENTRY, EMPTY_PROTECTED_SCOPE, INVALID_PROTECTED_HEADER, CROSSING_SCOPES,
}

internal data class TypedCatchScopeTopologyForest(
    val scopes: List<TypedCatchScopeTopology>,
    val nesting: ExceptionScopeNesting<TypedCatchScopeTopology>,
) {
    val roots: List<TypedCatchScopeTopology>
        get() = scopes.filter { scope -> nesting.parentByScope[scope] == null }

    fun childrenOf(scope: TypedCatchScopeTopology): List<TypedCatchScopeTopology> = nesting.childrenByScope[scope].orEmpty()

    fun depthFirstScopes(): List<TypedCatchScopeTopology> = buildList {
        fun visit(scope: TypedCatchScopeTopology) {
            add(scope)
            childrenOf(scope).forEach(::visit)
        }
        roots.forEach(::visit)
    }
}

internal data class TypedCatchTopologyBuild(
    val topology: TypedCatchScopeTopologyForest? = null,
    val failure: TypedCatchTopologyFailure? = null,
)

private data class TypedCatchPhysicalScopeKey(
    val ranges: List<ProtectedRange>,
)

/**
 * Reconstructs source-level typed-catch scope topology from one or more physical exception-table
 * groups. Handler entries that occur under the same set of protected groups belong to one source
 * scope; distinct scopes may be disjoint or properly nested, but never cross.
 *
 * Callers may remove blocks already proven to be compiler scaffolding (for example duplicated
 * finally copies), but physical-range grouping itself is independent from finally recognition.
 */
internal fun buildTypedCatchScopeTopologies(
    groups: List<ExceptionGroupTopology>, labelPositions: Map<RawLabelId, Int>, facts: ControlFlowFacts, excludedBlocks: Set<BasicBlockId> = emptySet()
): TypedCatchTopologyBuild {
    if (groups.isEmpty()) return TypedCatchTopologyBuild(
        topology = TypedCatchScopeTopologyForest(
            scopes = emptyList(),
            nesting = ExceptionScopeNesting(emptyMap(), emptyMap(), emptyList()),
        ),
    )

    val groupsByEntry = linkedMapOf<BasicBlockId, MutableList<ExceptionGroupTopology>>()
    val handlersByEntry = linkedMapOf<BasicBlockId, MutableList<RawExceptionHandler>>()
    for (candidate in groups) {
        for (handler in candidate.group.handlers) {
            if (handler.catchType == null) continue
            val entry = facts.instructionToBlock.getOrNull(exceptionLabelPosition(labelPositions, handler.handler)) ?: return TypedCatchTopologyBuild(failure = TypedCatchTopologyFailure.INVALID_HANDLER_ENTRY)
            groupsByEntry.getOrPut(entry) { mutableListOf() } += candidate
            handlersByEntry.getOrPut(entry) { mutableListOf() } += handler
        }
    }
    if (groupsByEntry.isEmpty()) return TypedCatchTopologyBuild(
        topology = TypedCatchScopeTopologyForest(
            scopes = emptyList(),
            nesting = ExceptionScopeNesting(emptyMap(), emptyMap(), emptyList()),
        ),
    )

    val entriesByScope = groupsByEntry.entries.groupBy { (_, entryGroups) ->
        TypedCatchPhysicalScopeKey(
            entryGroups.map { candidate -> candidate.group.envelope }.distinct().sortedWith(compareBy<ProtectedRange> { it.start }.thenBy { it.endExclusive }),
        )
    }

    val scopes = mutableListOf<TypedCatchScopeTopology>()
    for ((_, entries) in entriesByScope.entries.sortedBy { it.key.ranges.first().start }) {
        val scopeGroups = entries.flatMap { it.value }.distinct().sortedBy { it.group.envelope.start }
        val protectedBlocks = scopeGroups.flatMapTo(linkedSetOf()) { candidate ->
            extendExceptionProtectedScopeWithTerminalTransfers(candidate.protectedBlocks, facts)
        } - excludedBlocks
        if (protectedBlocks.isEmpty()) {
            return TypedCatchTopologyBuild(failure = TypedCatchTopologyFailure.EMPTY_PROTECTED_SCOPE)
        }

        val start = scopeGroups.first().group.envelope.start
        val header = facts.instructionToBlock.getOrNull(start) ?: return TypedCatchTopologyBuild(failure = TypedCatchTopologyFailure.INVALID_PROTECTED_HEADER)
        if (header !in protectedBlocks) {
            return TypedCatchTopologyBuild(failure = TypedCatchTopologyFailure.INVALID_PROTECTED_HEADER)
        }
        val protectedRanges = scopeGroups.flatMap { it.group.segments }.map { StructuredProtectedRange(it.range.start, it.range.endExclusive) }.distinct()
            .sortedWith(compareBy<StructuredProtectedRange> { it.startInstructionIndex }.thenBy { it.endInstructionIndexExclusive })
        scopes += TypedCatchScopeTopology(
            header = header,
            protectedBlocks = protectedBlocks,
            handlersByEntry = entries.associate { (entry, _) -> entry to handlersByEntry.getValue(entry).distinct() },
            protectedRanges = protectedRanges,
        )
    }

    val nesting = buildExceptionScopeNesting(scopes) { scope -> scope.protectedBlocks }
    if (!nesting.isLaminar) {
        return TypedCatchTopologyBuild(failure = TypedCatchTopologyFailure.CROSSING_SCOPES)
    }

    return TypedCatchTopologyBuild(
        topology = TypedCatchScopeTopologyForest(scopes = scopes, nesting = nesting),
    )
}

/**
 * Includes source-terminal transfer blocks immediately following a physical protected range when
 * every normal predecessor of such a block is already owned by the protected scope.
 */
internal fun extendExceptionProtectedScopeWithTerminalTransfers(protectedBlocks: Set<BasicBlockId>, facts: ControlFlowFacts): Set<BasicBlockId> {
    val result = protectedBlocks.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        val candidates = result.asSequence().flatMap { block -> facts.outgoing[block].orEmpty().asSequence() }.map { it.to }.filter { it in facts.explicitTerminalBlocks && it !in result }.distinct().toList()
        for (candidate in candidates) {
            val incoming = facts.incoming[candidate].orEmpty()
            if (incoming.isNotEmpty() && incoming.all { it.from in result }) {
                result += candidate
                changed = true
            }
        }
    } while (changed)
    return result
}

internal data class ExceptionHandlerSignature(
    val handlerInstructionIndex: Int,
    val catchType: String?,
)

internal fun exceptionLabelPosition(positions: Map<RawLabelId, Int>, label: RawLabelId): Int = requireNotNull(positions[label]) { "Unknown exception-table label ${label.value}." }

internal fun exceptionHandlerSignature(handlers: List<RawExceptionHandler>, labelPositions: Map<RawLabelId, Int>): List<ExceptionHandlerSignature> =
    handlers.map { handler -> ExceptionHandlerSignature(exceptionLabelPosition(labelPositions, handler.handler), handler.catchType) }
        .sortedWith(compareBy<ExceptionHandlerSignature> { it.handlerInstructionIndex }.thenBy { it.catchType ?: "" })

/** Builds the physical exception topology once so source recognizers do not reinterpret raw tables. */
internal object ExceptionTopologyBuilder {
    /** Groups raw table ranges, projects them to blocks, and indexes shared catch-all handlers. */
    fun build(graph: ControlFlowGraph, facts: ControlFlowFacts): ExceptionTopology {
        val labelPositions = graph.code.labels.associate { it.id to it.instructionIndex }
        val segments = graph.code.exceptionHandlers.groupBy { handler ->
                ProtectedRange(
                    start = exceptionLabelPosition(labelPositions, handler.tryStart),
                    endExclusive = exceptionLabelPosition(labelPositions, handler.tryEnd),
                )
            }.entries.map { (range, handlers) -> ExceptionTableSegment(range, handlers) }.sortedWith(compareBy<ExceptionTableSegment> { it.range.start }.thenBy { it.range.endExclusive })
        val groups = coalesceSegments(segments, labelPositions, facts).map { group ->
            ExceptionGroupTopology(
                group = group,
                protectedBlocks = protectedBlocks(group.envelope, graph, facts),
                handlerEntries = group.handlers.mapNotNullTo(linkedSetOf()) { handler ->
                    facts.instructionToBlock.getOrNull(exceptionLabelPosition(labelPositions, handler.handler))
                },
            )
        }
        return ExceptionTopology(
            labelPositions = labelPositions,
            groups = groups,
            catchAllPeersByHandlerInstructionIndex = buildCatchAllPeerIndex(groups, labelPositions),
        )
    }

    private fun protectedBlocks(range: ProtectedRange, graph: ControlFlowGraph, facts: ControlFlowFacts): Set<BasicBlockId> =
        graph.blocks.asSequence().filter { block -> block.id in facts.blocks && block.startInstructionIndex < range.endExclusive && block.endInstructionIndexExclusive > range.start }.mapTo(linkedSetOf()) { it.id }

    private fun coalesceSegments(segments: List<ExceptionTableSegment>, labelPositions: Map<RawLabelId, Int>, facts: ControlFlowFacts): List<ExceptionTableGroup> {
        if (segments.isEmpty()) return emptyList()

        val result = mutableListOf<ExceptionTableGroup>()
        var current = ExceptionTableGroup(listOf(segments.first()))
        for (next in segments.drop(1)) {
            if (canCoalesce(current, next, labelPositions, facts)) {
                current = ExceptionTableGroup(current.segments + next)
            } else {
                result += current
                current = ExceptionTableGroup(listOf(next))
            }
        }
        result += current
        return result
    }

    private fun canCoalesce(current: ExceptionTableGroup, next: ExceptionTableSegment, labelPositions: Map<RawLabelId, Int>, facts: ControlFlowFacts): Boolean {
        if (exceptionHandlerSignature(current.handlers, labelPositions) != exceptionHandlerSignature(next.handlers, labelPositions)) {
            return false
        }
        val currentEnd = current.envelope.endExclusive
        if (next.range.start < currentEnd) return false
        if (next.range.start == currentEnd) return true

        val gapBlocks = (currentEnd until next.range.start).map { instructionIndex -> facts.instructionToBlock.getOrNull(instructionIndex) ?: return false }.toSet()
        return gapBlocks.isNotEmpty() && gapBlocks.all { it in facts.explicitTerminalBlocks }
    }

    private fun buildCatchAllPeerIndex(groups: List<ExceptionGroupTopology>, labelPositions: Map<RawLabelId, Int>): Map<Int, List<ExceptionGroupTopology>> {
        val peers = linkedMapOf<Int, MutableList<ExceptionGroupTopology>>()
        for (topology in groups) {
            topology.group.handlers.asSequence().filter { it.catchType == null }.map { exceptionLabelPosition(labelPositions, it.handler) }.distinct()
                .forEach { handlerInstructionIndex -> peers.getOrPut(handlerInstructionIndex) { mutableListOf() } += topology }
        }
        return peers.mapValues { (_, candidates) -> candidates.sortedBy { it.group.envelope.start } }
    }
}
