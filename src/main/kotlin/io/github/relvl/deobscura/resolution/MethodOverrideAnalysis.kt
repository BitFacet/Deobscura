package io.github.relvl.deobscura.resolution

import io.github.relvl.deobscura.raw.*
import java.util.*

/**
 * Source-level virtual method relationships derived from the complete application hierarchy.
 *
 * Application declarations are grouped into override families. A family is pinned when it reaches
 * a runtime/classpath declaration: such a family must keep the external API name. The same facts
 * are also used by source rendering to restore @Override without relying on bytecode annotations.
 */
data class MethodOverrideKey(val ownerInternalName: String, val name: String, val descriptor: String)

@ConsistentCopyVisibility
data class MethodOverrideAnalysis internal constructor(
    private val familyByMethod: Map<MethodOverrideKey, MethodOverrideKey>,
    private val membersByFamily: Map<MethodOverrideKey, Set<MethodOverrideKey>>,
    private val pinnedFamilies: Set<MethodOverrideKey>,
    private val overridesSuper: Set<MethodOverrideKey>,
    private val declarations: Map<String, ClassMethods>,
    val stats: MethodOverrideStats = MethodOverrideStats(),
) {
    fun familyOf(method: MethodOverrideKey): MethodOverrideKey? = familyByMethod[method]

    fun familyMembers(method: MethodOverrideKey): Set<MethodOverrideKey> = familyOf(method)?.let { membersByFamily[it].orEmpty() }.orEmpty()

    fun isPinned(method: MethodOverrideKey): Boolean = familyOf(method) in pinnedFamilies

    fun overridesSuperMethod(ownerInternalName: String, name: String, descriptor: String): Boolean = MethodOverrideKey(ownerInternalName, name, descriptor) in overridesSuper

    /** Resolves an application declaration family for a symbolic method owner, including inherited members. */
    fun familyOfReference(ownerInternalName: String, name: String, descriptor: String): MethodOverrideKey? {
        val visited = mutableSetOf<String>()
        fun visit(owner: String): MethodOverrideKey? {
            if (!visited.add(owner)) return null
            val declaration = declarations[owner] ?: return null
            declaration.methods.firstOrNull { it.name == name && it.descriptor == descriptor }?.let { method ->
                familyByMethod[MethodOverrideKey(owner, method.name, method.descriptor)]?.let { return it }
            }
            declaration.superName?.let { visit(it)?.let { family -> return family } }
            declaration.interfaces.forEach { interfaceName ->
                visit(interfaceName)?.let { family -> return family }
            }
            return null
        }
        return visit(ownerInternalName)
    }

    companion object {
        val EMPTY = MethodOverrideAnalysis(emptyMap(), emptyMap(), emptySet(), emptySet(), emptyMap())
    }
}

data class MethodOverrideStats(
    val virtualFamilies: Int = 0,
    val overridingMethods: Int = 0,
    val externalApiFamilies: Int = 0,
)

