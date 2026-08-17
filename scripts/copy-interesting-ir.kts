import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val interestingIr = listOf(
    // Existing control-flow / switch goldens.
    "class/GXB3BZNuK.ir",
    "class/mHp80DMOlW.ir",
    "class/WfJU7pYOaC.ir",
    "org/cyberneko/html/HTMLScanner\$SpecialScanner.ir",
    "org/apache/commons/io/filefilter/MagicNumberFileFilter.ir",

    // finally goldens.
    "org/apache/commons/io/file/PathUtils.ir",
    "org/apache/commons/io/input/TeeInputStream.ir",
    "org/apache/commons/io/input/TeeReader.ir",
    "org/apache/commons/io/FileUtils.ir",

    // synchronized goldens and variants.
    "com/wurmonline/shared/util/SynchedHashMap.ir",
    "com/wurmonline/shared/util/SynchedLinkedHashMap.ir",
    "it/unimi/dsi/fastutil/doubles/DoubleCollections\$SynchronizedCollection.ir",
    "org/apache/xerces/parsers/CachingParserPool\$SynchronizedGrammarPool.ir",
    "org/apache/commons/io/FileCleaningTracker.ir",
    "org/apache/commons/io/output/ByteArrayOutputStream.ir",
    "org/apache/commons/io/output/LockableFileWriter.ir",

    // Explicit locks / complex cleanup.
    "org/apache/commons/io/input/ReadAheadInputStream.ir",
    "org/apache/commons/io/input/Tailer.ir",
    "org/apache/xerces/jaxp/validation/SoftReferenceGrammarPool.ir",

    // Serialization / state restoration cleanup.
    "org/apache/xerces/dom/CoreDocumentImpl.ir",
    "org/apache/xerces/dom/NamedNodeMapImpl.ir",
    "org/apache/xerces/dom/DOMNormalizer.ir",

    // Parser lifecycle cleanup families.
    "org/apache/xerces/impl/xs/opti/SchemaParsingConfig.ir",
    "org/apache/xerces/parsers/DTDConfiguration.ir",
    "org/apache/xerces/parsers/NonValidatingConfiguration.ir",
    "org/apache/xerces/impl/dtd/XMLDTDLoader.ir",
    "org/apache/xerces/impl/XMLDocumentScannerImpl\$ContentDispatcher.ir",
    "org/apache/xerces/impl/XMLDocumentScannerImpl\$DTDDispatcher.ir",

    // Validation lifecycle / nested cleanup.
    "org/apache/xerces/jaxp/validation/DOMValidatorHelper.ir",
    "org/apache/xerces/jaxp/validation/StAXValidatorHelper.ir",
    "org/apache/xerces/jaxp/validation/StreamValidatorHelper.ir",
    "org/apache/xerces/jaxp/validation/ValidatorHandlerImpl.ir",
    "org/apache/xerces/impl/xs/traversers/SchemaContentHandler.ir",

    // Large catch-all families / old compiler patterns.
    "org/apache/xerces/impl/dv/xs/XSSimpleTypeDecl.ir",
    "org/apache/xerces/parsers/SecureProcessingConfiguration.ir",
    "org/apache/html/dom/HTMLDocumentImpl.ir",
    "org/cyberneko/html/SecuritySupport.ir",
    "org/fit/cssbox/layout/ContentImage.ir",

    // ObjectFactory is useful because it contains many small,
    // fragmented exception-table regions in one method family.
    "org/apache/html/dom/ObjectFactory.ir",

    // Real Wurm / obfuscated cases.
    "class/Pcb3P7vsWq.ir",
    "class/IfNs7Jz1Q.ir",
    "class/Jq4t95OqQi.ir",
    "class/Lf7dbzysqZ.ir",
    "com/wurmonline/client/WurmClientBase.ir",
    "com/wurmonline/client/renderer/gui/BWVHQVeaY.ir",
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
