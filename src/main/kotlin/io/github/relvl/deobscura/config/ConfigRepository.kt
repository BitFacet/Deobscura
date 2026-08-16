package io.github.relvl.deobscura.config

import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

class ConfigRepository {
    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS, true)
        .configure(JsonReadFeature.ALLOW_TRAILING_COMMA, true)
        .build()

    fun loadOrCreate(path: Path): ConfigLoadResult {
        if (Files.notExists(path)) {
            write(path, DeobscuraConfig())
            return ConfigLoadResult.Created(path)
        }

        if (!Files.isRegularFile(path)) {
            throw ConfigException("Configuration path is not a file: $path")
        }

        return try {
            ConfigLoadResult.Loaded(mapper.readValue<DeobscuraConfig>(path.toFile()))
        } catch (exception: Exception) {
            throw ConfigException("Failed to read configuration '$path': ${exception.message}", exception)
        }
    }

    fun write(path: Path, config: DeobscuraConfig) {
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        try {
            Files.writeString(path, render(config))
        } catch (exception: Exception) {
            throw ConfigException("Failed to write configuration '$path': ${exception.message}", exception)
        }
    }

    private fun render(config: DeobscuraConfig): String {
        val constructor = DeobscuraConfig::class.primaryConstructor
            ?: throw ConfigException("DeobscuraConfig must have a primary constructor.")
        val properties = DeobscuraConfig::class.memberProperties.associateBy { it.name }

        val renderedProperties = constructor.parameters.map { parameter ->
            val name = parameter.name
                ?: throw ConfigException("All DeobscuraConfig constructor parameters must be named.")
            val property = properties[name]
                ?: throw ConfigException("No property found for DeobscuraConfig constructor parameter '$name'.")
            val description = parameter.annotations
                .filterIsInstance<ConfigProperty>()
                .singleOrNull()
                ?.description
                ?: throw ConfigException("DeobscuraConfig property '$name' is missing @ConfigProperty.")

            buildString {
                description.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { append("  // ").append(it).append('\n') }

                append("  ")
                append(mapper.writeValueAsString(name))
                append(": ")

                val valueJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(property.get(config))
                append(valueJson.replace("\n", "\n  "))
            }
        }

        return renderedProperties.joinToString(
            separator = ",\n\n",
            prefix = "{\n",
            postfix = "\n}\n",
        )
    }
}

sealed interface ConfigLoadResult {
    data class Loaded(val config: DeobscuraConfig) : ConfigLoadResult
    data class Created(val path: Path) : ConfigLoadResult
}

class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
