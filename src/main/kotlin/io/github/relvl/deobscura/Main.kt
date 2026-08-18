package io.github.relvl.deobscura

import io.github.relvl.deobscura.cli.DeobscuraCommand
import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Thread.currentThread().name = "deobscura"
    exitProcess(CommandLine(DeobscuraCommand()).execute(*args))
}
