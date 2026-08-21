package io.github.relvl.deobscura.util

import java.util.*

internal fun formatElapsedSeconds(nanos: Long): String = String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0)
