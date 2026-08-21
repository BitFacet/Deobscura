package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.controlflow.StructuredRegion

/** Builds an ordered source-composition view without re-proving any control-flow structure. */
class SourceStructureBuilder {
    fun build(
        graph: ControlFlowGraph,
        flow: SsaControlFlowGraph,
        structure: StructuredControlFlowAnalysis,
    ): SourceStructureAnalysis {
        val reachable = flow.reachableBlocks()
        val blockOrder = graph.blocks.associate { it.id to it.startInstructionIndex }
        val regions = structure.regions.filter { it.header in reachable }
        val diagnosticsByHeader = structure.unstructured.groupBy { it.header }
        val consumptions = linkedMapOf<Pair<BasicBlockId, SourceConsumptionReason>, SourceConsumption>()
        val emittedRegions = linkedSetOf<StructuredRegion>()

        val normalCopyOwners = finallyNormalCopyOwners(regions)
        val suppressedRegions = regions.filterTo(linkedSetOf()) { it.header in normalCopyOwners }

        fun recordConsumption(block: BasicBlockId, reason: SourceConsumptionReason, owner: BasicBlockId) {
            if (block !in reachable) return
            consumptions.putIfAbsent(block to reason, SourceConsumption(block, reason, owner))
        }

        fun sourceParts(region: StructuredRegion): List<PartSpec> = when (region) {
            is StructuredRegion.If -> buildList {
                if (region.thenBlocks.isNotEmpty()) add(PartSpec(SourceRegionPartKind.THEN, region.thenBlocks))
                if (region.elseBlocks.isNotEmpty()) add(PartSpec(SourceRegionPartKind.ELSE, region.elseBlocks))
            }

            is StructuredRegion.While -> listOf(PartSpec(SourceRegionPartKind.LOOP_BODY, region.bodyBlocks))
            is StructuredRegion.Switch -> region.cases.mapIndexed { index, case ->
                PartSpec(
                    kind = SourceRegionPartKind.SWITCH_CASE,
                    blocks = case.blocks,
                    ordinal = index,
                    label = buildList {
                        addAll(case.labels.map(Int::toString))
                        if (case.isDefault) add("default")
                    }.joinToString(","),
                )
            }

            is StructuredRegion.TryCatch -> buildList {
                add(PartSpec(SourceRegionPartKind.TRY_BODY, region.tryBlocks))
                region.catches.forEachIndexed { index, catch ->
                    add(PartSpec(SourceRegionPartKind.CATCH_BODY, catch.blocks, index, catch.catchTypes.joinToString("|")))
                }
            }

            is StructuredRegion.TryFinally -> buildList {
                add(PartSpec(SourceRegionPartKind.TRY_BODY, region.tryBlocks))
                val bodyBlocks = blocksOverlappingRanges(graph, reachable, region.finallyBodyInstructionRanges)
                add(PartSpec(SourceRegionPartKind.FINALLY_BODY, bodyBlocks, instructionRanges = region.finallyBodyInstructionRanges))
            }

            is StructuredRegion.TryCatchFinally -> buildList {
                add(PartSpec(SourceRegionPartKind.TRY_BODY, region.tryBlocks))
                region.catches.forEachIndexed { index, catch ->
                    add(PartSpec(SourceRegionPartKind.CATCH_BODY, catch.blocks, index, catch.catchTypes.joinToString("|")))
                }
                val bodyBlocks = blocksOverlappingRanges(graph, reachable, region.finallyBodyInstructionRanges)
                add(PartSpec(SourceRegionPartKind.FINALLY_BODY, bodyBlocks, instructionRanges = region.finallyBodyInstructionRanges))
            }

            is StructuredRegion.Synchronized -> listOf(
                PartSpec(SourceRegionPartKind.SYNCHRONIZED_BODY, region.bodyBlocks),
            )
        }

        fun directChildRegions(ownedBlocks: Set<BasicBlockId>, excluded: Set<StructuredRegion>): List<StructuredRegion> {
            val candidates = regions.filter { candidate ->
                candidate !in excluded && candidate.header in ownedBlocks && candidate !in suppressedRegions
            }
            return candidates.filter { child ->
                candidates.none { possibleParent ->
                    possibleParent !== child && isNestedInside(child, possibleParent, ::sourceParts)
                }
            }
        }

        lateinit var buildBlock: (Set<BasicBlockId>, Set<StructuredRegion>) -> SourceBlock
        buildBlock = { ownedBlocks, excluded ->
            val effectiveBlocks = ownedBlocks.filterTo(linkedSetOf()) { it in reachable }
            val directRegions = directChildRegions(effectiveBlocks, excluded)
            val regionsByHeader = directRegions.groupBy { it.header }
            val claimedByRegion = directRegions.flatMapTo(linkedSetOf()) { it.coveredBlocks }
            val nodes = mutableListOf<SourceNode>()

            effectiveBlocks.sortedBy { blockOrder[it] ?: Int.MAX_VALUE }.forEach { block ->
                val sameHeader = regionsByHeader[block].orEmpty()
                if (sameHeader.isNotEmpty()) {
                    val region = sameHeader.maxWithOrNull(
                        compareBy<StructuredRegion> { it.coveredBlocks.size }.thenBy { regionPriority(it) },
                    ) ?: return@forEach
                    if (!emittedRegions.add(region)) return@forEach

                    when (region) {
                        is StructuredRegion.TryFinally -> recordFinallyConsumptions(
                            graph, reachable, regions, region.header, region.handlerBlocks, region.finallyBodyInstructionRanges,
                            region.normalCopyBlocks, suppressedRegions, ::recordConsumption,
                        )

                        is StructuredRegion.TryCatchFinally -> recordFinallyConsumptions(
                            graph, reachable, regions, region.header, region.handlerBlocks, region.finallyBodyInstructionRanges,
                            region.normalCopyBlocks, suppressedRegions, ::recordConsumption,
                        )

                        is StructuredRegion.Synchronized -> region.handlerBlocks.forEach {
                            recordConsumption(it, SourceConsumptionReason.SYNCHRONIZED_MONITOR_SCAFFOLDING, region.header)
                        }

                        else -> Unit
                    }

                    val nextExcluded = excluded + region
                    val parts = sourceParts(region).map { spec ->
                        SourceRegionPart(
                            kind = spec.kind,
                            ordinal = spec.ordinal,
                            label = spec.label,
                            ownedBlocks = spec.blocks,
                            instructionRanges = spec.instructionRanges,
                            body = buildBlock(spec.blocks, nextExcluded),
                        )
                    }
                    nodes += SourceNode.Structured(
                        region = region,
                        parts = parts,
                        diagnostics = diagnosticsByHeader[region.header].orEmpty(),
                        provenance = SourceProvenance(region.coveredBlocks.intersect(reachable)),
                    )
                    return@forEach
                }

                if (block in claimedByRegion) return@forEach
                val diagnostics = diagnosticsByHeader[block].orEmpty()
                val provenance = SourceProvenance(setOf(block), blockInstructionRange(graph, block)?.let(::listOf).orEmpty())
                nodes += if (diagnostics.isEmpty()) {
                    SourceNode.BasicBlock(block, provenance)
                } else {
                    SourceNode.Unstructured(block, diagnostics, provenance)
                }
            }
            SourceBlock(effectiveBlocks, nodes)
        }

        suppressedRegions.forEach { suppressed ->
            val owner = requireNotNull(normalCopyOwners[suppressed.header])
            suppressed.coveredBlocks.forEach {
                recordConsumption(it, SourceConsumptionReason.FINALLY_NORMAL_COPY, owner)
            }
        }

        val projectedRoot = buildBlock(reachable, emptySet())
        val accountedBeforeFallback = linkedSetOf<BasicBlockId>().apply {
            collectAccountedBlocks(projectedRoot, this)
            addAll(consumptions.values.map { it.block })
        }
        val missing = reachable - accountedBeforeFallback
        val issues = if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(SourceProjectionIssue(SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK, missing))
        }
        val root = if (missing.isEmpty()) {
            projectedRoot
        } else {
            projectedRoot.copy(
                nodes = projectedRoot.nodes + missing.sortedBy { blockOrder[it] ?: Int.MAX_VALUE }.map { block ->
                    SourceNode.ProjectionFallback(
                        block = block,
                        reason = SourceProjectionIssueReason.UNACCOUNTED_REACHABLE_BLOCK,
                        provenance = SourceProvenance(
                            setOf(block),
                            blockInstructionRange(graph, block)?.let(::listOf).orEmpty(),
                        ),
                    )
                },
            )
        }
        val accounted = linkedSetOf<BasicBlockId>().apply {
            collectAccountedBlocks(root, this)
            addAll(consumptions.values.map { it.block })
        }
        check(accounted.containsAll(reachable)) {
            "Source projection fallback failed to retain all reachable blocks."
        }

