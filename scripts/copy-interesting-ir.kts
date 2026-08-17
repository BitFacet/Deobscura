import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val interestingIr = listOf(
    "class/HBBqdlMfAB.ir",
    "class/jSouKVH2LN.ir",
    "org/apache/xml/serialize/BaseMarkupSerializer.ir",
    "org/apache/xerces/impl/xpath/regex/RegexParser.ir",
    "com/google/gson/internal/bind/TypeAdapters$26.ir",
    "de/matthiasmann/twl/utils/PNGDecoder.ir",
    "com/google/gson/stream/JsonReader.ir",
    "com/jcraft/jorbis/Drft.ir"
)

fun findProjectRoot(start: Path): Path {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
        current = current.parent
    }
    error("Could not find project root from $start")
}

val projectRoot = findProjectRoot(Path.of(System.getProperty("user.dir")))
val irRoot = projectRoot.resolve("workspace/src/technical-ir")
val targetDir = projectRoot.resolve("workspace/demo-ir")

require(Files.isDirectory(irRoot)) { "Technical IR directory does not exist: $irRoot" }
Files.createDirectories(targetDir)

interestingIr.forEach { relativePath ->
    val source = irRoot.resolve(relativePath).normalize()
    require(source.startsWith(irRoot) && Files.isRegularFile(source)) {
        "IR file '$relativePath' was not found under $irRoot"
    }

    val target = targetDir.resolve(source.fileName.toString())
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    println("Copied $relativePath -> $target")
}
