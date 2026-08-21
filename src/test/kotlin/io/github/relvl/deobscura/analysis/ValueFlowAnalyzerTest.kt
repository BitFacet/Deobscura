package io.github.relvl.deobscura.analysis

import io.github.relvl.deobscura.cfg.ControlFlowGraphBuilder
import io.github.relvl.deobscura.raw.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValueFlowAnalyzerTest {
    private val importer = ClassImporter()
    private val graphBuilder = ControlFlowGraphBuilder()
    private val frameAnalyzer = FrameAnalyzer()
    private val analyzer = ValueFlowAnalyzer()

    @Test
    fun `makes arithmetic operands explicit and removes stack choreography`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "arithmetic" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)

        val add = analysis.operations.single {
            it.instruction is RawOperatorInstruction && it.instruction.opcode.mnemonic == "iadd"
        }
        assertEquals(2, add.inputs.size)
        assertNotNull(add.output)
        assertTrue(add.inputs.all(analysis.values::containsKey))
        assertTrue(analysis.values.containsKey(add.output))
    }

    @Test
    fun `loads reference the current local value instead of creating a new value`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "localRoundTrip" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)
        val localOperations = analysis.operations.filter { it.instruction is RawLocalInstruction }
        val store = localOperations.first { (it.instruction as RawLocalInstruction).operation == LocalOperation.STORE }
        val load = localOperations.last { (it.instruction as RawLocalInstruction).operation == LocalOperation.LOAD }

        assertEquals(store.inputs.single(), load.inputs.single())
        assertEquals(null, store.output)
        assertEquals(null, load.output)
    }

    @Test
    fun `materializes merge values at control flow joins`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "choose" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)

        assertTrue(analysis.mergeValueCount > 0)
        assertTrue(analysis.values.values.filterIsInstance<ValueDefinition.Merge>().all { it.inputs.size > 1 })
    }

    @Test
    fun `stack manipulation does not survive into value operations`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "construct" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)

        assertTrue(analysis.eliminatedStackInstructionCount > 0)
        assertTrue(analysis.operations.none { it.instruction is RawStackInstruction })
    }

    @Test
    fun `preserves precise value types into SSA`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "typed" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)
        val valueFlow = analyzer.analyze(graph, frames)
        val ssa = SsaAnalyzer().analyze(graph, valueFlow)

        val parameters = ssa.values.values.filterIsInstance<SsaValueDefinition.Root>().filter { it.origin is ValueOrigin.Parameter }.associateBy { (it.origin as ValueOrigin.Parameter).index }

        assertEquals(JvmValueType.Computational(JvmComputationalType.BOOLEAN), parameters.getValue(0).type)
        assertEquals(JvmValueType.Computational(JvmComputationalType.BYTE), parameters.getValue(1).type)
        assertEquals(
            JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
            parameters.getValue(2).type,
        )

        val returnOperation = ssa.operations.single { it.instruction is RawReturnInstruction }
        assertEquals(
            JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
            ssa.typeOf(returnOperation.inputs.single()),
        )
    }

    @Test
    fun `keeps intrinsic instruction type when a frame merge widens it`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "byteOrZero" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)
        val getIndex = code.instructions.indexOfFirst {
            it is RawInvokeInstruction && it.owner == "java/nio/ByteBuffer" && it.name == "get" && it.descriptor == "()B"
        }
        val getValue = analysis.values.values.filterIsInstance<ValueDefinition.Instruction>().single { it.instructionIndex == getIndex }

        assertEquals(JvmValueType.Computational(JvmComputationalType.BYTE), getValue.type)
        assertTrue(
            analysis.values.values.filterIsInstance<ValueDefinition.Merge>().any {
                getValue.id in it.inputs && it.type == JvmValueType.Computational(JvmComputationalType.INT)
            },
        )
    }

    @Test
    fun `types instanceof result as boolean`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "isString" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)
        val instanceOfIndex = code.instructions.indexOfFirst {
            it is RawTypeCheckInstruction && it.opcode.mnemonic == "instanceof"
        }
        val value = analysis.values.values.filterIsInstance<ValueDefinition.Instruction>().single { it.instructionIndex == instanceOfIndex }

        assertEquals(JvmValueType.Computational(JvmComputationalType.BOOLEAN), value.type)
    }

    @Test
    fun `preserves intrinsic reference constant types`() {
        val rawClass = importFixture()
        val method = rawClass.methods.single { it.name == "referenceConstants" }
        val code = requireNotNull(method.code)
        val graph = graphBuilder.build(code)
        val frames = frameAnalyzer.analyze(rawClass.internalName, method, graph)

        val analysis = analyzer.analyze(graph, frames)
        val instructionValues = analysis.values.values.filterIsInstance<ValueDefinition.Instruction>().associateBy { it.instructionIndex }
        val stringIndex = code.instructions.indexOfFirst {
            it is RawConstantInstruction && it.opcode.mnemonic == "ldc" && it.value.javaClass == String::class.java
        }
        val nullIndex = code.instructions.indexOfFirst {
            it is RawConstantInstruction && it.opcode.mnemonic == "aconst_null"
        }

        assertEquals(
            JvmValueType.Reference(JvmReferenceType.Exact(JvmType.ObjectType("java/lang/String"))),
            instructionValues.getValue(stringIndex).type,
        )
        assertEquals(
            JvmValueType.Reference(JvmReferenceType.Null),
            instructionValues.getValue(nullIndex).type,
        )
    }

    private fun importFixture(): RawClass {
        val type = ValueFlowFixture::class.java
        val internalName = type.name.replace('.', '/')
        val bytes = requireNotNull(type.getResourceAsStream("/$internalName.class")).use { it.readAllBytes() }
        return importer.importClass(bytes)
    }
}

private class ValueFlowFixture {
    fun arithmetic(a: Int, b: Int): Int = a + b * 2

    fun localRoundTrip(value: Int): Int {
        val copy = value + 1
        return copy
    }

    fun choose(flag: Boolean): Int = if (flag) 10 else 20

    fun construct(): Any = Any()

    fun typed(flag: Boolean, value: Byte, text: String): String? = if (flag && value >= 0) text else null

    fun byteOrZero(buffer: java.nio.ByteBuffer, flag: Boolean): Int = if (flag) buffer.get().toInt() else 0

    fun referenceConstants(flag: Boolean): String? = if (flag) "literal" else null

    fun isString(value: Any): Boolean = value is String
}
