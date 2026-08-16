package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.jar.LoadedClass
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.NameAndTypeEntry

class ClassReferenceScanner {
    private val classFile = ClassFile.of()

    fun scan(loadedClass: LoadedClass): Set<ClassReference> {
        val model = classFile.parse(loadedClass.bytes)
        val references = linkedSetOf<ClassReference>()

        model.superclass().ifPresent { superclass ->
            addClassEntry(references, superclass.asInternalName(), ReferenceKind.STRUCTURAL)
        }
        model.interfaces().forEach { interfaceEntry ->
            addClassEntry(references, interfaceEntry.asInternalName(), ReferenceKind.STRUCTURAL)
        }

        model.fields().forEach { field ->
            addDescriptorReferences(references, field.fieldType().stringValue(), ReferenceKind.SIGNATURE)
        }
        model.methods().forEach { method ->
            addDescriptorReferences(references, method.methodType().stringValue(), ReferenceKind.SIGNATURE)
            method.findAttribute(Attributes.exceptions()).ifPresent { exceptions ->
                exceptions.exceptions().forEach { exception ->
                    addClassEntry(references, exception.asInternalName(), ReferenceKind.SIGNATURE)
                }
            }
        }

        for (entry in model.constantPool()) {
            when (entry) {
                is ClassEntry -> addClassEntry(references, entry.asInternalName(), ReferenceKind.CONSTANT_POOL)
                is NameAndTypeEntry -> addDescriptorReferences(
                    references,
                    entry.type().stringValue(),
                    ReferenceKind.CONSTANT_POOL,
                )

                else -> Unit
            }
        }

        references.removeIf { it.internalName == loadedClass.internalName }
        return references
    }

    private fun addClassEntry(
        references: MutableSet<ClassReference>,
        value: String,
        kind: ReferenceKind,
    ) {
        if (value.startsWith('[')) {
            addDescriptorReferences(references, value, kind)
        } else {
            references += ClassReference(value, kind)
        }
    }

    private fun addDescriptorReferences(
        references: MutableSet<ClassReference>,
        descriptor: String,
        kind: ReferenceKind,
    ) {
        OBJECT_TYPE.findAll(descriptor).forEach { match ->
            references += ClassReference(match.groupValues[1], kind)
        }
    }

    private companion object {
        val OBJECT_TYPE = Regex("L([^;]+);")
    }
}

data class ClassReference(
    val internalName: String,
    val kind: ReferenceKind,
)

enum class ReferenceKind(val priority: Int) {
    CONSTANT_POOL(0),
    SIGNATURE(1),
    STRUCTURAL(2),
}
