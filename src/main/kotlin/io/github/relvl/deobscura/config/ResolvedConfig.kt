package io.github.relvl.deobscura.config

import java.nio.file.Path

data class ResolvedConfig(
    val input: Path,
    val classpath: List<Path>,
    val runtime: Path,
    val runtimeVersion: Runtime.Version,
    val output: Path,
    val technicalIr: Path?,
)

data class ConfigResolution(
    val config: ResolvedConfig,
    val warnings: List<String>,
)
