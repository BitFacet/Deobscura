package io.github.relvl.deobscura.config

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class ConfigResolver(
    private val workingDirectory: Path,
) {
    private val normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize()

    fun resolve(config: DeobscuraConfig): ConfigResolution {
        val warnings = mutableListOf<String>()

        val input = resolvePath(config.input)
        requireJar(input, "Input JAR")

        val runtime = config.runtime?.let(::resolvePath) ?: Path.of(System.getProperty("java.home"))
        if (!Files.isDirectory(runtime)) {
            throw ConfigException("Runtime directory does not exist: $runtime")
        }

        val classpath = config.classpath.flatMap { entry ->
            resolveClasspathEntry(entry, warnings)
        }

        val runtimeVersion = resolveRuntimeVersion(runtime, config.runtime != null, warnings)
        val output = resolvePath(config.output)
        validateOutputDoesNotContainInputs(output, input, classpath, runtime)

        return ConfigResolution(
            config = ResolvedConfig(
                input = input,
                classpath = classpath,
                runtime = runtime,
                runtimeVersion = runtimeVersion,
                output = output,
                deobfuscation = config.deobfuscation,
                technicalIr = config.technicalIr,
            ),
            warnings = warnings,
        )
    }

    private fun resolveClasspathEntry(entry: String, warnings: MutableList<String>): List<Path> {
        if (entry.isBlank()) {
            warnings += "Ignoring an empty classpath entry."
            return emptyList()
        }

        if (!containsGlob(entry)) {
            val path = resolvePath(entry)
            if (!Files.isRegularFile(path)) {
                warnings += "Classpath entry does not exist or is not a file: $path"
                return emptyList()
            }
            if (!path.isJar()) {
                warnings += "Classpath entry is not a JAR and will be ignored: $path"
                return emptyList()
            }
            return listOf(path)
        }

        val matches = expandGlob(entry)
            .filter { Files.isRegularFile(it) && it.isJar() }
            .sortedBy { it.toString() }

        if (matches.isEmpty()) {
            warnings += "Classpath glob did not match any JARs: $entry"
        }

        return matches
    }

    private fun expandGlob(pattern: String): List<Path> {
        val absolutePattern = Path.of(pattern.substringBeforeGlob()).isAbsolute
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val searchRoot = globSearchRoot(pattern)

        if (!Files.isDirectory(searchRoot)) {
            return emptyList()
        }

        return Files.walk(searchRoot).use { paths ->
            paths.filter { path ->
                val normalized = path.toAbsolutePath().normalize()
                val candidate = if (absolutePattern) {
                    normalized
                } else {
                    normalizedWorkingDirectory.relativize(normalized)
                }
                matcher.matches(candidate)
            }.toList()
        }
    }

    private fun globSearchRoot(pattern: String): Path {
        val prefix = pattern.substringBeforeGlob()
        val separatorIndex = maxOf(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'))
        val rootText = if (separatorIndex >= 0) prefix.substring(0, separatorIndex + 1) else ""
        return if (rootText.isEmpty()) normalizedWorkingDirectory else resolvePath(rootText)
    }

    private fun String.substringBeforeGlob(): String {
        val index = indexOfFirst { it in GLOB_META_CHARACTERS }
        return if (index < 0) this else substring(0, index)
    }


    private fun resolveRuntimeVersion(
        runtime: Path,
        explicitlyConfigured: Boolean,
        warnings: MutableList<String>,
    ): Runtime.Version {
        if (!explicitlyConfigured) {
            return Runtime.version()
        }

        val releaseFile = runtime.resolve("release")
        if (Files.isRegularFile(releaseFile)) {
            val javaVersion = Files.readAllLines(releaseFile)
                .firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")

            if (!javaVersion.isNullOrBlank()) {
                try {
                    return Runtime.Version.parse(javaVersion)
                } catch (_: IllegalArgumentException) {
                    warnings += "Cannot parse JAVA_VERSION '$javaVersion' from $releaseFile; using current JVM version ${Runtime.version()}."
                    return Runtime.version()
                }
            }
        }

        warnings += "Cannot determine Java version for runtime '$runtime'; using current JVM version ${Runtime.version()}."
        return Runtime.version()
    }

    private fun resolvePath(value: String): Path {
        val path = Path.of(value)
        return (if (path.isAbsolute) path else normalizedWorkingDirectory.resolve(path)).normalize()
    }

    private fun validateOutputDoesNotContainInputs(
        output: Path,
        input: Path,
        classpath: List<Path>,
        runtime: Path,
    ) {
        val normalizedOutput = output.toAbsolutePath().normalize()
        val protectedPaths = listOf(input) + classpath + runtime
        protectedPaths.forEach { protectedPath ->
            val normalized = protectedPath.toAbsolutePath().normalize()
            if (normalized.startsWith(normalizedOutput)) {
                throw ConfigException(
                    "Output '$normalizedOutput' would delete configured input data '$normalized' on startup.",
                )
            }
        }
    }

    private fun requireJar(path: Path, description: String) {
        if (!Files.isRegularFile(path)) {
            throw ConfigException("$description does not exist or is not a file: $path")
        }
        if (!path.isJar()) {
            throw ConfigException("$description must have a .jar extension: $path")
        }
    }

    private fun Path.isJar(): Boolean = extension.equals("jar", ignoreCase = true)

    private fun containsGlob(value: String): Boolean = value.any { it in GLOB_META_CHARACTERS }

    private companion object {
        val GLOB_META_CHARACTERS = setOf('*', '?', '[', '{')
    }
}
