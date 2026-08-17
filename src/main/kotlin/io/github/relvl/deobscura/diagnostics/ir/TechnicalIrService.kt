package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.analysis.FrameAnalysis
import io.github.relvl.deobscura.analysis.MethodAnalysis
import io.github.relvl.deobscura.analysis.MethodAnalysisException
import io.github.relvl.deobscura.analysis.SsaAnalysis
import io.github.relvl.deobscura.analysis.SsaOptimizationResult
import io.github.relvl.deobscura.analysis.ValueFlowAnalysis
import io.github.relvl.deobscura.cfg.ControlFlowGraph
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.controlflow.StructuredControlFlowAnalysis
import io.github.relvl.deobscura.normalize.LegacySubroutineNormalizationResult
import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.raw.RawMethod
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale

/** Collects optional technical-IR snapshots during analysis and writes them after the pipeline completes. */
object TechnicalIrService {
    private val logger = LoggerFactory.getLogger(TechnicalIrService::class.java)
    private val renderer = TechnicalIrRenderer()
    private val classes = linkedMapOf<String, ClassSnapshot>()

    private var root: Path? = null
    private var inputJar: Path? = null
    private var currentLocator: TechnicalIrLocator? = null

    val enabled: Boolean
        get() = root != null

    val locator: TechnicalIrLocator?
        get() = currentLocator

    fun configure(outputDirectory: Path?, inputJar: Path) {
        reset()
        if (outputDirectory == null) return

        deleteRecursively(outputDirectory)
        Files.createDirectories(outputDirectory)
        root = outputDirectory
        this.inputJar = inputJar
        currentLocator = TechnicalIrLocator(outputDirectory)
    }

    fun captureClass(rawClass: RawClass) {
        if (!enabled) return
        classes.getOrPut(rawClass.internalName) { ClassSnapshot(rawClass) }
    }

    fun captureNormalization(
        ownerInternalName: String,
        originalMethod: RawMethod,
        normalizedMethod: RawMethod,
        normalization: LegacySubroutineNormalizationResult,
    ) {
        method(ownerInternalName, originalMethod)?.apply {
            this.normalizedMethod = normalizedMethod
            this.normalization = normalization
        }
    }

    fun captureGraph(ownerInternalName: String, method: RawMethod, graph: ControlFlowGraph) {
        method(ownerInternalName, method)?.graph = graph
    }

    fun captureFrames(ownerInternalName: String, method: RawMethod, frames: FrameAnalysis) {
        method(ownerInternalName, method)?.frames = frames
    }

    fun captureValueFlow(ownerInternalName: String, method: RawMethod, valueFlow: ValueFlowAnalysis) {
        method(ownerInternalName, method)?.valueFlow = valueFlow
    }

    fun captureInitialSsa(ownerInternalName: String, method: RawMethod, ssa: SsaAnalysis) {
        method(ownerInternalName, method)?.initialSsa = ssa
    }

    fun captureOptimization(ownerInternalName: String, method: RawMethod, optimization: SsaOptimizationResult) {
        method(ownerInternalName, method)?.optimization = optimization
    }

    fun captureExpression(ownerInternalName: String, method: RawMethod, expression: ExpressionAnalysis) {
        method(ownerInternalName, method)?.expression = expression
    }

    fun captureStructuredControlFlow(
        ownerInternalName: String,
        method: RawMethod,
        structuredControlFlow: StructuredControlFlowAnalysis,
    ) {
        method(ownerInternalName, method)?.structuredControlFlow = structuredControlFlow
    }

    fun captureFailure(ownerInternalName: String, method: RawMethod, exception: MethodAnalysisException) {
        method(ownerInternalName, method)?.failure = exception
    }

    fun rootHint(): String = locator?.let { " Technical IR root: ${it.rootLocation()}." } ?: ""

    fun methodHint(ownerInternalName: String, methodName: String, descriptor: String): String =
        locator?.let { " Technical IR: ${it.methodLocation(ownerInternalName, methodName, descriptor)}." } ?: ""

    fun consumerLocations(consumers: Iterable<String>): List<String> {
        val currentLocator = locator ?: return emptyList()
        return consumers.mapNotNull(currentLocator::consumerLocation).distinct()
    }

    fun writeAll() {
        val outputDirectory = root ?: return
        val input = requireNotNull(inputJar)
        val startedAt = System.nanoTime()
        val snapshots = classes.values.toList()

        logger.info("Writing technical IR for {} class(es) to {}...", snapshots.size, outputDirectory)
        writeManifest(outputDirectory, input)

        var written = 0
        var nextProgressAt = startedAt + PROGRESS_INTERVAL_NANOS
        for (snapshot in snapshots) {
            writeClass(outputDirectory, snapshot)
            written++

            val now = System.nanoTime()
            if (now >= nextProgressAt) {
                val percent = if (snapshots.isEmpty()) 100 else (written.toLong() * 100 / snapshots.size).toInt()
                logger.info("Technical IR progress: {}/{} class(es) ({}%).", written, snapshots.size, percent)
                nextProgressAt = now + PROGRESS_INTERVAL_NANOS
            }
        }

        logger.info(
            "Technical IR written for {} class(es) to {} in {}.",
            written,
            outputDirectory,
            formatElapsed(System.nanoTime() - startedAt),
        )
    }

