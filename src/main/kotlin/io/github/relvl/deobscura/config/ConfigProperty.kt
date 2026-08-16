package io.github.relvl.deobscura.config

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigProperty(
    val description: String,
)