        return SourceStructureAnalysis(root, accounted, consumptions.values.toList(), issues)
    }

    private fun isNestedInside(
        child: StructuredRegion,
        parent: StructuredRegion,
        sourceParts: (StructuredRegion) -> List<PartSpec>,
    ): Boolean {
        if (child.header == parent.header && child.coveredBlocks.size >= parent.coveredBlocks.size) return false
        return sourceParts(parent).any { part ->
            child.header in part.blocks && child.coveredBlocks.all { it in parent.coveredBlocks }
        }
    }

    private fun recordFinallyConsumptions(
        graph: ControlFlowGraph,
        reachable: Set<BasicBlockId>,
        regions: List<StructuredRegion>,
        owner: BasicBlockId,
        handlerBlocks: Set<BasicBlockId>,
        finallyBodyRanges: List<IntRange>,
        normalCopyBlocks: Set<BasicBlockId>,
        suppressedRegions: Set<StructuredRegion>,
        record: (BasicBlockId, SourceConsumptionReason, BasicBlockId) -> Unit,
    ) {
        normalCopyBlocks.forEach { record(it, SourceConsumptionReason.FINALLY_NORMAL_COPY, owner) }
        suppressedRegions.filter { it.header in normalCopyBlocks }.forEach { suppressed ->
            suppressed.coveredBlocks.forEach { record(it, SourceConsumptionReason.FINALLY_NORMAL_COPY, owner) }
        }

        val directSourceBlocks = blocksOverlappingRanges(graph, reachable, finallyBodyRanges)
        val nestedSourceBlocks =
            regions.asSequence().filter { it.header in directSourceBlocks && it.header != owner }.filter { it.coveredBlocks.all { block -> block in handlerBlocks } }.flatMap { it.coveredBlocks.asSequence() }.toSet()
        (handlerBlocks - directSourceBlocks - nestedSourceBlocks).forEach {
            record(it, SourceConsumptionReason.FINALLY_EXCEPTIONAL_SCAFFOLDING, owner)
        }
    }

    private fun finallyNormalCopyOwners(regions: List<StructuredRegion>): Map<BasicBlockId, BasicBlockId> = buildMap {
        regions.forEach { region ->
            val copies = when (region) {
                is StructuredRegion.TryFinally -> region.normalCopyBlocks
                is StructuredRegion.TryCatchFinally -> region.normalCopyBlocks
                else -> emptySet()
            }
            copies.forEach { putIfAbsent(it, region.header) }
        }
    }

    private fun regionPriority(region: StructuredRegion): Int = when (region) {
        is StructuredRegion.TryCatchFinally -> 7
        is StructuredRegion.TryFinally -> 6
        is StructuredRegion.TryCatch -> 5
        is StructuredRegion.Synchronized -> 4
        is StructuredRegion.While -> 3
        is StructuredRegion.Switch -> 2
        is StructuredRegion.If -> 1
    }

    private fun blocksOverlappingRanges(
        graph: ControlFlowGraph,
        reachable: Set<BasicBlockId>,
        ranges: List<IntRange>,
    ): Set<BasicBlockId> = graph.blocks.asSequence().filter { it.id in reachable }.filter { block -> ranges.any { range -> block.startInstructionIndex <= range.last && block.endInstructionIndexExclusive > range.first } }
        .mapTo(linkedSetOf()) { it.id }

    private fun blockInstructionRange(graph: ControlFlowGraph, block: BasicBlockId): IntRange? {
        val raw = graph.block(block)
        return if (raw.startInstructionIndex < raw.endInstructionIndexExclusive) {
            raw.startInstructionIndex..(raw.endInstructionIndexExclusive - 1)
        } else {
            null
        }
    }

    private fun collectAccountedBlocks(block: SourceBlock, output: MutableSet<BasicBlockId>) {
        block.nodes.forEach { node ->
            output += node.provenance.blocks
            if (node is SourceNode.Structured) node.parts.forEach { collectAccountedBlocks(it.body, output) }
        }
    }

    private data class PartSpec(
        val kind: SourceRegionPartKind,
        val blocks: Set<BasicBlockId>,
        val ordinal: Int = 0,
        val label: String? = null,
        val instructionRanges: List<IntRange> = emptyList(),
    )
}
