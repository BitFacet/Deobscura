package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalysis
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.resolution.MethodOverrideAnalysis
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** Collects completed analyses and writes the first Java-like source projection after analysis. */
object SourceOutputService {
    private val logger = LoggerFactory.getLogger(SourceOutputService::class.java)
    private val classes = linkedMapOf<String, SourceClassSnapshot>()
    private var root: Path? = null
    private var deobfuscation: DeobfuscationPlan = DeobfuscationPlan()
    private var methodOverrides: MethodOverrideAnalysis = MethodOverrideAnalysis.EMPTY

    val enabled: Boolean
        get() = root != null

    fun configure(outputDirectory: Path) {
        reset()
        Files.createDirectories(outputDirectory)
        root = outputDirectory
    }

    fun setDeobfuscation(plan: DeobfuscationPlan) {
        deobfuscation = plan
    }

    fun setMethodOverrides(analysis: MethodOverrideAnalysis) {
        methodOverrides = analysis
    }

    fun captureClass(rawClass: RawClass) {
        if (!enabled) return
        classes.getOrPut(rawClass.internalName) { SourceClassSnapshot(rawClass) }
    }

    fun captureMethod(ownerInternalName: String, analysis: MethodAnalysis) {
        if (!enabled) return
        val owner = classes[ownerInternalName]
            ?: error("Source output class '$ownerInternalName' was not captured before method analysis.")
        owner.methods[SourceMethodKey(analysis.method.name, analysis.method.descriptor)] = analysis
    }

    fun writeAll() {
        val outputDirectory = root ?: return
        val startedAt = System.nanoTime()
        val snapshots = classes.values.toList()
        val renderer = JavaLikeSourceRenderer(deobfuscation, methodOverrides)
        logger.info("Writing Java-like source for {} class(es) to {}...", snapshots.size, outputDirectory)

        snapshots.forEach { snapshot ->
            val sourceInternalName = deobfuscation.classInternalName(snapshot.rawClass.internalName)
            val path = sourceFile(outputDirectory, sourceInternalName)
            Files.createDirectories(requireNotNull(path.parent))
            Files.writeString(path, renderer.renderClass(snapshot.rawClass, snapshot.methods), StandardCharsets.UTF_8)
        }

        logger.info(
            "Java-like source written for {} class(es) to {} in {}.",
            snapshots.size,
            outputDirectory,
            formatElapsed(System.nanoTime() - startedAt),
        )
    }

    fun reset() {
        classes.clear()
        root = null
        deobfuscation = DeobfuscationPlan()
        methodOverrides = MethodOverrideAnalysis.EMPTY
    }

    private fun sourceFile(root: Path, internalName: String): Path {
        val segments = internalName.split('/')
        require(segments.isNotEmpty() && segments.none(String::isEmpty)) {
            "Invalid class internal name '$internalName'."
        }
        val packageSegments = segments.dropLast(1).map(::encodePathSegment)
        val fileName = "${encodePathSegment(segments.last())}.java"
        return packageSegments.fold(root) { current, segment -> current.resolve(segment) }
            .resolve(fileName)
            .normalize()
    }

    private fun encodePathSegment(segment: String): String {
        val encoded = buildString {
            segment.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xff
                val char = value.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-' || char == '$') {
                    append(char)
                } else {
                    append('%')
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }
        if (encoded.uppercase() !in WINDOWS_RESERVED_NAMES) return encoded
        val first = encoded[0].code
        return "%${HEX[first ushr 4]}${HEX[first and 0x0f]}${encoded.substring(1)}"
    }

    private fun formatElapsed(nanos: Long): String =
        String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0)

    private const val HEX = "0123456789ABCDEF"
    private val WINDOWS_RESERVED_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { number ->
            add("COM$number")
            add("LPT$number")
        }
    }
}

private data class SourceClassSnapshot(
    val rawClass: RawClass,
    val methods: LinkedHashMap<SourceMethodKey, MethodAnalysis> = linkedMapOf(),
)
