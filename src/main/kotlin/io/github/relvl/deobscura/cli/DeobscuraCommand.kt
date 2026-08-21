package io.github.relvl.deobscura.cli

import io.github.relvl.deobscura.analysis.AnalysisDiagnostics
import io.github.relvl.deobscura.analysis.FrameAnalyzer
import io.github.relvl.deobscura.analysis.MethodAnalyzer
import io.github.relvl.deobscura.cfg.ControlFlowDiagnostics
import io.github.relvl.deobscura.config.ConfigException
import io.github.relvl.deobscura.config.ConfigLoadResult
import io.github.relvl.deobscura.config.ConfigRepository
import io.github.relvl.deobscura.config.ConfigResolver
import io.github.relvl.deobscura.deobfuscation.Deobfuscator
import io.github.relvl.deobscura.diagnostics.ir.TechnicalIrService
import io.github.relvl.deobscura.jar.JarLoader
import io.github.relvl.deobscura.output.OutputDirectoryService
import io.github.relvl.deobscura.raw.ClassImporter
import io.github.relvl.deobscura.resolution.*
import io.github.relvl.deobscura.source.SourceOutputService
import io.github.relvl.deobscura.util.formatElapsedSeconds
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
        val startedAt = System.nanoTime()
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val absoluteConfigPath = resolveAgainst(workingDirectory, configPath)
        logger.info("Working directory: {}", workingDirectory)

        val result = try {
            when (val loaded = ConfigRepository().loadOrCreate(absoluteConfigPath)) {
                is ConfigLoadResult.Created -> configurationCreated(loaded)
                is ConfigLoadResult.Loaded -> run(workingDirectory, absoluteConfigPath, loaded)
            }
        } catch (exception: ConfigException) {
            logger.error(exception.message)
            EXIT_FAILURE
        } catch (exception: Exception) {
            logger.error("Failed to load project.", exception)
            EXIT_FAILURE
        }

        if (result == EXIT_SUCCESS) {
            logger.info("Deobscura completed successfully in {}.", formatElapsedSeconds(System.nanoTime() - startedAt))
        }
        return result
    }

    private fun configurationCreated(loaded: ConfigLoadResult.Created): Int {
        logger.error(
            "Configuration was not found. Created default configuration at '{}'. Edit it and run Deobscura again.",
            loaded.path,
        )
        return EXIT_CONFIGURATION_REQUIRED
    }

    private fun run(workingDirectory: Path, absoluteConfigPath: Path, loaded: ConfigLoadResult.Loaded): Int {
        if (rewriteConfig) {
            ConfigRepository().write(absoluteConfigPath, loaded.config)
            logger.info("Rewrote configuration: {}", absoluteConfigPath)
        }

        val resolution = ConfigResolver(workingDirectory).resolve(loaded.config)
        OutputDirectoryService.recreate(
            resolution.config.output,
            listOf(absoluteConfigPath, resolution.config.input) + resolution.config.classpath + resolution.config.runtime,
        )
        TechnicalIrService.configure(
            resolution.config.output.takeIf { resolution.config.technicalIr },
            resolution.config.input,
        )
        SourceOutputService.configure(resolution.config.output)

        try {
            logger.info("Input JAR: {}", resolution.config.input)
            logger.info("Java-like source output: {}", resolution.config.output)
            if (resolution.config.technicalIr) logger.info("Technical IR output: {}", resolution.config.output)
            resolution.warnings.forEach { logger.warn("{}{}", it, TechnicalIrService.rootHint()) }

            val jar = JarLoader().load(resolution.config)

            RuntimeClassSource(resolution.config.runtime).use { runtimeSource ->
                logger.info("Runtime: {} (Java {})", resolution.config.runtime, resolution.config.runtimeVersion)

                val classResolver = ClassResolver(jar, runtimeSource)
                val rawImport = ClassImporter().importInput(jar)
                val hierarchy = ClassHierarchy(classResolver)
                val methodOverrides = MethodOverrideAnalyzer(classResolver, hierarchy).analyze(rawImport)
                logger.info(
                    "Method hierarchy identified {} virtual family(ies), {} overriding method(s), and {} family(ies) pinned to external APIs.",
                    methodOverrides.stats.virtualFamilies,
                    methodOverrides.stats.overridingMethods,
                    methodOverrides.stats.externalApiFamilies,
                )
                val deobfuscation = Deobfuscator().analyze(rawImport, resolution.config.deobfuscation, methodOverrides)
                SourceOutputService.setDeobfuscation(deobfuscation)
                SourceOutputService.setMethodOverrides(methodOverrides)
                TechnicalIrService.setDeobfuscation(deobfuscation)
                if (resolution.config.deobfuscation) {
                    logger.info(
                        "Deobfuscation renamed {} package segment(s), {} field(s), and {} method(s).",
                        deobfuscation.stats.renamedPackageSegments,
                        deobfuscation.stats.renamedFields,
                        deobfuscation.stats.renamedMethods,
                    )
                } else {
                    logger.info("Deobfuscation disabled.")
                }

                val resolutionDiagnostics = ResolutionDiagnostics()
                val resolutionResult = resolutionDiagnostics.inspect(jar, classResolver)
                ControlFlowDiagnostics().inspect(rawImport)
                AnalysisDiagnostics(
                    methodAnalyzer = MethodAnalyzer(frameAnalyzer = FrameAnalyzer(hierarchy)),
                ).inspect(rawImport)
                TechnicalIrService.writeAll()
                SourceOutputService.writeAll()
                logger.info("Hierarchy analysis loaded {} class definition(s) lazily.", hierarchy.loadedClassCount)
                resolutionDiagnostics.logAnalysisImpact(classResolver, resolutionResult)
            }
            return EXIT_SUCCESS
        } finally {
            TechnicalIrService.reset()
            SourceOutputService.reset()
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
        val logger = LoggerFactory.getLogger(DeobscuraCommand::class.java)
    }
}
