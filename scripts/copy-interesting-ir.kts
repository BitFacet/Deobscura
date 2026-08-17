import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val interestingIr = listOf(
    "com/google/gson/stream/JsonReader.ir",
    "org/antlr/v4/runtime/tree/xpath/XPath.ir",
    "org/apache/xml/serialize/BaseMarkupSerializer.ir",
    "cz/vutbr/web/csskit/antlr4/CSSLexer.ir",
    "de/matthiasmann/twl/utils/PNGDecoder.ir",
    "org/apache/xerces/impl/XMLDocumentScannerImpl\$XMLDeclDispatcher.ir",
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
