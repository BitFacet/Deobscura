import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val projectRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

fun resolvePath(path: String): Path {
    val configured = Path.of(path)
    return if (configured.isAbsolute) {
        configured.normalize()
    } else {
        projectRoot.resolve(configured).normalize()
    }
}

// ---------------------- Configuration ----------------------
val searchRoot = resolvePath("../workspace/src/technical-ir/class")
val targetDir = resolvePath("../workspace")

require(Files.isDirectory(searchRoot)) {
    "Search root does not exist or is not a directory: $searchRoot"
}

val largestIr: Path = Files.walk(searchRoot).use { paths ->
    paths
        .filter { Files.isRegularFile(it) }
        .filter { it.fileName.toString().endsWith(".ir", ignoreCase = true) }
        .max(Comparator.comparingLong { Files.size(it) })
        .orElseThrow { IllegalStateException("No .ir files found under $searchRoot") }
}

Files.createDirectories(targetDir)

val target: Path = targetDir.resolve(largestIr.fileName)
Files.copy(largestIr, target, StandardCopyOption.REPLACE_EXISTING)

println("Copied largest IR file:")
println("  source: $largestIr")
println("  size:   ${Files.size(largestIr)} bytes")
println("  target: $target")
