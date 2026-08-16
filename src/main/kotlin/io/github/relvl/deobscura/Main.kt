package io.github.relvl.deobscura

import io.github.relvl.deobscura.cli.DeobscuraCommand
import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(CommandLine(DeobscuraCommand()).execute(*args))
}
