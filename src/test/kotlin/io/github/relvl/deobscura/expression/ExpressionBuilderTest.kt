package io.github.relvl.deobscura.expression

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.raw.*
import kotlin.test.*

class ExpressionBuilderTest {
    private val builder = ExpressionBuilder()

    @Test
    fun `lifts typed SSA operations into source-like expressions`() {
        val receiver = ValueId(0)
        val argument = ValueId(1)
        val sum = ValueId(2)
        val field = ValueId(3)
        val call = ValueId(4)
        val stringType = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String")))
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            receiver to SsaValueDefinition.Root(
                receiver,
                JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("example/Owner"))),
                ValueOrigin.This("example/Owner"),
            ),
            argument to SsaValueDefinition.Root(
                argument,
                JvmValueType.of(JvmComputationalType.INT),
                ValueOrigin.Parameter(0),
            ),
            sum to SsaValueDefinition.Instruction(sum, JvmValueType.of(JvmComputationalType.INT), 0),
            field to SsaValueDefinition.Instruction(field, stringType, 1),
            call to SsaValueDefinition.Instruction(call, JvmValueType.of(JvmComputationalType.INT), 2),
        )
        val operations = listOf(
            ValueOperation(
                0,
                RawOperatorInstruction(JvmOpcode("iadd"), JvmComputationalType.INT),
                listOf(argument, argument),
                sum,
            ),
            ValueOperation(
                1,
                RawFieldInstruction(
                    JvmOpcode("getfield"),
                    "example/Owner",
                    "name",
                    "Ljava/lang/String;",
                    JvmType.ObjectType("java/lang/String"),
                ),
                listOf(receiver),
                field,
            ),
            ValueOperation(
                2,
                RawInvokeInstruction(
                    JvmOpcode("invokevirtual"),
                    "java/lang/String",
                    "length",
                    "()I",
                    JvmMethodDescriptor(emptyList(), JvmType.IntType),
                    isInterface = false,
                ),
                listOf(field),
                call,
            ),
            ValueOperation(3, RawReturnInstruction(JvmOpcode("ireturn"), JvmComputationalType.INT), listOf(call)),
        )

        val result = builder.build(ssa(values, operations))

        val binary = assertIs<ExpressionNode.Binary>(result.value(sum).node)
        assertEquals(BinaryOperator.ADD, binary.operator)
        assertEquals(argument, binary.left)
        assertEquals(argument, binary.right)

        val fieldRead = assertIs<ExpressionNode.FieldRead>(result.value(field).node)
        assertEquals(receiver, fieldRead.receiver)
        assertEquals("name", fieldRead.field.name)
        assertEquals("Ljava/lang/String;", fieldRead.field.descriptor)

        val invocation = assertIs<ExpressionNode.Call>(result.value(call).node)
        assertEquals(field, invocation.receiver)
        assertEquals("length", invocation.method.name)
        assertEquals(InvocationKind.VIRTUAL, invocation.method.invocationKind)

        val returned = assertIs<ExpressionStatement.Return>(result.statements.single())
        assertEquals(call, returned.value)
    }

    @Test
    fun `folds new and matching constructor into one object construction expression`() {
        val objectValue = ValueId(0)
        val argument = ValueId(1)
        val objectType = JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("example/Thing")))
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            objectValue to SsaValueDefinition.Instruction(objectValue, objectType, 0),
            argument to SsaValueDefinition.Root(
                argument,
                JvmValueType.of(JvmComputationalType.INT),
                ValueOrigin.Parameter(0),
            ),
        )
        val operations = listOf(
            ValueOperation(
                0,
                RawNewObjectInstruction(JvmOpcode("new"), "example/Thing"),
                emptyList(),
                objectValue,
            ),
            ValueOperation(
                1,
                RawInvokeInstruction(
                    JvmOpcode("invokespecial"),
                    "example/Thing",
                    "<init>",
                    "(I)V",
                    JvmMethodDescriptor(listOf(JvmType.IntType), JvmType.VoidType),
                    isInterface = false,
                ),
                listOf(objectValue, argument),
            ),
        )

        val result = builder.build(ssa(values, operations))

        val constructed = assertIs<ExpressionNode.ConstructObject>(result.value(objectValue).node)
        assertEquals("example/Thing", constructed.internalName)
        assertEquals(listOf(argument), constructed.arguments)
        assertEquals(listOf(0, 1), result.value(objectValue).instructionIndices)
        assertEquals(emptyList(), result.statements)
    }

    @Test
    fun `keeps non-new constructor invocation as a statement`() {
        val receiver = ValueId(0)
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            receiver to SsaValueDefinition.Root(
                receiver,
                JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("example/Child"))),
                ValueOrigin.This("example/Child"),
            ),
        )
        val operation = ValueOperation(
            0,
            RawInvokeInstruction(
                JvmOpcode("invokespecial"),
                "example/Parent",
                "<init>",
                "()V",
                JvmMethodDescriptor(emptyList(), JvmType.VoidType),
                isInterface = false,
            ),
            listOf(receiver),
        )

        val result = builder.build(ssa(values, listOf(operation)))

        val call = assertIs<ExpressionStatement.Call>(result.statements.single())
        assertEquals(receiver, call.receiver)
        assertEquals("example/Parent", call.method.ownerInternalName)
        assertEquals("<init>", call.method.name)
        assertNull(result.values[ValueId(99)])
    }

    @Test
    fun `materializes pure single-use values into their consumer`() {
        val left = ValueId(0)
        val right = ValueId(1)
        val sum = ValueId(2)
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            left to SsaValueDefinition.Root(left, FrameValueKind.INT, ValueOrigin.Parameter(0)),
            right to SsaValueDefinition.Root(right, FrameValueKind.INT, ValueOrigin.Parameter(1)),
            sum to SsaValueDefinition.Instruction(sum, FrameValueKind.INT, 0),
        )
        val operations = listOf(
            ValueOperation(0, RawOperatorInstruction(JvmOpcode("iadd"), JvmComputationalType.INT), listOf(left, right), sum),
            ValueOperation(1, RawReturnInstruction(JvmOpcode("ireturn"), JvmComputationalType.INT), listOf(sum)),
        )
        val uses = mapOf<ValueId, List<SsaValueUse>>(
            left to listOf(SsaValueUse.Operation(0, 0)),
            right to listOf(SsaValueUse.Operation(0, 1)),
            sum to listOf(SsaValueUse.Operation(1, 0)),
        )

        val result = builder.build(ssa(values, operations, uses = uses))

        assertEquals(setOf(sum), result.materialization.inlineValues)
    }

    @Test
    fun `renders unused value-returning calls as statements`() {
        val receiver = ValueId(0)
        val resultValue = ValueId(1)
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            receiver to SsaValueDefinition.Root(
                receiver,
                JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("example/Owner"))),
                ValueOrigin.This("example/Owner"),
            ),
            resultValue to SsaValueDefinition.Instruction(resultValue, FrameValueKind.INT, 0),
        )
        val operations = listOf(
            ValueOperation(
                0,
                RawInvokeInstruction(
                    JvmOpcode("invokevirtual"),
                    "example/Owner",
                    "touch",
                    "()I",
                    JvmMethodDescriptor(emptyList(), JvmType.IntType),
                    isInterface = false,
                ),
                listOf(receiver),
                resultValue,
            ),
        )

        val result = builder.build(ssa(values, operations))

        assertEquals(setOf(resultValue), result.materialization.discardedResultValues)
    }

    @Test
    fun `recognizes zero-one phi used only as a boolean condition`() {
        val zero = ValueId(0)
        val one = ValueId(1)
        val phi = ValueId(2)
        val b0 = io.github.relvl.deobscura.cfg.BasicBlockId(0)
        val b1 = io.github.relvl.deobscura.cfg.BasicBlockId(1)
        val b2 = io.github.relvl.deobscura.cfg.BasicBlockId(2)
        val phiInputs = listOf(SsaPhiInput(zero, b0), SsaPhiInput(one, b1))
        val values = linkedMapOf<ValueId, SsaValueDefinition>(
            zero to SsaValueDefinition.Instruction(zero, FrameValueKind.INT, 0),
            one to SsaValueDefinition.Instruction(one, FrameValueKind.INT, 1),
            phi to SsaValueDefinition.Phi(phi, FrameValueKind.INT, b2, SsaPhiLocation.Stack(0), phiInputs),
        )
        val operations = listOf(
            ValueOperation(0, RawConstantInstruction(JvmOpcode("iconst_0"), JvmComputationalType.INT, constantDesc(0)), emptyList(), zero),
            ValueOperation(1, RawConstantInstruction(JvmOpcode("iconst_1"), JvmComputationalType.INT, constantDesc(1)), emptyList(), one),
            ValueOperation(2, RawBranchInstruction(JvmOpcode("ifeq"), RawLabelId(0)), listOf(phi)),
        )
        val uses = mapOf<ValueId, List<SsaValueUse>>(
            zero to listOf(SsaValueUse.Phi(phi, b0, 0)),
            one to listOf(SsaValueUse.Phi(phi, b1, 1)),
            phi to listOf(SsaValueUse.Operation(2, 0)),
        )
        val analysis = SsaAnalysis(
            values = values,
            operations = operations,
            phiNodes = listOf(SsaPhiNode(phi, b2, SsaPhiLocation.Stack(0), phiInputs)),
            uses = uses,
            eliminatedLocalInstructionCount = 0,
        )

        val result = builder.build(analysis)

        assertEquals(setOf(phi), result.materialization.booleanValues)
        assertTrue(zero in result.materialization.inlineValues)
        assertTrue(one in result.materialization.inlineValues)
    }

    private fun ssa(
        values: Map<ValueId, SsaValueDefinition>,
        operations: List<ValueOperation>,
        uses: Map<ValueId, List<SsaValueUse>> = emptyMap(),
    ) = SsaAnalysis(
        values = values,
        operations = operations,
        phiNodes = emptyList(),
        uses = uses,
        eliminatedLocalInstructionCount = 0,
    )

    private fun constantDesc(value: Any): java.lang.constant.ConstantDesc = value as java.lang.constant.ConstantDesc
}
