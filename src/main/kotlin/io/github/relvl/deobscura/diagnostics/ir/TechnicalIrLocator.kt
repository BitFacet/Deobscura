package io.github.relvl.deobscura.diagnostics.ir

import io.github.relvl.deobscura.output.classOutputFile
import java.nio.file.Path

class TechnicalIrLocator(
    val root: Path,
    private val classInternalName: (String) -> String = { it },
) {
    fun classFile(ownerInternalName: String): Path = classOutputFile(root, classInternalName(ownerInternalName), "ir")

    fun rootLocation(): String = root.toString()

    fun classLocation(ownerInternalName: String): String = classFile(ownerInternalName).toString()

    fun methodLocation(ownerInternalName: String, methodName: String, descriptor: String): String = "${classLocation(ownerInternalName)} :: $methodName$descriptor"

    fun consumerLocation(consumer: String): String? {
        val separator = consumer.lastIndexOf('.')
        if (separator <= 0 || separator == consumer.lastIndex) return null
        val owner = consumer.substring(0, separator)
        val method = consumer.substring(separator + 1)
        if ('/' !in owner || '(' !in method) return null
        return "${classLocation(owner)} :: $method"
    }
}
