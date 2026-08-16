package io.github.relvl.deobscura.cli

import io.github.relvl.deobscura.cfg.ControlFlowDiagnostics
import io.github.relvl.deobscura.config.ConfigException
import io.github.relvl.deobscura.config.ConfigLoadResult
import io.github.relvl.deobscura.config.ConfigRepository
import io.github.relvl.deobscura.config.ConfigResolver
import io.github.relvl.deobscura.jar.JarLoader
import io.github.relvl.deobscura.resolution.ClassResolver
import io.github.relvl.deobscura.resolution.ResolutionDiagnostics
import io.github.relvl.deobscura.resolution.ReferenceKind
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

                        diagnostics.unresolved.forEach { unresolved ->
                            logger.warn(
                                "Unresolved class '{}' [{}] referenced by {}.",
                                unresolved.internalName,
                                unresolved.kind,
                                formatReferrers(unresolved.referrers),
                            )
                        }

                        logger.info("Working directory: {}", workingDirectory)
                        logger.info("Input JAR: {}", resolution.config.input)
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

    private fun formatReferrers(referrers: List<String>): String {
        val shown = referrers.take(MAX_REFERRERS_IN_WARNING)
        return buildString {
            append(shown.joinToString())
            if (referrers.size > shown.size) {
                append(" (+")
                append(referrers.size - shown.size)
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
        const val MAX_REFERRERS_IN_WARNING = 5

        val logger = LoggerFactory.getLogger(DeobscuraCommand::class.java)
    }
}
