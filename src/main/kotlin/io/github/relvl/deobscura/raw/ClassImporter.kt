package io.github.relvl.deobscura.raw

import io.github.relvl.deobscura.jar.JarLoadResult
import io.github.relvl.deobscura.jar.JarRole
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeModel
import java.lang.classfile.attribute.CodeAttribute
import java.lang.classfile.Instruction
import java.lang.classfile.Label
import java.lang.classfile.instruction.ArrayLoadInstruction
import java.lang.classfile.instruction.ArrayStoreInstruction
import java.lang.classfile.instruction.BranchInstruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConvertInstruction
import java.lang.classfile.instruction.DiscontinuedInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.IncrementInstruction
import java.lang.classfile.instruction.InvokeDynamicInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LabelTarget
import java.lang.classfile.instruction.LineNumber
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.LookupSwitchInstruction
import java.lang.classfile.instruction.MonitorInstruction
import java.lang.classfile.instruction.NewMultiArrayInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction
import java.lang.classfile.instruction.NewReferenceArrayInstruction
import java.lang.classfile.instruction.NopInstruction
import java.lang.classfile.instruction.OperatorInstruction
import java.lang.classfile.instruction.ReturnInstruction
import java.lang.classfile.instruction.StackInstruction
import java.lang.classfile.instruction.StoreInstruction
import java.lang.classfile.instruction.TableSwitchInstruction
import java.lang.classfile.instruction.ThrowInstruction
import java.lang.classfile.instruction.TypeCheckInstruction
import java.util.IdentityHashMap

class ClassImporter {
    private val classFile = ClassFile.of()

    fun importInput(jarLoadResult: JarLoadResult): RawImportResult {
        val classes = linkedMapOf<String, RawClass>()
        val warnings = mutableListOf<String>()
        var fieldCount = 0
        var methodCount = 0
        var methodsWithCode = 0
        var instructionCount = 0L
        var unknownInstructionCount = 0L

        jarLoadResult.classes.values
            .asSequence()
            .filter { it.origin.role == JarRole.INPUT }
            .forEach { loadedClass ->
                try {
                    val imported = importClass(loadedClass.bytes)
                    classes[imported.internalName] = imported
                    fieldCount += imported.fields.size
                    methodCount += imported.methods.size
                    methodsWithCode += imported.methods.count { it.code != null }
                    imported.methods.forEach { method ->
                        method.code?.let { code ->
                            instructionCount += code.instructions.size
                            unknownInstructionCount += code.instructions.count { it is RawUnknownInstruction }
                        }
                    }
                } catch (exception: Exception) {
                    warnings += "Failed to import class '${loadedClass.internalName}' into raw model: ${exception.message}."
                }
            }

        return RawImportResult(
            classes = classes,
            fieldCount = fieldCount,
            methodCount = methodCount,
            methodsWithCode = methodsWithCode,
            instructionCount = instructionCount,
            unknownInstructionCount = unknownInstructionCount,
            parseFailureCount = jarLoadResult.inputClassCount - classes.size,
            warnings = warnings,
        )
    }

    fun importClass(bytes: ByteArray): RawClass {
        val model = classFile.parse(bytes)
        return RawClass(
            internalName = model.thisClass().asInternalName(),
            majorVersion = model.majorVersion(),
            minorVersion = model.minorVersion(),
            accessFlags = model.flags().flagsMask(),
            superName = model.superclass().map { it.asInternalName() }.orElse(null),
            interfaces = model.interfaces().map { it.asInternalName() },
            fields = model.fields().map { field ->
                val descriptor = field.fieldType().stringValue()
                RawField(
                    name = field.fieldName().stringValue(),
                    descriptor = descriptor,
                    type = JvmType.parse(descriptor),
                    accessFlags = field.flags().flagsMask(),
                )
            },
            methods = model.methods().map { method ->
                val descriptor = method.methodType().stringValue()
                RawMethod(
                    name = method.methodName().stringValue(),
                    descriptor = descriptor,
                    type = JvmMethodDescriptor.parse(descriptor),
                    accessFlags = method.flags().flagsMask(),
                    exceptions = method.findAttribute(Attributes.exceptions())
                        .map { attribute -> attribute.exceptions().map { it.asInternalName() } }
                        .orElse(emptyList()),
                    code = method.code().map(::importCode).orElse(null),
                )
            },
        )
    }

