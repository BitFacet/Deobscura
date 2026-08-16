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
}