/** Builds virtual method families while treating classpath/runtime declarations as read-only API anchors. */
class MethodOverrideAnalyzer private constructor(
    private val externalLookup: (String) -> RawClass?,
    private val returnAssignable: (JvmType, JvmType, String) -> Boolean?,
) {
    constructor(resolver: ClassResolver, hierarchy: ClassHierarchy) : this(
        externalLookup = resolverClassLookup(resolver),
        returnAssignable = { target, source, consumer ->
            hierarchy.isAssignable(JvmReferenceType.Exact(target), JvmReferenceType.Exact(source), consumer)
        },
    )

    internal constructor(
        externalClasses: Map<String, RawClass>,
        returnAssignable: (JvmType, JvmType, String) -> Boolean? = { target, source, _ -> target == source },
    ) : this(externalLookup = externalClasses::get, returnAssignable = returnAssignable)

    fun analyze(rawImport: RawImportResult): MethodOverrideAnalysis {
        val application = rawImport.classes
        val declarations = linkedMapOf<String, ClassMethods>()
        application.values.forEach { rawClass -> declarations[rawClass.internalName] = rawClass.toClassMethods(external = false) }

        fun load(owner: String): ClassMethods? {
            declarations[owner]?.let { return it }
            return try {
                externalLookup(owner)?.toClassMethods(external = owner !in application)?.also { declarations[owner] = it }
            } catch (_: RuntimeException) {
                null
            }
        }

        // Materialize ancestry once so reference resolution later does not need resolver access.
        val queue = ArrayDeque(application.values.flatMap { listOfNotNull(it.superName) + it.interfaces })
        val visitedTypes = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val owner = queue.removeFirst()
            if (!visitedTypes.add(owner)) continue
            load(owner)?.let { declaration ->
                declaration.superName?.let(queue::addLast)
                declaration.interfaces.forEach(queue::addLast)
            }
        }

        val virtualMethods = application.values.flatMap { rawClass ->
            rawClass.methods.filter(::isVirtualMethod).map { method -> MethodOverrideKey(rawClass.internalName, method.name, method.descriptor) }
        }
        val disjoint = MethodFamilySet(virtualMethods)
        val overridesSuper = linkedSetOf<MethodOverrideKey>()
        val externallyAnchored = linkedSetOf<MethodOverrideKey>()

        application.values.forEach { rawClass ->
            rawClass.methods.filter(::isVirtualMethod).forEach { method ->
                val child = MethodOverrideKey(rawClass.internalName, method.name, method.descriptor)
                ancestors(rawClass, declarations).forEach { ancestorPath ->
                    val ancestor = ancestorPath.declaration
                    ancestor.methods.filter { candidate -> candidate.name == method.name }.filter { candidate -> methodParameterDescriptor(candidate.descriptor) == methodParameterDescriptor(method.descriptor) }
                        .filter { candidate -> isOverridableFrom(candidate, ancestorPath) }.filter { candidate -> returnsAreOverrideCompatible(method.descriptor, candidate.descriptor, rawClass.internalName) }
                        .forEach { candidate ->
                            overridesSuper += child
                            val parent = MethodOverrideKey(ancestor.internalName, candidate.name, candidate.descriptor)
                            if (ancestor.external) {
                                externallyAnchored += child
                            } else if (parent in disjoint) {
                                disjoint.union(child, parent)
                            }
                        }
                }
            }
        }

        val familyByMethod = virtualMethods.associateWith { disjoint.root(it) }
        val membersByFamily = familyByMethod.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
        val pinnedFamilies = externallyAnchored.mapNotNullTo(linkedSetOf()) { familyByMethod[it] }

        return MethodOverrideAnalysis(
            familyByMethod = familyByMethod,
            membersByFamily = membersByFamily,
            pinnedFamilies = pinnedFamilies,
            overridesSuper = overridesSuper,
            declarations = declarations,
            stats = MethodOverrideStats(
                virtualFamilies = membersByFamily.size,
                overridingMethods = overridesSuper.size,
                externalApiFamilies = pinnedFamilies.size,
            ),
        )
    }

    private fun ancestors(rawClass: RawClass, declarations: Map<String, ClassMethods>): Sequence<AncestorPath> = sequence {
        val queue = ArrayDeque<AncestorPath>()
        val childPackage = packageName(rawClass.internalName)
        listOfNotNull(rawClass.superName).plus(rawClass.interfaces).forEach { owner ->
            declarations[owner]?.let { queue.addLast(AncestorPath(it, listOf(childPackage))) }
        }
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val declaration = path.declaration
            if (!visited.add(declaration.internalName)) continue
            yield(path)
            val descendantPackages = path.descendantPackages + packageName(declaration.internalName)
            declaration.superName?.let { owner ->
                declarations[owner]?.let { queue.addLast(AncestorPath(it, descendantPackages)) }
            }
            declaration.interfaces.forEach { owner ->
                declarations[owner]?.let { queue.addLast(AncestorPath(it, descendantPackages)) }
            }
        }
    }

    private fun returnsAreOverrideCompatible(childDescriptor: String, parentDescriptor: String, consumer: String): Boolean {
        val child = JvmMethodDescriptor.parse(childDescriptor).returnType
        val parent = JvmMethodDescriptor.parse(parentDescriptor).returnType
        if (child == parent) return true
        if (!child.isReferenceType || !parent.isReferenceType) return false
        return returnAssignable(parent, child, consumer) == true
    }

    private fun isOverridableFrom(method: MethodDeclaration, ancestorPath: AncestorPath): Boolean {
        if (method.accessFlags and (ACC_PRIVATE or ACC_STATIC or ACC_FINAL) != 0) return false
        if (method.accessFlags and (ACC_PUBLIC or ACC_PROTECTED) != 0) return true
        val ownerPackage = packageName(ancestorPath.declaration.internalName)
        return ancestorPath.descendantPackages.all { it == ownerPackage }
    }

    private fun RawClass.toClassMethods(external: Boolean) = ClassMethods(
        internalName = internalName,
        superName = superName,
        interfaces = interfaces,
        methods = methods.map { MethodDeclaration(it.name, it.descriptor, it.accessFlags) },
        external = external,
    )

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_PRIVATE = 0x0002
        const val ACC_PROTECTED = 0x0004
        const val ACC_STATIC = 0x0008
        const val ACC_FINAL = 0x0010
    }
}

private data class AncestorPath(
    val declaration: ClassMethods,
    val descendantPackages: List<String>,
)

internal data class ClassMethods(
    val internalName: String,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<MethodDeclaration>,
    val external: Boolean,
)

internal data class MethodDeclaration(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
)

private class MethodFamilySet(methods: Collection<MethodOverrideKey>) {
    private val parent = methods.associateWithTo(linkedMapOf()) { it }

    operator fun contains(method: MethodOverrideKey): Boolean = method in parent

    fun root(method: MethodOverrideKey): MethodOverrideKey {
        val current = parent.getValue(method)
        if (current == method) return method
        return root(current).also { parent[method] = it }
    }

    fun union(left: MethodOverrideKey, right: MethodOverrideKey) {
        val leftRoot = root(left)
        val rightRoot = root(right)
        if (leftRoot == rightRoot) return
        val canonical = minOf(leftRoot, rightRoot, compareBy(MethodOverrideKey::ownerInternalName, MethodOverrideKey::name, MethodOverrideKey::descriptor))
        val other = if (canonical == leftRoot) rightRoot else leftRoot
        parent[other] = canonical
    }
}

private fun isVirtualMethod(method: RawMethod): Boolean = !method.name.startsWith("<") && method.accessFlags and (0x0002 or 0x0008) == 0

private fun packageName(internalName: String): String = internalName.substringBeforeLast('/', missingDelimiterValue = "")

private fun resolverClassLookup(resolver: ClassResolver): (String) -> RawClass? {
    val importer = ClassImporter()
    return { owner -> resolver.findClass(owner)?.let { resolved -> importer.importClass(resolved.bytes) } }
}
