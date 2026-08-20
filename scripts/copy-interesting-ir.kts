import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val interestingFiles = listOf(
    "com/wurmonline/client/renderer/gui/nxNUICZucW.ir",
    "com/wurmonline/client/renderer/gui/nxNUICZucW.java",
    "com/wurmonline/client/renderer/gui/eu0bDYoN0J.ir",
    "com/wurmonline/client/renderer/gui/eu0bDYoN0J.java",
    "com/wurmonline/client/renderer/gui/txg1j8dsgN.ir",
    "com/wurmonline/client/renderer/gui/txg1j8dsgN.java",
    "com/wurmonline/client/renderer/gui/TAQgJjbDW.ir",
    "com/wurmonline/client/renderer/gui/TAQgJjbDW.java",
    "com/wurmonline/client/renderer/gui/F4WVf7ErMq.ir",
    "com/wurmonline/client/renderer/gui/F4WVf7ErMq.java",
    "com/wurmonline/client/renderer/gui/TzLAhOL5vv.ir",
    "com/wurmonline/client/renderer/gui/TzLAhOL5vv.java",
    "com/wurmonline/client/renderer/gui/i79JMME6SY.ir",
    "com/wurmonline/client/renderer/gui/i79JMME6SY.java",

    "com/sun/javafx/geom/BaseBounds.ir",
    "com/sun/javafx/geom/BaseBounds.java",
    "com/sun/javafx/geom/RectBounds.ir",
    "com/sun/javafx/geom/RectBounds.java",
    "com/sun/javafx/geom/BoxBounds.ir",
    "com/sun/javafx/geom/BoxBounds.java",

    "com/sun/javafx/geom/transform/BaseTransform.ir",
    "com/sun/javafx/geom/transform/BaseTransform.java",
    "com/sun/javafx/geom/transform/AffineBase.ir",
    "com/sun/javafx/geom/transform/AffineBase.java",
    "com/sun/javafx/geom/transform/Affine3D.ir",
    "com/sun/javafx/geom/transform/Affine3D.java",
    "com/sun/javafx/geom/transform/Translate2D.ir",
    "com/sun/javafx/geom/transform/Translate2D.java",
    "com/sun/javafx/geom/transform/Identity.ir",
    "com/sun/javafx/geom/transform/Identity.java",
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
