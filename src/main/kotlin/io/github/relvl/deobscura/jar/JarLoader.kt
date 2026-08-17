package io.github.relvl.deobscura.jar

import io.github.relvl.deobscura.config.ResolvedConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.jar.JarFile

class JarLoader(
    private val logger: Logger = LoggerFactory.getLogger(JarLoader::class.java),
) {
    fun load(config: ResolvedConfig): JarLoadResult {
        val classes = linkedMapOf<String, LoadedClass>()
        val warnings = mutableListOf<String>()

        val inputClasses = try {
            loadJar(JarSource(config.input, JarRole.INPUT), config.runtimeVersion)
        } catch (exception: Exception) {
            throw JarLoadException("Failed to load input JAR '${config.input}': ${exception.message}", exception)
        }

        inputClasses.forEach { loadedClass ->
            classes[loadedClass.internalName] = loadedClass
        }
        val inputClassNames = inputClasses.mapTo(hashSetOf()) { it.internalName }

        var classpathClassCount = 0
        var shadowedClasspathClassCount = 0

        for (classpathJar in config.classpath) {
            val source = JarSource(classpathJar, JarRole.CLASSPATH)
            val loaded = try {
                loadJar(source, config.runtimeVersion)
            } catch (exception: Exception) {
                warnings += "Failed to load classpath JAR '${source.path}': ${exception.message}. Continuing without it."
                continue
            }

            classpathClassCount += loaded.size
            var shadowedByInput = 0

            for (loadedClass in loaded) {
                if (loadedClass.internalName in inputClassNames) {
                    shadowedByInput++
                    continue
                }

                val previous = classes.put(loadedClass.internalName, loadedClass)
                if (previous != null) {
                    warnings += buildString {
                        append("Duplicate classpath class '")
                        append(loadedClass.internalName)
                        append("': ")
                        append(loadedClass.origin.jar)
                        append(" overrides ")
                        append(previous.origin.jar)
                        append('.')
                    }
                }
            }

            if (shadowedByInput > 0) {
                shadowedClasspathClassCount += shadowedByInput
                warnings += "Classpath JAR '${source.path}' contains $shadowedByInput class(es) already present in the input JAR; input versions will be used."
            }
        }

        val classpathOnlyClassCount = classes.values.count { it.origin.role == JarRole.CLASSPATH }

        return JarLoadResult(
            classes = classes,
            inputClassCount = inputClasses.size,
            classpathClassCount = classpathClassCount,
            classpathOnlyClassCount = classpathOnlyClassCount,
            shadowedClasspathClassCount = shadowedClasspathClassCount,
            warnings = warnings,
        ).also { result -> logLoadResult(result, config.classpath.size, config.technicalIr) }
    }

    private fun logLoadResult(result: JarLoadResult, classpathJarCount: Int, technicalIr: Path?) {
        val ir = technicalIr?.let { " Technical IR root: $it." } ?: ""
        result.warnings.forEach { logger.warn("{}{}", it, ir) }
        logger.info("Loaded {} classes from input JAR.", result.inputClassCount)
        logger.info(
            "Loaded {} classes from {} classpath JAR(s).",
            result.classpathClassCount,
            classpathJarCount,
        )
        logger.info(
            "Classpath contributed {} classes to the resolved set; {} classpath classes were shadowed by the input JAR.",
            result.classpathOnlyClassCount,
            result.shadowedClasspathClassCount,
        )
        logger.info("Application class set contains {} classes.", result.classes.size)
    }

    private fun loadJar(source: JarSource, runtimeVersion: Runtime.Version): List<LoadedClass> {
        return JarFile(source.path.toFile(), false, JarFile.OPEN_READ, runtimeVersion).use { jar ->
            val multiRelease = jar.isMultiRelease
            jar.versionedStream().use { entries ->
                entries
                    .filter { entry ->
                        !entry.isDirectory &&
                                entry.name.endsWith(CLASS_SUFFIX) &&
                                (multiRelease || !entry.name.startsWith(MULTI_RELEASE_PREFIX))
                    }
                    .map { entry ->
                        LoadedClass(
                            internalName = entry.name.removeSuffix(CLASS_SUFFIX),
                            bytes = jar.getInputStream(entry).use { it.readAllBytes() },
                            origin = ClassOrigin(
                                jar = source.path,
                                entry = entry.realName,
                                role = source.role,
                            ),
                        )
                    }
                    .toList()
            }
        }
    }

    private data class JarSource(
        val path: Path,
        val role: JarRole,
    )

    private companion object {
        const val CLASS_SUFFIX = ".class"
        const val MULTI_RELEASE_PREFIX = "META-INF/versions/"
    }
}

enum class JarRole {
    INPUT,
    CLASSPATH,
}

data class ClassOrigin(
    val jar: Path,
    val entry: String,
    val role: JarRole,
)

data class LoadedClass(
    val internalName: String,
    val bytes: ByteArray,
    val origin: ClassOrigin,
)

data class JarLoadResult(
    val classes: Map<String, LoadedClass>,
    val inputClassCount: Int,
    val classpathClassCount: Int,
    val classpathOnlyClassCount: Int,
    val shadowedClasspathClassCount: Int,
    val warnings: List<String>,
)

class JarLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