    private fun importCode(code: CodeModel): RawCode {
        val labels = LabelRegistry()
        val instructions = mutableListOf<RawInstruction>()
        val labelPositions = linkedMapOf<RawLabelId, Int>()
        val lineNumbers = mutableListOf<RawLineNumber>()

        code.forEach { element ->
            when (element) {
                is LabelTarget -> labelPositions[labels.id(element.label())] = instructions.size
                is LineNumber -> lineNumbers += RawLineNumber(instructions.size, element.line())
                is Instruction -> instructions += importInstruction(element, labels)
                else -> Unit
            }
        }

        val handlers = code.exceptionHandlers().map { handler ->
            RawExceptionHandler(
                tryStart = labels.id(handler.tryStart()),
                tryEnd = labels.id(handler.tryEnd()),
                handler = labels.id(handler.handler()),
                catchType = handler.catchType().map { it.asInternalName() }.orElse(null),
            )
        }

        val codeAttribute = code as? CodeAttribute
        val rawLabels = labels.entries()
            .map { (label, id) ->
                RawLabel(
                    id = id,
                    instructionIndex = labelPositions[id] ?: instructions.size,
                    bytecodeOffset = codeAttribute?.labelToBci(label),
                )
            }
            .sortedBy { it.id.value }

        return RawCode(
            maxStack = codeAttribute?.maxStack(),
            maxLocals = codeAttribute?.maxLocals(),
            bytecodeLength = codeAttribute?.codeLength(),
            instructions = instructions,
            labels = rawLabels,
            exceptionHandlers = handlers,
            lineNumbers = lineNumbers,
        )
    }

