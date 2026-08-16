package io.github.relvl.deobscura.cli

import io.github.relvl.deobscura.analysis.FrameDiagnostics
import io.github.relvl.deobscura.analysis.SsaDiagnostics
import io.github.relvl.deobscura.analysis.ValueFlowDiagnostics
import io.github.relvl.deobscura.cfg.ControlFlowDiagnostics
import io.github.relvl.deobscura.config.ConfigException
import io.github.relvl.deobscura.config.ConfigLoadResult
import io.github.relvl.deobscura.config.ConfigRepository
import io.github.relvl.deobscura.config.ConfigResolver
import io.github.relvl.deobscura.jar.JarLoader
import io.github.relvl.deobscura.normalize.LegacySubroutineDiagnostics
import io.github.relvl.deobscura.resolution.ClassResolver
import io.github.relvl.deobscura.resolution.ResolutionDiagnostics
import io.github.relvl.deobscura.resolution.ReferenceKind
import io.github.relvl.deobscura.resolution.ResolutionRequest
import io.github.relvl.deobscura.resolution.RuntimeClassSource
import io.github.relvl.deobscura.raw.ClassImporter
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "deobscura",
    description = ["Loads an obfuscated JAR and its configured classpath."],
    mixinStandardHelpOptions = true,
)
class DeobscuraCommand : Callable<Int> {
    @Option(
        names = ["-c", "--config"],
        description = ["Configuration file. Relative paths are resolved from the working directory."],
        defaultValue = DEFAULT_CONFIG,
    )
    lateinit var configPath: String

    @Option(
        names = ["--rewrite-config"],
        description = ["Rewrite the selected configuration as documented JSONC after loading it."],
    )
    var rewriteConfig: Boolean = false

