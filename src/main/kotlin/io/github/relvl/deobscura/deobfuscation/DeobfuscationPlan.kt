package io.github.relvl.deobscura.deobfuscation

import io.github.relvl.deobscura.raw.RawClass
import io.github.relvl.deobscura.raw.methodParameterDescriptor
import io.github.relvl.deobscura.resolution.MethodOverrideAnalysis
import io.github.relvl.deobscura.resolution.MethodOverrideKey

/**
 * Stable source-facing renames derived from the complete application model.
 *
 * Analysis keeps original JVM symbols; source consumers resolve names through this plan so a rename
 * is applied consistently to declarations and every reference without mutating canonical input.
 */
data class DeobfuscationPlan(
    private val packageRenames: Map<PackageSegmentKey, String> = emptyMap(),
    private val fieldNames: Map<FieldKey, String> = emptyMap(),
    private val methodNames: Map<MethodKey, String> = emptyMap(),
    val stats: DeobfuscationStats = DeobfuscationStats(),
) {
    fun classInternalName(original: String): String = renamePackage(original, packageRenames)

    fun fieldName(ownerInternalName: String, name: String, descriptor: String): String = fieldNames[FieldKey(ownerInternalName, name, descriptor)] ?: name

    fun methodName(ownerInternalName: String, name: String, descriptor: String): String = methodNames[MethodKey(ownerInternalName, name, descriptor)] ?: name

    companion object {
        fun build(
            classes: Collection<RawClass>,
            enabled: Boolean,
            methodOverrides: MethodOverrideAnalysis = MethodOverrideAnalysis.EMPTY,
        ): DeobfuscationPlan {
            if (!enabled) return DeobfuscationPlan()

            val packageRenames = buildPackageRenames(classes)

            val fieldNames = linkedMapOf<FieldKey, String>()
            val methodNames = linkedMapOf<MethodKey, String>()

            classes.forEach { rawClass ->
                buildFieldRenames(rawClass, fieldNames)
            }
            buildMethodRenames(classes, methodOverrides, methodNames)

            val renamedMethodDeclarations = classes.sumOf { rawClass ->
                rawClass.methods.count { method -> MethodKey(rawClass.internalName, method.name, method.descriptor) in methodNames }
            }
            return DeobfuscationPlan(
                packageRenames = packageRenames,
                fieldNames = fieldNames,
                methodNames = methodNames,
                stats = DeobfuscationStats(
                    renamedPackageSegments = packageRenames.size,
                    renamedFields = fieldNames.size,
                    renamedMethods = renamedMethodDeclarations,
                ),
            )
        }

        private fun buildPackageRenames(classes: Collection<RawClass>): Map<PackageSegmentKey, String> {
            val childNames = linkedMapOf<String, LinkedHashSet<String>>()
            classes.forEach { rawClass ->
                val segments = rawClass.internalName.split('/').dropLast(1)
                var parent = ""
                segments.forEach { segment ->
                    childNames.getOrPut(parent) { linkedSetOf() } += segment
                    parent = if (parent.isEmpty()) segment else "$parent/$segment"
                }
            }

            val result = linkedMapOf<PackageSegmentKey, String>()
            val resolvedParents = mutableMapOf<String, String>("" to "")
            childNames.keys.sortedBy { it.count { char -> char == '/' } }.forEach { originalParent ->
                val renamedParent = resolvedParents[originalParent] ?: originalParent
                val children = childNames.getValue(originalParent)
                val used = children.filterTo(linkedSetOf(), ::isLegalJavaIdentifier)
                children.forEach { segment ->
                    val renamed = if (isLegalJavaIdentifier(segment)) {
                        segment
                    } else {
                        allocate("package_${sanitizeIdentifier(segment)}", used)
                    }
                    used += renamed
                    if (renamed != segment) result[PackageSegmentKey(originalParent, segment)] = renamed
                    val originalPath = if (originalParent.isEmpty()) segment else "$originalParent/$segment"
                    val renamedPath = if (renamedParent.isEmpty()) renamed else "$renamedParent/$renamed"
                    resolvedParents[originalPath] = renamedPath
                }
            }
            return result
        }

        private fun renamePackage(internalName: String, renames: Map<PackageSegmentKey, String>): String {
            val segments = internalName.split('/')
            if (segments.size == 1) return internalName
            val renamed = ArrayList<String>(segments.size)
            var parent = ""
            segments.dropLast(1).forEach { segment ->
                renamed += renames[PackageSegmentKey(parent, segment)] ?: segment
                parent = if (parent.isEmpty()) segment else "$parent/$segment"
            }
            renamed += segments.last()
            return renamed.joinToString("/")
        }

        private fun buildFieldRenames(rawClass: RawClass, output: MutableMap<FieldKey, String>) {
            val used = rawClass.fields.mapTo(linkedSetOf()) { it.name }
            rawClass.fields.groupBy { it.name }.values.filter { it.size > 1 }.forEach { duplicates ->
                var index = 1
                duplicates.forEach { field ->
                    val renamed = allocateIndexed(field.name, index, used)
                    index = renamed.substringAfterLast('_').toInt() + 1
                    used += renamed
                    output[FieldKey(rawClass.internalName, field.name, field.descriptor)] = renamed
                }
            }
        }

        private fun buildMethodRenames(
            classes: Collection<RawClass>,
            methodOverrides: MethodOverrideAnalysis,
            output: MutableMap<MethodKey, String>,
        ) {
            val classesByName = classes.associateBy { it.internalName }
            val assignedFamilies = linkedMapOf<MethodOverrideKey, String>()

            classes.sortedBy { it.internalName }.forEach { rawClass ->
                val methods = rawClass.methods.filterNot { it.name.startsWith("<") }
                methods.groupBy { methodParameterDescriptor(it.descriptor) }.forEach { (_, sameParameters) ->
                    sameParameters.groupBy { it.name }.values.filter { it.size > 1 }.forEach collision@{ duplicates ->
                        val units = duplicates.map { method ->
                            val key = MethodOverrideKey(rawClass.internalName, method.name, method.descriptor)
                            methodOverrides.familyOf(key) ?: key
                        }.distinct()
                        if (units.size <= 1) return@collision

                        val used = sameParameters.mapTo(linkedSetOf()) { it.name }
                        var index = 1
                        units.forEach unitLoop@{ unit ->
                            if (methodOverrides.isPinned(unit)) return@unitLoop
                            val existing = assignedFamilies[unit]
                            if (existing != null) {
                                used += existing
                                return@unitLoop
                            }
                            val base = duplicates.first().name
                            var renamed: String
                            do {
                                renamed = "${base}_${index++}"
                            } while (renamed in used || !isFamilyNameAvailable(unit, renamed, classesByName, methodOverrides, assignedFamilies))
                            assignedFamilies[unit] = renamed
                            used += renamed
                        }
                    }
                }
            }

            assignedFamilies.forEach { (unit, renamed) ->
                val members = methodOverrides.familyMembers(unit).ifEmpty { setOf(unit) }
                members.forEach { member -> output[MethodKey(member.ownerInternalName, member.name, member.descriptor)] = renamed }
            }

            // Symbolic owners may name a subclass/interface that inherits the actual declaration.
            classes.forEach { rawClass ->
                rawClass.methods.forEach { method ->
                    method.code?.instructions.orEmpty().filterIsInstance<io.github.relvl.deobscura.raw.RawInvokeInstruction>().forEach invokeLoop@{ invoke ->
                        val family = methodOverrides.familyOfReference(invoke.owner, invoke.name, invoke.descriptor) ?: return@invokeLoop
                        val renamed = assignedFamilies[family] ?: return@invokeLoop
                        output[MethodKey(invoke.owner, invoke.name, invoke.descriptor)] = renamed
                    }
                }
            }
        }

        private fun isFamilyNameAvailable(
            family: MethodOverrideKey,
            candidate: String,
            classesByName: Map<String, RawClass>,
            methodOverrides: MethodOverrideAnalysis,
            assignedFamilies: Map<MethodOverrideKey, String>,
        ): Boolean {
            val members = methodOverrides.familyMembers(family).ifEmpty { setOf(family) }
            return members.all { member ->
                val owner = classesByName[member.ownerInternalName] ?: return@all true
                val params = methodParameterDescriptor(member.descriptor)
                owner.methods.filterNot { it.name.startsWith("<") }.filter { methodParameterDescriptor(it.descriptor) == params }.none { other ->
                    val otherKey = MethodOverrideKey(owner.internalName, other.name, other.descriptor)
                    val otherFamily = methodOverrides.familyOf(otherKey) ?: otherKey
                    otherFamily != family && (assignedFamilies[otherFamily] ?: other.name) == candidate
                }
            }
        }

        private fun allocate(candidate: String, used: Set<String>): String {
            if (candidate !in used) return candidate
            var index = 1
            while ("${candidate}_$index" in used) index++
            return "${candidate}_$index"
        }

        private fun allocateIndexed(base: String, startIndex: Int, used: Set<String>): String {
            var index = startIndex
            while ("${base}_$index" in used) index++
            return "${base}_$index"
        }

        private fun sanitizeIdentifier(value: String): String = buildString {
            value.forEachIndexed { index, char ->
                val valid = if (index == 0) Character.isJavaIdentifierStart(char) else Character.isJavaIdentifierPart(char)
                if (valid) append(char) else append("_u${char.code.toString(16).padStart(4, '0')}")
            }
        }.ifEmpty { "unnamed" }

        private fun isLegalJavaIdentifier(value: String): Boolean =
            value.isNotEmpty() && Character.isJavaIdentifierStart(value[0]) && value.drop(1).all { Character.isJavaIdentifierPart(it) } && value !in JAVA_RESERVED_WORDS

        private val JAVA_RESERVED_WORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "_", "true", "false", "null",
        )
    }
}

data class FieldKey(val ownerInternalName: String, val name: String, val descriptor: String)
data class MethodKey(val ownerInternalName: String, val name: String, val descriptor: String)
data class PackageSegmentKey(val parentInternalName: String, val name: String)
data class DeobfuscationStats(
    val renamedPackageSegments: Int = 0,
    val renamedFields: Int = 0,
    val renamedMethods: Int = 0,
)