    private fun importInstruction(instruction: Instruction, labels: LabelRegistry): RawInstruction {
        val opcode = JvmOpcode(instruction.opcode().name.lowercase())
        return when (instruction) {
            is ConstantInstruction -> RawConstantInstruction(
                opcode,
                instruction.typeKind().toRawType(),
                instruction.constantValue(),
            )
            is LoadInstruction -> RawLocalInstruction(
                opcode,
                LocalOperation.LOAD,
                instruction.typeKind().toRawType(),
                instruction.slot(),
            )
            is StoreInstruction -> RawLocalInstruction(
                opcode,
                LocalOperation.STORE,
                instruction.typeKind().toRawType(),
                instruction.slot(),
            )
            is IncrementInstruction -> RawIncrementInstruction(opcode, instruction.slot(), instruction.constant())
            is ArrayLoadInstruction -> RawArrayInstruction(
                opcode,
                ArrayOperation.LOAD,
                instruction.typeKind().toRawType(),
            )
            is ArrayStoreInstruction -> RawArrayInstruction(
                opcode,
                ArrayOperation.STORE,
                instruction.typeKind().toRawType(),
            )
            is OperatorInstruction -> RawOperatorInstruction(opcode, instruction.typeKind().toRawType())
            is ConvertInstruction -> RawConversionInstruction(
                opcode,
                instruction.fromType().toRawType(),
                instruction.toType().toRawType(),
            )
            is StackInstruction -> RawStackInstruction(opcode)
            is BranchInstruction -> RawBranchInstruction(opcode, labels.id(instruction.target()))
            is LookupSwitchInstruction -> RawSwitchInstruction(
                opcode = opcode,
                defaultTarget = labels.id(instruction.defaultTarget()),
                cases = instruction.cases().map { RawSwitchCase(it.caseValue(), labels.id(it.target())) },
            )
            is TableSwitchInstruction -> RawSwitchInstruction(
                opcode = opcode,
                defaultTarget = labels.id(instruction.defaultTarget()),
                cases = instruction.cases().map { RawSwitchCase(it.caseValue(), labels.id(it.target())) },
                lowValue = instruction.lowValue(),
                highValue = instruction.highValue(),
            )
            is FieldInstruction -> {
                val descriptor = instruction.type().stringValue()
                RawFieldInstruction(
                    opcode = opcode,
                    owner = instruction.owner().asInternalName(),
                    name = instruction.name().stringValue(),
                    descriptor = descriptor,
                    type = JvmType.parse(descriptor),
                )
            }
            is InvokeInstruction -> {
                val descriptor = instruction.type().stringValue()
                RawInvokeInstruction(
                    opcode = opcode,
                    owner = instruction.owner().asInternalName(),
                    name = instruction.name().stringValue(),
                    descriptor = descriptor,
                    type = JvmMethodDescriptor.parse(descriptor),
                    isInterface = instruction.isInterface(),
                )
            }
            is InvokeDynamicInstruction -> {
                val descriptor = instruction.type().stringValue()
                RawInvokeDynamicInstruction(
                    opcode = opcode,
                    name = instruction.name().stringValue(),
                    descriptor = descriptor,
                    type = JvmMethodDescriptor.parse(descriptor),
                    bootstrapMethod = instruction.bootstrapMethod(),
                    bootstrapArguments = instruction.bootstrapArgs(),
                )
            }
            is NewObjectInstruction -> RawNewObjectInstruction(opcode, instruction.className().asInternalName())
            is NewPrimitiveArrayInstruction -> RawNewArrayInstruction(
                opcode,
                instruction.typeKind().toJvmType(),
            )
            is NewReferenceArrayInstruction -> RawNewArrayInstruction(
                opcode,
                JvmType.ObjectType(instruction.componentType().asInternalName()),
            )
            is NewMultiArrayInstruction -> {
                val arrayType = JvmType.parse(instruction.arrayType().asInternalName())
                require(arrayType is JvmType.ArrayType) {
                    "multianewarray does not reference an array type: ${instruction.arrayType().asInternalName()}"
                }
                RawNewMultiArrayInstruction(opcode, arrayType, instruction.dimensions())
            }
            is TypeCheckInstruction -> RawTypeCheckInstruction(
                opcode,
                classEntryToJvmType(instruction.type().asInternalName()),
            )
            is ReturnInstruction -> RawReturnInstruction(opcode, instruction.typeKind().toRawType())
            is MonitorInstruction -> RawMonitorInstruction(opcode)
            is ThrowInstruction -> RawThrowInstruction(opcode)
            is NopInstruction -> RawNopInstruction(opcode)
            is DiscontinuedInstruction.JsrInstruction -> RawBranchInstruction(opcode, labels.id(instruction.target()))
            is DiscontinuedInstruction.RetInstruction -> RawRetInstruction(opcode, instruction.slot())
            else -> RawUnknownInstruction(opcode, instruction.javaClass.name)
        }
    }

    private fun java.lang.classfile.TypeKind.toRawType(): JvmComputationalType =
        JvmComputationalType.fromClassFileName(name)

    private fun java.lang.classfile.TypeKind.toJvmType(): JvmType = when (name) {
        "BOOLEAN" -> JvmType.BooleanType
        "BYTE" -> JvmType.ByteType
        "CHAR" -> JvmType.CharType
        "SHORT" -> JvmType.ShortType
        "INT" -> JvmType.IntType
        "LONG" -> JvmType.LongType
        "FLOAT" -> JvmType.FloatType
        "DOUBLE" -> JvmType.DoubleType
        else -> throw IllegalArgumentException("Type kind '$name' is not a primitive array component type.")
    }

    private fun classEntryToJvmType(value: String): JvmType =
        if (value.startsWith('[')) JvmType.parse(value) else JvmType.ObjectType(value)

    private class LabelRegistry {
        private val labels = IdentityHashMap<Label, RawLabelId>()
        private var nextId = 0

        fun id(label: Label): RawLabelId = labels.getOrPut(label) { RawLabelId(nextId++) }

        fun entries(): List<Pair<Label, RawLabelId>> = labels.entries.map { it.key to it.value }
    }
}

data class RawImportResult(
    val classes: Map<String, RawClass>,
    val fieldCount: Int,
    val methodCount: Int,
    val methodsWithCode: Int,
    val instructionCount: Long,
    val unknownInstructionCount: Long,
    val parseFailureCount: Int,
    val warnings: List<String>,
)
