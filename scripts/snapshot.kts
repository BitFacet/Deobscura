#!/usr/bin/env kotlin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val includedPaths = listOf(
    "src",
    "gradle",
    "scripts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    ".gitignore",
)

val excludedDirectories = setOf(
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "workspace",
)

fun findProjectRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
        current = current.parent
    }
    error("Could not find project root from $start")
}

fun isExcluded(relative: Path): Boolean =
    relative.any { it.toString() in excludedDirectories }

val root = findProjectRoot(Path.of(System.getProperty("user.dir")))
val output = args.firstOrNull()?.let(Path::of) ?: Path.of("Deobscura-snapshot.zip")
val outputPath = (if (output.isAbsolute) output else root.resolve(output)).normalize()

Files.deleteIfExists(outputPath)
outputPath.parent?.let(Files::createDirectories)

ZipOutputStream(
    Files.newOutputStream(
        outputPath,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ),
).use { zip ->
    includedPaths.forEach { included ->
        val source = root.resolve(included)
        if (!Files.exists(source)) return@forEach

        val files = if (Files.isDirectory(source)) {
            Files.walk(source).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter { !isExcluded(it) }
                    .sorted(compareBy { it.toString().replace('\\', '/') })
                    .toList()
            }
        } else {
            listOf(root.relativize(source))
        }

        files.forEach { relative ->
            val entryName = relative.toString().replace('\\', '/')
            zip.putNextEntry(ZipEntry(entryName))
            Files.copy(root.resolve(relative), zip)
            zip.closeEntry()
        }
    }
}

println("Snapshot written to $outputPath")
