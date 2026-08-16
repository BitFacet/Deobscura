package io.github.relvl.deobscura.cli

import io.github.relvl.deobscura.config.ConfigException
import io.github.relvl.deobscura.config.ConfigLoadResult
import io.github.relvl.deobscura.config.ConfigRepository
import io.github.relvl.deobscura.config.ConfigResolver
import io.github.relvl.deobscura.jar.JarLoader
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
                    logger.info("Resolved class set contains {} classes.", result.classes.size)
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
