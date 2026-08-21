package io.github.relvl.deobscura.output

import java.nio.file.Files
import java.nio.file.Path

object OutputDirectoryService {
    fun recreate(
        output: Path,
        protectedPaths: Collection<Path> = emptyList(),
    ) {
        val normalizedOutput = output.toAbsolutePath().normalize()

        protectedPaths.forEach { protectedPath ->
            val normalizedProtected = protectedPath.toAbsolutePath().normalize()
            require(!normalizedProtected.startsWith(normalizedOutput)) {
                "Refusing to recreate output '$normalizedOutput' because it contains protected path '$normalizedProtected'."
            }
        }

        deleteRecursively(normalizedOutput)
        Files.createDirectories(normalizedOutput)
    }

    private fun deleteRecursively(path: Path) {
        if (Files.notExists(path)) return

        Files.walk(path).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}