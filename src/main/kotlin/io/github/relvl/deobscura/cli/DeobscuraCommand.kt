package io.github.relvl.deobscura.cli

import io.github.relvl.deobscura.analysis.AnalysisDiagnostics
import io.github.relvl.deobscura.analysis.FrameAnalyzer
import io.github.relvl.deobscura.analysis.MethodAnalyzer
import io.github.relvl.deobscura.cfg.ControlFlowDiagnostics
import io.github.relvl.deobscura.config.ConfigException
import io.github.relvl.deobscura.config.ConfigLoadResult
import io.github.relvl.deobscura.config.ConfigRepository
import io.github.relvl.deobscura.config.ConfigResolver
import io.github.relvl.deobscura.jar.JarLoader
import io.github.relvl.deobscura.raw.ClassImporter
import io.github.relvl.deobscura.resolution.ClassHierarchy
import io.github.relvl.deobscura.resolution.ClassResolver
import io.github.relvl.deobscura.resolution.ResolutionDiagnostics
import io.github.relvl.deobscura.resolution.RuntimeClassSource
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
                is ConfigLoadResult.Created -> configurationCreated(loaded)
                is ConfigLoadResult.Loaded -> run(workingDirectory, absoluteConfigPath, loaded)
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
        logger.info("Input JAR: {}", resolution.config.input)
        resolution.warnings.forEach { logger.warn(it) }

        val jar = JarLoader().load(resolution.config)

        RuntimeClassSource(resolution.config.runtime).use { runtimeSource ->
            logger.info("Runtime: {} (Java {})", resolution.config.runtime, resolution.config.runtimeVersion)

            val classResolver = ClassResolver(jar, runtimeSource)
            val resolutionDiagnostics = ResolutionDiagnostics()
            val resolutionResult = resolutionDiagnostics.inspect(jar, classResolver)
            val rawImport = ClassImporter().importInput(jar)

            ControlFlowDiagnostics().inspect(rawImport)
            val hierarchy = ClassHierarchy(classResolver)
            AnalysisDiagnostics(
                methodAnalyzer = MethodAnalyzer(frameAnalyzer = FrameAnalyzer(hierarchy)),
            ).inspect(rawImport)
            logger.info("Hierarchy analysis loaded {} class definition(s) lazily.", hierarchy.loadedClassCount)
            resolutionDiagnostics.logAnalysisImpact(classResolver, resolutionResult)
        }
        return EXIT_SUCCESS
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
