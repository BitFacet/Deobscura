import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val interestingFiles = listOf(
    "package_class/ZM4cSqt3bl.java",
    "package_class/ZM4cSqt3bl.ir",
    "package_class/GkJmVtjv0Y.java",
    "package_class/GkJmVtjv0Y.ir",
)
val zip = interestingFiles.size > 10

fun findProjectRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
        current = current.parent
    }
    error("Could not find project root from $start")
}

val projectRoot = findProjectRoot(Path.of(System.getProperty("user.dir")))
val irRoot = projectRoot.resolve("workspace/src")
val targetDir = projectRoot.resolve("workspace/demo")
val targetZip = projectRoot.resolve("workspace/demo.zip")

require(Files.isDirectory(irRoot)) { "directory does not exist: $irRoot" }
Files.createDirectories(targetDir)

interestingFiles.forEach { relativePath ->
    val source = irRoot.resolve(relativePath).normalize()
    require(source.startsWith(irRoot) && Files.isRegularFile(source)) {
        "file '$relativePath' was not found under $irRoot"
    }

    val target = targetDir.resolve(source.fileName.toString())
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    println("Copied $relativePath -> $target")
}

if (zip) {
    ZipOutputStream(Files.newOutputStream(targetZip)).use { zip ->
        Files.list(targetDir).use { files ->
            files
                .filter(Files::isRegularFile)
                .sorted()
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.fileName.toString()))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
        }
    }

    println("Created archive: $targetZip")
}
