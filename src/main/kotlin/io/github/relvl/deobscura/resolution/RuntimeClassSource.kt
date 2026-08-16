package io.github.relvl.deobscura.resolution

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class RuntimeClassSource(
    private val runtimeHome: Path,
) : AutoCloseable {
    private val normalizedRuntimeHome = runtimeHome.toAbsolutePath().normalize()
    private val currentRuntimeHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
    private val ownsFileSystem: Boolean
    private val fileSystem: FileSystem
    private val modulesRoot: Path
    private val packagesRoot: Path
    private val cache = mutableMapOf<String, RuntimeClass?>()

    init {
        val jrtUri = URI.create("jrt:/")
        if (normalizedRuntimeHome == currentRuntimeHome) {
            fileSystem = FileSystems.getFileSystem(jrtUri)
            ownsFileSystem = false
        } else {
            fileSystem = FileSystems.newFileSystem(
                jrtUri,
                mapOf("java.home" to normalizedRuntimeHome.toString()),
            )
            ownsFileSystem = true
        }

        modulesRoot = fileSystem.getPath("/modules")
        packagesRoot = fileSystem.getPath("/packages")
    }

    fun findClass(internalName: String): RuntimeClass? {
        if (cache.containsKey(internalName)) {
            return cache[internalName]
        }

        val resolved = findClassUncached(internalName)
        cache[internalName] = resolved
        return resolved
    }

    private fun findClassUncached(internalName: String): RuntimeClass? {
        val relativeClassPath = "$internalName.class"
        val packageName = internalName.substringBeforeLast('/', missingDelimiterValue = "")
            .replace('/', '.')

        if (packageName.isNotEmpty()) {
            val packageDirectory = packagesRoot.resolve(packageName)
            if (Files.isDirectory(packageDirectory)) {
                Files.list(packageDirectory).use { modules ->
                    val iterator = modules.iterator()
                    while (iterator.hasNext()) {
                        val modulePath = iterator.next()
                        val moduleName = modulePath.fileName.toString()
                        val classPath = modulesRoot.resolve(moduleName).resolve(relativeClassPath)
                        if (Files.isRegularFile(classPath)) {
                            return RuntimeClass(
                                internalName = internalName,
                                bytes = Files.readAllBytes(classPath),
                                module = moduleName,
                            )
                        }
                    }
                }
            }
            return null
        }

        // The Java runtime normally has no classes in the unnamed package, but keep this correct
        // for unusual runtime images without building a complete class index up front.
        Files.list(modulesRoot).use { modules ->
            val iterator = modules.iterator()
            while (iterator.hasNext()) {
                val modulePath = iterator.next()
                val classPath = modulePath.resolve(relativeClassPath)
                if (Files.isRegularFile(classPath)) {
                    return RuntimeClass(
                        internalName = internalName,
                        bytes = Files.readAllBytes(classPath),
                        module = modulePath.fileName.toString(),
                    )
                }
            }
        }
        return null
    }

    override fun close() {
        if (ownsFileSystem) {
            fileSystem.close()
        }
    }
}

data class RuntimeClass(
    val internalName: String,
    val bytes: ByteArray,
    val module: String,
)
