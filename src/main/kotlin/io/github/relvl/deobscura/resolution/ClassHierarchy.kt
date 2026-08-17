package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.raw.JvmReferenceType
import io.github.relvl.deobscura.raw.JvmType
import java.lang.classfile.ClassFile

/** Lazy hierarchy view over input, classpath and runtime classes. */
class ClassHierarchy(
    private val resolver: ClassResolver,
) {
    private val classFile = ClassFile.of()
    private val nodes = mutableMapOf<String, ClassHierarchyNode>()
    private val unparseable = mutableSetOf<String>()

    fun commonSupertype(
        left: JvmReferenceType,
        right: JvmReferenceType,
        consumer: String,
    ): JvmReferenceType {
        if (left == right) return left
        if (left == JvmReferenceType.Null) return right
        if (right == JvmReferenceType.Null) return left
        if (left == JvmReferenceType.Unknown || right == JvmReferenceType.Unknown) return JvmReferenceType.Unknown

        left as JvmReferenceType.Exact
        right as JvmReferenceType.Exact

        val leftAcceptsRight = isAssignable(left.type, right.type, consumer, ResolutionPurpose.COMMON_SUPERTYPE)
        if (leftAcceptsRight == true) return left
        val rightAcceptsLeft = isAssignable(right.type, left.type, consumer, ResolutionPurpose.COMMON_SUPERTYPE)
        if (rightAcceptsLeft == true) return right
        if (leftAcceptsRight == null || rightAcceptsLeft == null) return JvmReferenceType.Unknown

        if (left.type is JvmType.ArrayType && right.type is JvmType.ArrayType) {
            return commonArrayType(left.type, right.type, consumer)
        }
        if (left.type is JvmType.ArrayType || right.type is JvmType.ArrayType) {
            return exactObject(OBJECT)
        }

        val leftName = (left.type as JvmType.ObjectType).internalName
        val rightType = right.type as JvmType.ObjectType
        var current: String? = leftName
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (isAssignable(JvmType.ObjectType(current), rightType, consumer, ResolutionPurpose.COMMON_SUPERTYPE) == true) {
                return exactObject(current)
            }
            current = node(current, ResolutionPurpose.COMMON_SUPERTYPE, consumer)?.superName
                ?: if (current == OBJECT) null else return JvmReferenceType.Unknown
        }
        return exactObject(OBJECT)
    }

    fun isAssignable(
        target: JvmReferenceType,
        source: JvmReferenceType,
        consumer: String,
    ): Boolean? = when {
        source == JvmReferenceType.Null -> true
        target == JvmReferenceType.Unknown || source == JvmReferenceType.Unknown -> null
        target == JvmReferenceType.Null -> source == JvmReferenceType.Null
        else -> isAssignable(
            (target as JvmReferenceType.Exact).type,
            (source as JvmReferenceType.Exact).type,
            consumer,
            ResolutionPurpose.ASSIGNABILITY,
        )
    }

    private fun commonArrayType(
        left: JvmType.ArrayType,
        right: JvmType.ArrayType,
        consumer: String,
    ): JvmReferenceType {
        val leftComponent = left.componentType
        val rightComponent = right.componentType
        if (leftComponent == rightComponent) return JvmReferenceType.Exact(left)
        if (!leftComponent.isReference || !rightComponent.isReference) return exactObject(OBJECT)

        val component = commonSupertype(
            JvmReferenceType.Exact(leftComponent),
            JvmReferenceType.Exact(rightComponent),
            consumer,
        )
        return when (component) {
            is JvmReferenceType.Exact -> JvmReferenceType.Exact(JvmType.ArrayType(component.type))
            JvmReferenceType.Unknown -> JvmReferenceType.Unknown
            JvmReferenceType.Null -> error("Array component common supertype cannot be null.")
        }
    }

    private fun isAssignable(
        target: JvmType,
        source: JvmType,
        consumer: String,
        purpose: ResolutionPurpose,
    ): Boolean? {
        if (target == source) return true
        if (target is JvmType.ArrayType) {
            if (source !is JvmType.ArrayType) return false
            val targetComponent = target.componentType
            val sourceComponent = source.componentType
            if (!targetComponent.isReference || !sourceComponent.isReference) return targetComponent == sourceComponent
            return isAssignable(targetComponent, sourceComponent, consumer, purpose)
        }
        if (source is JvmType.ArrayType) {
            val targetName = (target as? JvmType.ObjectType)?.internalName ?: return false
            return targetName == OBJECT || targetName == CLONEABLE || targetName == SERIALIZABLE
        }

        val targetName = (target as JvmType.ObjectType).internalName
        val sourceName = (source as JvmType.ObjectType).internalName
        if (targetName == OBJECT) return true
        return hasSupertype(sourceName, targetName, purpose, consumer, mutableSetOf())
    }

    private fun hasSupertype(
        current: String,
        target: String,
        purpose: ResolutionPurpose,
        consumer: String,
        visited: MutableSet<String>,
    ): Boolean? {
        if (current == target) return true
        if (!visited.add(current)) return false
        val node = node(current, purpose, consumer) ?: return null

        var incomplete = false
        node.superName?.let { superName ->
            when (hasSupertype(superName, target, purpose, consumer, visited)) {
                true -> return true
                null -> incomplete = true
                false -> Unit
            }
        }
        for (interfaceName in node.interfaces) {
            when (hasSupertype(interfaceName, target, purpose, consumer, visited)) {
                true -> return true
                null -> incomplete = true
                false -> Unit
            }
        }
        return if (incomplete) null else false
    }

    private fun node(
        internalName: String,
        purpose: ResolutionPurpose,
        consumer: String,
    ): ClassHierarchyNode? {
        nodes[internalName]?.let { return it }
        if (internalName in unparseable) return null
        val resolved = resolver.findClassForAnalysis(internalName, purpose, consumer) ?: return null
        return try {
            val model = classFile.parse(resolved.bytes)
            ClassHierarchyNode(
                internalName = internalName,
                superName = model.superclass().map { it.asInternalName() }.orElse(null),
                interfaces = model.interfaces().map { it.asInternalName() },
                accessFlags = model.flags().flagsMask(),
            ).also { nodes[internalName] = it }
        } catch (_: RuntimeException) {
            unparseable += internalName
            null
        }
    }

    val loadedClassCount: Int
        get() = nodes.size

    private fun exactObject(internalName: String) = JvmReferenceType.Exact(JvmType.ObjectType(internalName))

    private val JvmType.isReference: Boolean
        get() = this is JvmType.ObjectType || this is JvmType.ArrayType

    private companion object {
        const val OBJECT = "java/lang/Object"
        const val CLONEABLE = "java/lang/Cloneable"
        const val SERIALIZABLE = "java/io/Serializable"
    }
}

data class ClassHierarchyNode(
    val internalName: String,
    val superName: String?,
    val interfaces: List<String>,
    val accessFlags: Int,
)
