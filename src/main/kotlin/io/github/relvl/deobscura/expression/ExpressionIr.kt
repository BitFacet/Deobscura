package io.github.relvl.deobscura.expression

import io.github.relvl.deobscura.analysis.*
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.raw.JvmMethodDescriptor
import io.github.relvl.deobscura.raw.JvmType
import java.lang.constant.ConstantDesc
import java.lang.constant.DirectMethodHandleDesc

/**
 * First source-oriented layer above optimized SSA.
 *
 * Values remain SSA-addressed and control flow remains block-based. The purpose of this layer is to
 * replace JVM opcode-shaped value definitions with typed source-like expressions without yet
 * inventing source variables or structured Java control flow.
 */
data class ExpressionAnalysis(
    val values: Map<ValueId, ExpressionValue>,
    val statements: List<ExpressionStatement>,
    val materialization: ExpressionMaterialization = ExpressionMaterialization(),
) {
    fun value(id: ValueId): ExpressionValue = requireNotNull(values[id]) { "Unknown expression value v${id.value}." }
}

/** Source-materialization decisions that do not change SSA identity or control flow. */
data class ExpressionMaterialization(
    /** Pure single-use instruction values that can be rendered directly inside their consumer. */
    val inlineValues: Set<ValueId> = emptySet(),
    /** Effectful value-producing expressions whose result is unused and should render as statements. */
    val discardedResultValues: Set<ValueId> = emptySet(),
    /** Integer 0/1 phi values used exclusively as boolean branch conditions. */
    val booleanValues: Set<ValueId> = emptySet(),
)

data class ExpressionValue(
    val id: ValueId,
    val type: JvmValueType,
    val node: ExpressionNode,
    /** Physical JVM instruction provenance. Empty for roots and phi values. */
    val instructionIndices: List<Int> = emptyList(),
)

sealed interface ExpressionNode {
    data class Root(val origin: ValueOrigin) : ExpressionNode

    data class Phi(
        val blockId: BasicBlockId,
        val location: SsaPhiLocation,
        val inputs: List<SsaPhiInput>,
    ) : ExpressionNode

    data class Constant(val value: ConstantDesc) : ExpressionNode

    data class Unary(
        val operator: UnaryOperator,
        val operand: ValueId,
    ) : ExpressionNode

    data class Binary(
        val operator: BinaryOperator,
        val left: ValueId,
        val right: ValueId,
    ) : ExpressionNode

    data class Increment(
        val operand: ValueId,
        val amount: Int,
    ) : ExpressionNode

    /** JVM cmp-family result (-1/0/1), including the floating-point NaN bias. */
    data class ThreeWayCompare(
        val left: ValueId,
        val right: ValueId,
        val nanResult: Int? = null,
    ) : ExpressionNode

    data class Conversion(
        val operand: ValueId,
        val targetType: JvmValueType,
    ) : ExpressionNode

    data class FieldRead(
        val field: FieldSymbol,
        val receiver: ValueId?,
    ) : ExpressionNode

    data class ArrayRead(
        val array: ValueId,
        val index: ValueId,
    ) : ExpressionNode

    data class ArrayLength(val array: ValueId) : ExpressionNode

    data class Call(
        val method: MethodSymbol,
        val receiver: ValueId?,
        val arguments: List<ValueId>,
    ) : ExpressionNode

    data class DynamicCall(
        val callSite: DynamicCallSite,
        val arguments: List<ValueId>,
    ) : ExpressionNode

    /** `new` before a matching constructor invocation has been attached. */
    data class NewObject(val internalName: String) : ExpressionNode

    /** A verified `new` + matching `invokespecial <init>` pair. */
    data class ConstructObject(
        val internalName: String,
        val constructor: MethodSymbol,
        val arguments: List<ValueId>,
    ) : ExpressionNode

    data class NewArray(
        val arrayType: JvmType.ArrayType,
        val dimensions: List<ValueId>,
    ) : ExpressionNode

    data class Cast(
        val operand: ValueId,
        val targetType: JvmType,
    ) : ExpressionNode

    data class InstanceOf(
        val operand: ValueId,
        val targetType: JvmType,
    ) : ExpressionNode

    /** Conservative escape hatch for an SSA-producing instruction not source-lifted yet. */
    data class Raw(
        val opcode: String,
        val inputs: List<ValueId>,
    ) : ExpressionNode
}

enum class UnaryOperator(val symbol: String) {
    NEGATE("-"),
}

enum class BinaryOperator(val symbol: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    REMAINDER("%"),
    BIT_AND("&"),
    BIT_OR("|"),
    BIT_XOR("^"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    UNSIGNED_SHIFT_RIGHT(">>>"),
}

data class FieldSymbol(
    val ownerInternalName: String,
    val name: String,
    val descriptor: String,
    val type: JvmType,
)

data class MethodSymbol(
    val ownerInternalName: String,
    val name: String,
    val descriptor: String,
    val type: JvmMethodDescriptor,
    val invocationKind: InvocationKind,
)

enum class InvocationKind {
    STATIC,
    VIRTUAL,
    SPECIAL,
    INTERFACE,
}

data class DynamicCallSite(
    val name: String,
    val descriptor: String,
    val type: JvmMethodDescriptor,
    val bootstrapMethod: DirectMethodHandleDesc,
    val bootstrapArguments: List<ConstantDesc>,
)

sealed interface ExpressionStatement {
    val instructionIndex: Int

    data class FieldWrite(
        override val instructionIndex: Int,
        val field: FieldSymbol,
        val receiver: ValueId?,
        val value: ValueId,
    ) : ExpressionStatement

    data class ArrayWrite(
        override val instructionIndex: Int,
        val array: ValueId,
        val index: ValueId,
        val value: ValueId,
    ) : ExpressionStatement

    data class Call(
        override val instructionIndex: Int,
        val method: MethodSymbol,
        val receiver: ValueId?,
        val arguments: List<ValueId>,
    ) : ExpressionStatement

    data class DynamicCall(
        override val instructionIndex: Int,
        val callSite: DynamicCallSite,
        val arguments: List<ValueId>,
    ) : ExpressionStatement

    data class Return(
        override val instructionIndex: Int,
        val value: ValueId?,
    ) : ExpressionStatement

    data class Throw(
        override val instructionIndex: Int,
        val value: ValueId,
    ) : ExpressionStatement

    data class Monitor(
        override val instructionIndex: Int,
        val operation: MonitorOperation,
        val value: ValueId,
    ) : ExpressionStatement

    data class Branch(
        override val instructionIndex: Int,
        val condition: BranchCondition?,
    ) : ExpressionStatement

    data class Switch(
        override val instructionIndex: Int,
        val selector: ValueId,
    ) : ExpressionStatement

    /** Conservative escape hatch for an effectful/control-flow instruction not source-lifted yet. */
    data class Raw(
        override val instructionIndex: Int,
        val opcode: String,
        val inputs: List<ValueId>,
    ) : ExpressionStatement
}

enum class MonitorOperation { ENTER, EXIT }

data class BranchCondition(
    val operator: ComparisonOperator,
    val left: ValueId,
    val right: BranchOperand,
)

sealed interface BranchOperand {
    data class Value(val value: ValueId) : BranchOperand
    data object Zero : BranchOperand
    data object Null : BranchOperand
}

enum class ComparisonOperator(val symbol: String) {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
}

class ExpressionIrInconsistencyException(message: String) : IllegalStateException(message)
