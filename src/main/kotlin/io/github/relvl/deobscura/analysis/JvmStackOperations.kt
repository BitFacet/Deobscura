package io.github.relvl.deobscura.analysis

internal object JvmStackOperations {
    fun <T> execute(
        mnemonic: String,
        pop: () -> T,
        push: (T) -> Unit,
        category: (T) -> Int,
        invalidCategory: (mnemonic: String, expected: Int, value: T) -> Nothing,
        unsupported: (mnemonic: String) -> Nothing,
    ) {
        fun requireCategory(value: T, expected: Int): T {
            val actual = category(value)
            if (actual != expected) invalidCategory(mnemonic, expected, value)
            return value
        }

        when (mnemonic) {
            "pop" -> requireCategory(pop(), 1)
            "pop2" -> {
                val first = pop()
                if (category(first) == 1) requireCategory(pop(), 1)
            }

            "dup" -> {
                val a = requireCategory(pop(), 1)
                push(a); push(a)
            }

            "dup_x1" -> {
                val a = requireCategory(pop(), 1)
                val b = requireCategory(pop(), 1)
                push(a); push(b); push(a)
            }

            "dup_x2" -> {
                val a = requireCategory(pop(), 1)
                val b = pop()
                if (category(b) == 2) {
                    push(a); push(b); push(a)
                } else {
                    val c = requireCategory(pop(), 1)
                    push(a); push(c); push(b); push(a)
                }
            }

            "dup2" -> {
                val a = pop()
                if (category(a) == 2) {
                    push(a); push(a)
                } else {
                    val b = requireCategory(pop(), 1)
                    push(b); push(a); push(b); push(a)
                }
            }

            "dup2_x1" -> {
                val a = pop()
                if (category(a) == 2) {
                    val b = requireCategory(pop(), 1)
                    push(a); push(b); push(a)
                } else {
                    val b = requireCategory(pop(), 1)
                    val c = requireCategory(pop(), 1)
                    push(b); push(a); push(c); push(b); push(a)
                }
            }

            "dup2_x2" -> executeDup2X2(pop, push, category, ::requireCategory)
            "swap" -> {
                val a = requireCategory(pop(), 1)
                val b = requireCategory(pop(), 1)
                push(a); push(b)
            }

            else -> unsupported(mnemonic)
        }
    }

    private fun <T> executeDup2X2(
        pop: () -> T,
        push: (T) -> Unit,
        category: (T) -> Int,
        requireCategory: (T, Int) -> T,
    ) {
        val a = pop()
        if (category(a) == 2) {
            val b = pop()
            if (category(b) == 2) {
                push(a); push(b); push(a)
            } else {
                val c = requireCategory(pop(), 1)
                push(a); push(c); push(b); push(a)
            }
        } else {
            val b = requireCategory(pop(), 1)
            val c = pop()
            if (category(c) == 2) {
                push(b); push(a); push(c); push(b); push(a)
            } else {
                val d = requireCategory(pop(), 1)
                push(b); push(a); push(d); push(c); push(b); push(a)
            }
        }
    }
}
