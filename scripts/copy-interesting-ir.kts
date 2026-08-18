import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val interestingIr = listOf(
    "org/apache/commons/io/input/ReadAheadInputStream.ir",
    "org/fit/cssbox/layout/ContentImage.ir",
    "org/cyberneko/html/SecuritySupport.ir",

    "class/GXB3BZNuK.ir",
    "class/Lf7dbzysqZ.ir",
    "org/lwjgl/system/APIUtil.ir",

    "org/apache/http/impl/client/DecompressingHttpClient.ir",
    "org/apache/commons/io/input/Tailer.ir",

    "org/cyberneko/html/ObjectFactory.ir",
    "org/apache/http/conn/util/PublicSuffixMatcherLoader.ir",
    "org/apache/http/impl/execchain/ConnectionHolder.ir",

    "org/apache/http/impl/conn/ManagedClientConnectionImpl.ir",
    "org/apache/http/impl/conn/SingleClientConnManager.ir",

    "org/apache/commons/io/FileUtils.ir",

    "org/apache/xerces/impl/XMLDocumentScannerImpl\$DTDDispatcher.ir",
    "org/apache/xerces/parsers/SecureProcessingConfiguration.ir",
    "org/apache/xerces/xinclude/XIncludeHandler.ir",

    "org/apache/xml/serialize/DOMSerializerImpl.ir",

    "org/apache/html/dom/ObjectFactory.ir",
    "org/apache/xerces/dom/ObjectFactory.ir",
    "org/apache/xerces/impl/dv/ObjectFactory.ir",
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
val targetZip = projectRoot.resolve("workspace/demo-ir.zip")

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