    fun reset() {
        classes.clear()
        root = null
        inputJar = null
        currentLocator = null
    }

    private fun method(ownerInternalName: String, method: RawMethod): MethodSnapshot? {
        if (!enabled) return null
        val owner = classes[ownerInternalName]
            ?: error("Technical IR class '$ownerInternalName' was not captured before method analysis.")
        return owner.methods.getOrPut(MethodKey(method.name, method.descriptor)) { MethodSnapshot(method) }
    }

    private fun writeClass(outputDirectory: Path, snapshot: ClassSnapshot) {
        val path = requireNotNull(currentLocator).classFile(snapshot.rawClass.internalName)
        Files.createDirectories(requireNotNull(path.parent))
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.appendEscapedMalformedUtf16(renderer.renderClassHeader(snapshot.rawClass))
            snapshot.methods.values.forEach { method ->
                val analysis = method.completeAnalysis()
                when {
                    analysis != null -> writer.appendEscapedMalformedUtf16(
                        renderer.renderMethod(analysis.method, analysis),
                    )
                    method.failure != null -> writer.appendEscapedMalformedUtf16(
                        renderer.renderFailure(method.originalMethod, requireNotNull(method.failure)),
                    )
                }
            }
        }
    }

    private fun writeManifest(outputDirectory: Path, input: Path) {
        Files.writeString(
            outputDirectory.resolve(MANIFEST_FILE),
            buildString {
                appendLine("deobscura-technical-ir")
                appendLine("format-version: $FORMAT_VERSION")
                appendLine("input: $input")
                appendLine("layout: one .ir file per input class; JVM internal-name path segments are filename-escaped")
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun deleteRecursively(path: Path) {
        if (Files.notExists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun formatElapsed(nanos: Long): String =
        String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0)

    const val FORMAT_VERSION = 11
    const val MANIFEST_FILE = "_manifest.txt"
    private const val PROGRESS_INTERVAL_NANOS = 5_000_000_000L
}

internal data class ClassSnapshot(
    val rawClass: RawClass,
    val methods: LinkedHashMap<MethodKey, MethodSnapshot> = linkedMapOf(),
)

internal data class MethodKey(
    val name: String,
    val descriptor: String,
)

internal class MethodSnapshot(
    val originalMethod: RawMethod,
) {
    var normalizedMethod: RawMethod? = null
    var normalization: LegacySubroutineNormalizationResult? = null
    var graph: ControlFlowGraph? = null
    var frames: FrameAnalysis? = null
    var valueFlow: ValueFlowAnalysis? = null
    var initialSsa: SsaAnalysis? = null
    var optimization: SsaOptimizationResult? = null
    var expression: ExpressionAnalysis? = null
    var structuredControlFlow: StructuredControlFlowAnalysis? = null
    var failure: MethodAnalysisException? = null

    fun completeAnalysis(): MethodAnalysis? {
        val method = normalizedMethod ?: return null
        val currentGraph = graph ?: return null
        val currentFrames = frames ?: return null
        val currentValueFlow = valueFlow ?: return null
        val currentInitialSsa = initialSsa ?: return null
        val currentOptimization = optimization ?: return null
        val currentExpression = expression ?: return null
        val currentStructuredControlFlow = structuredControlFlow ?: return null
        val currentNormalization = normalization ?: return null
        return MethodAnalysis(
            method = method,
            graph = currentGraph,
            frames = currentFrames,
            valueFlow = currentValueFlow,
            initialSsa = currentInitialSsa,
            optimization = currentOptimization,
            expression = currentExpression,
            structuredControlFlow = currentStructuredControlFlow,
            normalization = currentNormalization,
        )
    }
}

internal fun String.escapeMalformedUtf16(): String {
    var firstMalformed = -1
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isHighSurrogate()) {
            if (index + 1 < length && this[index + 1].isLowSurrogate()) {
                index += 2
                continue
            }
            firstMalformed = index
            break
        }
        if (char.isLowSurrogate()) {
            firstMalformed = index
            break
        }
        index++
    }
    if (firstMalformed < 0) return this

    return buildString(length + 8) {
        append(this@escapeMalformedUtf16, 0, firstMalformed)
        var cursor = firstMalformed
        while (cursor < this@escapeMalformedUtf16.length) {
            val char = this@escapeMalformedUtf16[cursor]
            if (char.isHighSurrogate() &&
                cursor + 1 < this@escapeMalformedUtf16.length &&
                this@escapeMalformedUtf16[cursor + 1].isLowSurrogate()
            ) {
                append(char)
                append(this@escapeMalformedUtf16[cursor + 1])
                cursor += 2
            } else if (char.isHighSurrogate() || char.isLowSurrogate()) {
                append("\\u")
                append(char.code.toString(16).uppercase().padStart(4, '0'))
                cursor++
            } else {
                append(char)
                cursor++
            }
        }
    }
}

private fun java.io.Writer.appendEscapedMalformedUtf16(value: String) {
    append(value.escapeMalformedUtf16())
}