    override fun call(): Int {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val absoluteConfigPath = resolveAgainst(workingDirectory, configPath)
        logger.info("Working directory: {}", workingDirectory)

        return try {
            when (val loaded = ConfigRepository().loadOrCreate(absoluteConfigPath)) {
                is ConfigLoadResult.Created -> {
                    logger.error(
                        "Configuration was not found. Created default configuration at '{}'. Edit it and run Deobscura again.",
                        loaded.path,
                    )
                    EXIT_CONFIGURATION_REQUIRED
                }

                is ConfigLoadResult.Loaded -> {
                    if (rewriteConfig) {
                        ConfigRepository().write(absoluteConfigPath, loaded.config)
                        logger.info("Rewrote configuration: {}", absoluteConfigPath)
                    }

                    val resolution = ConfigResolver(workingDirectory).resolve(loaded.config)
                    logger.info("Input JAR: {}", resolution.config.input)
                    resolution.warnings.forEach { logger.warn(it) }

                    val result = JarLoader().load(resolution.config)
                    result.warnings.forEach { logger.warn(it) }

                    RuntimeClassSource(resolution.config.runtime).use { runtimeSource ->
                        val classResolver = ClassResolver(result, runtimeSource)
                        val diagnostics = ResolutionDiagnostics().inspect(result, classResolver)
                        diagnostics.warnings.forEach { logger.warn(it) }

                        val rawImport = ClassImporter().importInput(result)
                        rawImport.warnings.forEach { logger.warn(it) }
                        if (rawImport.unknownInstructionCount > 0) {
                            logger.warn(
                                "Raw importer encountered {} instruction(s) with an unknown representation.",
                                rawImport.unknownInstructionCount,
                            )
                        }
                        val controlFlow = ControlFlowDiagnostics().inspect(rawImport)
                        controlFlow.warnings.forEach { logger.warn(it) }
                        val legacySubroutines = LegacySubroutineDiagnostics().inspect(rawImport)
                        legacySubroutines.warnings.forEach { logger.warn(it) }
                        val frameAnalysis = FrameDiagnostics().inspect(rawImport)
                        frameAnalysis.warnings.forEach { logger.warn(it) }
                        val valueFlow = ValueFlowDiagnostics().inspect(rawImport)
                        valueFlow.warnings.forEach { logger.warn(it) }
                        val ssa = SsaDiagnostics().inspect(rawImport)
                        ssa.warnings.forEach { logger.warn(it) }

                        logger.info("Runtime: {} (Java {})", resolution.config.runtime, resolution.config.runtimeVersion)
                        logger.info("Loaded {} classes from input JAR.", result.inputClassCount)
                        logger.info(
                            "Loaded {} classes from {} classpath JAR(s).",
                            result.classpathClassCount,
                            resolution.config.classpath.size,
                        )
                        logger.info(
                            "Classpath contributed {} classes to the resolved set; {} classpath classes were shadowed by the input JAR.",
                            result.classpathOnlyClassCount,
                            result.shadowedClasspathClassCount,
                        )
                        logger.info("Application class set contains {} classes.", result.classes.size)
                        logger.info(
                            "Resolved {} referenced classes from the runtime; {} class(es) remain unresolved.",
                            classResolver.resolvedRuntimeClassCount,
                            diagnostics.unresolved.size,
                        )
                        if (diagnostics.unresolved.isNotEmpty()) {
                            logger.info(
                                "Unresolved classes by reference kind: {} structural, {} signature, {} constant-pool.",
                                diagnostics.count(ReferenceKind.STRUCTURAL),
                                diagnostics.count(ReferenceKind.SIGNATURE),
                                diagnostics.count(ReferenceKind.CONSTANT_POOL),
                            )
                        }
                        logger.info(
                            "Imported {} input classes into raw model: {} fields, {} methods ({} with code), {} instructions.",
                            rawImport.classes.size,
                            rawImport.fieldCount,
                            rawImport.methodCount,
                            rawImport.methodsWithCode,
                            rawImport.instructionCount,
                        )
                        logger.info(
                            "Raw import completed with {} parse failure(s) and {} unknown instruction(s).",
                            rawImport.parseFailureCount,
                            rawImport.unknownInstructionCount,
                        )
                        logger.info(
                            "Built CFG for {} method(s): {} basic blocks, {} edges ({} exception), {} unreachable blocks.",
                            controlFlow.methodCount,
                            controlFlow.blockCount,
                            controlFlow.edgeCount,
                            controlFlow.exceptionEdgeCount,
                            controlFlow.unreachableBlockCount,
                        )
                        logger.info("CFG construction completed with {} failure(s).", controlFlow.failureCount)
                        if (controlFlow.unreachableBlocks.isNotEmpty()) {
                            val shown = controlFlow.unreachableBlocks.take(MAX_UNREACHABLE_BLOCK_DETAILS)
                            shown.forEach { block ->
                                logger.debug(
                                    "Unreachable block: {} block={}, instructions={}..{}, lines={}, incoming={}, opcodes={}",
                                    block.methodName,
                                    block.blockId,
                                    block.startInstructionIndex,
                                    block.endInstructionIndexExclusive - 1,
                                    block.sourceLines?.let { if (it.first == it.last) it.first.toString() else "${it.first}..${it.last}" } ?: "?",
                                    if (block.incomingEdges.isEmpty()) {
                                        "none"
                                    } else {
                                        block.incomingEdges.joinToString { "${it.fromBlockId}:${it.kind}" }
                                    },
                                    formatOpcodes(block.opcodes),
                                )
                            }
                            if (controlFlow.unreachableBlocks.size > shown.size) {
                                logger.debug(
                                    "Unreachable block details truncated: {} more block(s) not shown.",
                                    controlFlow.unreachableBlocks.size - shown.size,
                                )
                            }
                        }
                        logger.info(
                            "Normalized legacy JSR/RET in {} method(s): {} JSR call site(s), {} cloned basic block(s), {} normalized instruction(s).",
                            legacySubroutines.methodCount,
                            legacySubroutines.jsrCallSiteCount,
                            legacySubroutines.clonedBlockCount,
                            legacySubroutines.normalizedInstructionCount,
                        )
                        logger.info(
                            "Legacy JSR/RET normalization completed with {} failure(s).",
                            legacySubroutines.failureCount,
                        )
                        logger.info(
                            "Analyzed JVM frames for {} method(s): {} frame merge(s), {} value merge(s).",
                            frameAnalysis.methodCount,
                            frameAnalysis.frameMergeCount,
                            frameAnalysis.valueMergeCount,
                        )
                        logger.info(
                            "Frame analysis completed with {} failure(s): {} stack/local inconsistency(s), {} unsupported instruction case(s).",
                            frameAnalysis.failureCount,
                            frameAnalysis.stackInconsistencyCount,
                            frameAnalysis.unsupportedInstructionCount,
                        )

                        logger.info(
                            "Built explicit value flow for {} method(s): {} values, {} operation(s), {} merge value(s), {} stack instruction(s) eliminated.",
                            valueFlow.methodCount,
                            valueFlow.valueCount,
                            valueFlow.operationCount,
                            valueFlow.mergeValueCount,
                            valueFlow.eliminatedStackInstructionCount,
                        )
                        if (valueFlow.unanalyzedBlockCount > 0) {
                            logger.debug(
                                "Value flow excluded {} unreachable basic block(s).",
                                valueFlow.unanalyzedBlockCount,
                            )
                        }
                        logger.info(
                            "Value-flow analysis completed with {} failure(s): {} inconsistent state(s), {} unsupported instruction case(s).",
                            valueFlow.failureCount,
                            valueFlow.inconsistencyCount,
                            valueFlow.unsupportedInstructionCount,
                        )

                        logger.info(
                            "Built SSA for {} method(s): {} values, {} operation(s), {} phi node(s) ({} local, {} stack), {} def-use edge(s), {} local load/store instruction(s) eliminated.",
                            ssa.methodCount,
                            ssa.valueCount,
                            ssa.operationCount,
                            ssa.phiCount,
                            ssa.localPhiCount,
                            ssa.stackPhiCount,
                            ssa.useEdgeCount,
                            ssa.eliminatedLocalInstructionCount,
                        )
                        logger.info(
                            "SSA phi placement: {} block(s), {} trivial phi node(s), {} phi node(s) in {} block(s) with at most one CFG predecessor ({} with no predecessor).",
                            ssa.phiBlockCount,
                            ssa.trivialPhiCount,
                            ssa.singlePredecessorPhiCount,
                            ssa.singlePredecessorPhiBlockCount,
                            ssa.zeroPredecessorPhiCount,
                        )
                        logger.info(
                            "SSA single-predecessor phi classification: {} phi node(s) in {} exception-related block(s), {} phi node(s) in {} non-exception block(s).",
                            ssa.singlePredecessorExceptionPhiCount,
                            ssa.singlePredecessorExceptionPhiBlockCount,
                            ssa.singlePredecessorNonExceptionPhiCount,
                            ssa.singlePredecessorNonExceptionPhiBlockCount,
                        )
                        ssa.nonExceptionSinglePredecessorPhiDetails.forEach { detail ->
                            logger.debug("SSA non-exception single-predecessor phi: {}", detail)
                        }
                        logger.info("SSA phi density: maximum {} phi node(s) in one basic block.", ssa.maxPhiNodesPerBlock)
                        logger.info(
                            "SSA optimization reached fixed point for {} method(s): maximum {} iteration(s), {} method(s) required multiple iterations.",
                            ssa.methodCount,
                            ssa.maxOptimizationIterationCount,
                            ssa.multiIterationMethodCount,
                        )
                        logger.info(
                            "SSA optimization resolved {} conditional branch(es) and {} switch(es): {} CFG edge(s) eliminated, {} additional block(s) became unreachable.",
                            ssa.resolvedConstantBranchCount,
                            ssa.resolvedConstantSwitchCount,
                            ssa.eliminatedConstantEdgeCount,
                            ssa.constantNewlyUnreachableBlockCount,
                        )
                        logger.info(
                            "SSA optimization removed {} operation(s), {} value(s), {} phi node(s), and {} phi input(s); propagated {} value alias(es).",
                            ssa.prunedOperationCount,
                            ssa.prunedValueCount,
                            ssa.prunedPhiNodeCount,
                            ssa.prunedPhiInputCount,
                            ssa.propagatedAliasCount,
                        )
                        logger.info(
                            "SSA optimization finished with {} constant value(s): {} literal(s), {} operation(s) folded, {} phi result(s) resolved, {} newly exposed after pruning.",
                            ssa.constantValueCount,
                            ssa.literalConstantCount,
                            ssa.foldedConstantOperationCount,
                            ssa.constantPhiCount,
                            ssa.newlyExposedConstantCount,
                        )
                        if (
                            ssa.retainedExceptionalProvenanceOperationCount > 0 ||
                            ssa.retainedExceptionalProvenancePhiCount > 0 ||
                            ssa.conservativelyRetainedPhiCount > 0
                        ) {
                            logger.debug(
                                "SSA CFG pruning retained {} unreachable operation(s), {} unreachable phi node(s), and left {} exception-related phi node(s) conservative.",
                                ssa.retainedExceptionalProvenanceOperationCount,
                                ssa.retainedExceptionalProvenancePhiCount,
                                ssa.conservativelyRetainedPhiCount,
                            )
                        }
                        logger.info(
                            "SSA analysis completed with {} failure(s): {} inconsistent state(s).",
                            ssa.failureCount,
                            ssa.inconsistencyCount,
                        )

                        val unresolvedAnalysisUses = classResolver.unresolvedAnalysisUses
                        unresolvedAnalysisUses.forEach { use ->
                            val discovered = diagnostics.unresolved.firstOrNull { it.internalName == use.internalName }
                            logger.warn(
                                "Unresolved class '{}'{} affected analysis [{}]: {}.",
                                use.internalName,
                                discovered?.let { " [${it.kind}]" } ?: "",
                                use.strongestImpact,
                                formatAnalysisRequests(use.requests),
                            )
                        }
                        val affectedNames = unresolvedAnalysisUses.asSequence().map { it.internalName }.toSet()
                        val unaffectedCount = diagnostics.unresolved.count { it.internalName !in affectedNames }
                        logger.info(
                            "Unresolved class impact: {} referenced, {} affected performed analyses, {} did not affect performed analyses.",
                            diagnostics.unresolved.size,
                            unresolvedAnalysisUses.size,
                            unaffectedCount,
                        )
                    }
                    EXIT_SUCCESS
                }
            }
        } catch (exception: ConfigException) {
            logger.error(exception.message)
            EXIT_FAILURE
        } catch (exception: Exception) {
            logger.error("Failed to load project: {}", exception.message)
            logger.debug("Project loading failure", exception)
            EXIT_FAILURE
        }
    }

