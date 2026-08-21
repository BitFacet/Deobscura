package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.analysis.MethodAnalysis
import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan
import io.github.relvl.deobscura.output.classOutputFile
import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.resolution.MethodOverrideAnalysis
import io.github.relvl.deobscura.util.formatElapsedSeconds
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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
        val owner = classes[ownerInternalName] ?: error("Source output class '$ownerInternalName' was not captured before method analysis.")
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
            val path = classOutputFile(outputDirectory, sourceInternalName, "java")
            Files.createDirectories(requireNotNull(path.parent))
            Files.writeString(path, renderer.renderClass(snapshot.rawClass, snapshot.methods), StandardCharsets.UTF_8)
        }

        logger.info(
            "Java-like source written for {} class(es) to {} in {}.",
            snapshots.size,
            outputDirectory,
            formatElapsedSeconds(System.nanoTime() - startedAt),
        )
    }

    fun reset() {
        classes.clear()
        root = null
        deobfuscation = DeobfuscationPlan()
        methodOverrides = MethodOverrideAnalysis.EMPTY
    }
}

private data class SourceClassSnapshot(
    val rawClass: RawClass,
    val methods: LinkedHashMap<SourceMethodKey, MethodAnalysis> = linkedMapOf(),
)
