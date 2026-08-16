package io.github.relvl.deobscura.config

/**
 * User-facing configuration as it is represented in JSONC.
 *
 * Paths are intentionally kept as strings here. They are resolved against the working directory
 * by [ConfigResolver] before they are used by the rest of the application.
 */
data class DeobscuraConfig(
    @ConfigProperty("JAR file to analyze.")
    val input: String = "input.jar",

    @ConfigProperty("Additional JAR files used for class resolution. Glob patterns are supported.")
    val classpath: List<String> = emptyList(),

    @ConfigProperty("Target Java runtime directory. null uses the runtime of the current JVM.")
    val runtime: String? = null,

    @ConfigProperty("Output directory.")
    val output: String = "out",
)
