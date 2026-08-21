package io.github.relvl.deobscura.source

import io.github.relvl.deobscura.deobfuscation.DeobfuscationPlan

internal fun DeobfuscationPlan.sourceClassName(internalName: String): String = classInternalName(internalName).replace('/', '.')