    private fun formatOpcodes(opcodes: List<String>): String {
        val shown = opcodes.take(MAX_OPCODES_IN_UNREACHABLE_BLOCK)
        return buildString {
            append(shown.joinToString(" "))
            if (opcodes.size > shown.size) {
                append(" ... (+")
                append(opcodes.size - shown.size)
                append(" more)")
            }
        }
    }

    private fun formatAnalysisRequests(
        requests: List<ResolutionRequest>,
    ): String {
        val distinctRequests = requests.distinct()
        val shown = distinctRequests.take(MAX_ANALYSIS_REQUESTS_IN_WARNING)
        return buildString {
            append(shown.joinToString { "${it.purpose} for ${it.consumer}" })
            if (distinctRequests.size > shown.size) {
                append(" (+")
                append(distinctRequests.size - shown.size)
                append(" more)")
            }
        }
    }

    private fun resolveAgainst(base: Path, value: String): Path {
        val path = Path.of(value)
        return (if (path.isAbsolute) path else base.resolve(path)).normalize()
    }

    private companion object {
        const val DEFAULT_CONFIG = "default.jsonc"
        const val EXIT_SUCCESS = 0
        const val EXIT_FAILURE = 1
        const val EXIT_CONFIGURATION_REQUIRED = 2
        const val MAX_ANALYSIS_REQUESTS_IN_WARNING = 5
        const val MAX_UNREACHABLE_BLOCK_DETAILS = 32
        const val MAX_OPCODES_IN_UNREACHABLE_BLOCK = 16

        val logger = LoggerFactory.getLogger(DeobscuraCommand::class.java)
    }
}
