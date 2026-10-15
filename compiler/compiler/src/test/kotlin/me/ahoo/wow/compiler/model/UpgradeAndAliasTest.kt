/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** `@Upgrade` chains (decision D82) and the `@Alias` values (decisions D22, D55) as the model processor reads them. */
@OptIn(ExperimentalCompilerApi::class)
class UpgradeAndAliasTest {
    /** A ledger whose `Entered` event is at [revision], with the further declarations [extra]. */
    private fun ledger(revision: Int, extra: String, alias: String = ""): SourceFile = SourceFile.kotlin(
        "Ledger.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Alias
        import me.ahoo.wow.api.annotation.Revision
        import me.ahoo.wow.api.annotation.Upgrade
        import tools.jackson.databind.JsonNode
        import tools.jackson.databind.node.ObjectNode

        $alias
        data class Enter(val amount: Long)

        @Revision($revision)
        data class Entered(val amount: Long, val currency: String, val source: String)

        data class Noted(val text: String)

        @AggregateRoot
        class Ledger(private val state: LedgerState) {
            fun onCommand(command: Enter): Entered = Entered(command.amount, "CNY", "app")
        }

        class LedgerState(val id: String) {
            var total = 0L
            fun onSourcing(event: Entered) { total += event.amount }
        }

        $extra
        """.trimIndent(),
    )

    private fun failed(result: JvmCompilationResult): String {
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `upgrades are chained into the generated model`() {
        val source = ledger(
            revision = 4,
            extra = """
            @Upgrade(event = Entered::class, from = 1)
            fun addCurrency(node: ObjectNode): ObjectNode = node.put("currency", "CNY")

            object LedgerUpgrades {
                @Upgrade(event = Entered::class, from = 2)
                fun addSource(node: JsonNode): JsonNode = (node as ObjectNode).put("source", "unknown")
            }

            class Legacy {
                companion object {
                    @Upgrade(event = Entered::class, from = 3)
                    internal fun doubleAmount(node: ObjectNode): JsonNode =
                        node.put("amount", node.get("amount").asLong() * 2)
                }
            }
            """,
        )
        val scenario = SourceFile.kotlin(
            "LedgerScenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import me.ahoo.wow.kernel.EventCodec
            import me.ahoo.wow.spi.log.EventRecord

            fun read(): Any = EventCodec().decode(ledgerModel("bank"), EventRecord("bank.ledger.Entered", 1, "{\"amount\":5}"))
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", source, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().doesNotContain("has no @Upgrade")
        result.classLoader.loadClass("sample.LedgerScenarioKt").getMethod("read").invoke(null).toString()
            .assert().isEqualTo("Entered(amount=10, currency=CNY, source=unknown)")
        val generated = compilation.workingDir.walkTopDown().single { it.name == "LedgerModel.kt" }.readText()
        generated.assert()
            .contains(
                "upgrade<sample.Entered>(1) { node -> sample.addCurrency(node as tools.jackson.databind.node.ObjectNode) }"
            )
            .contains("upgrade<sample.Entered>(2) { node -> sample.LedgerUpgrades.addSource(node) }")
            .contains("upgrade<sample.Entered>(3) { node -> sample.Legacy.Companion.doubleAmount(")
    }

    @Test
    fun `a chain with a gap, a duplicate or a revision out of range is a compile error`() {
        val source = ledger(
            revision = 4,
            extra = """
            @Upgrade(event = Entered::class, from = 1)
            fun first(node: ObjectNode): ObjectNode = node

            @Upgrade(event = Entered::class, from = 1)
            fun again(node: ObjectNode): ObjectNode = node

            @Upgrade(event = Entered::class, from = 0)
            fun zero(node: ObjectNode): ObjectNode = node

            @Upgrade(event = Entered::class, from = 4)
            fun current(node: ObjectNode): ObjectNode = node
            """,
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert()
            .contains("two upgrades of Entered from revision 1: sample.first, sample.again")
            .contains("upgrade sample.zero of Entered from revision 0: Entered is at revision 4")
            .contains(
                "upgrade sample.current of Entered from revision 4: Entered is at revision 4, so its upgrades go from revision 1 to 3"
            )
            .contains("event Entered is at revision 4 but has no upgrade from revision 2, 3")
    }

    @Test
    fun `an event above revision 1 with no upgrade at all is a compile error`() {
        val messages = failed(compileModel("generate", ledger(revision = 3, extra = "")).second)
        messages.assert().contains(
            "event Entered is at revision 3 but has no @Upgrade: declare one from each of the revisions 1, 2, as " +
                "@Upgrade(event = Entered::class, from = 1) on a function from JsonNode to JsonNode",
        )
    }

    @Test
    fun `report mode should list a missing chain as a problem`() {
        val (compilation, result) = compileModel("report", ledger(revision = 2, extra = ""))
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "model-report.json" }.readText()
            .assert().contains("event Entered is at revision 2 but has no @Upgrade")
    }

    @Test
    fun `an upgrade of an event at revision 1 is out of range`() {
        val source = ledger(
            revision = 1,
            extra = """
            @Upgrade(event = Entered::class, from = 1)
            fun early(node: ObjectNode): ObjectNode = node
            """,
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert().contains("upgrade sample.early of Entered from revision 1: Entered is at revision 1")
    }

    @Test
    fun `an upgrade's signature and place are checked`() {
        val source = ledger(
            revision = 6,
            extra = """
            @Upgrade(event = Entered::class, from = 1)
            fun text(node: String): String = node

            @Upgrade(event = Entered::class, from = 2)
            fun nullable(node: ObjectNode): ObjectNode? = node

            @Upgrade(event = Entered::class, from = 3)
            suspend fun waits(node: ObjectNode): ObjectNode = node

            @Upgrade(event = Entered::class, from = 4)
            private fun hidden(node: ObjectNode): ObjectNode = node

            class Holder {
                @Upgrade(event = Entered::class, from = 5)
                fun member(node: ObjectNode): ObjectNode = node
            }

            @Upgrade(event = Entered::class, from = 5)
            fun ObjectNode.extension(): ObjectNode = this
            """,
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert()
            .contains("upgrade sample.text must take one JsonNode or ObjectNode (tools.jackson.databind)")
            .contains("upgrade sample.nullable must take one JsonNode or ObjectNode")
            .contains("upgrade sample.waits suspends: an upgrade does no I/O")
            .contains("upgrade sample.hidden is private: the generated model calls it")
            .contains("upgrade sample.Holder.member is a member of a class")
            .contains("upgrade sample.extension must take one JsonNode or ObjectNode")
    }

    @Test
    fun `an upgrade of a class that is not an event of the module is a compile error`() {
        val source = ledger(
            revision = 1,
            extra = """
            @Upgrade(event = Noted::class, from = 1)
            fun stray(node: ObjectNode): ObjectNode = node
            """,
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert()
            .contains(
                "upgrade sample.stray upgrades sample.Noted, which is not an event of an aggregate of this module"
            )
    }

    @Test
    fun `a command alias is bound into the model`() {
        val scenario = SourceFile.kotlin(
            "LedgerScenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.kernel.CommandOutcome
            import me.ahoo.wow.kernel.memory.InMemoryEventLog
            import me.ahoo.wow.kernel.Kernel
            import me.ahoo.wow.kernel.aggregate

            // The ledger before Enter was renamed: its command was called Book.
            data class Book(val amount: Long)

            val before = aggregate<LedgerState>("bank", "ledger") {
                initial { LedgerState(it.id) }
                decide<Enter>(me.ahoo.wow.kernel.Creation.ALLOWED, name = "bank.ledger.Book") { _, command ->
                    me.ahoo.wow.api.decide.Decision.events(Entered(command.amount, "CNY", "app"))
                }
                evolve<Entered> { total += it.amount }
            }

            fun retry(): String = runBlocking {
                val log = InMemoryEventLog()
                val ref = before.type.ref("tenant", "l-1")
                val first = Kernel(log).execute(before, Command("c1", "r1", ref, Enter(5))) as CommandOutcome.Committed
                val model = ledgerModel("bank")
                val retried = Kernel(log).execute(model, Command("c2", "r1", ref, Enter(5)))
                listOf(
                    first.stream.commandType,
                    model.findCommandType("bank.ledger.Book")?.name,
                    (retried as CommandOutcome.AlreadyCommitted).stream.commandType,
                ).joinToString(" | ")
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel(
            "generate",
            ledger(revision = 1, extra = "", alias = "@Alias(\"Book\")"),
            scenario,
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.LedgerScenarioKt").getMethod("retry").invoke(null)
            .assert().isEqualTo("bank.ledger.Book | bank.ledger.Enter | bank.ledger.Book")
    }

    @Test
    fun `an alias with one dot, a blank one, a repeated one or the current name is a compile error`() {
        val source = ledger(
            revision = 1,
            alias = "@Alias(\"ledger.Book\", \" \", \"Enter\", \"Old\", \"Old\")",
            extra = "",
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert()
            .contains("command Enter's @Alias(\"ledger.Book\") has exactly one dot")
            .contains("command Enter's @Alias has a blank value")
            .contains("command Enter's @Alias(\"Enter\") is the type's current name")
            .contains("command Enter's @Alias lists \"Old\" twice")
    }

    @Test
    fun `an alias equal to the full current name is a compile error when the context is known`() {
        val source = ledger(revision = 1, alias = "@Alias(\"bank.ledger.Enter\")", extra = "")
        val (_, result) = compileModel("generate", mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "bank"), source)
        failed(result).assert().contains("command Enter's @Alias(\"bank.ledger.Enter\") is the type's current name")
    }

    @Test
    fun `a former name two types of the module claim is a compile error`() {
        val source = ledger(
            revision = 1,
            alias = "@Alias(\"bank.journal.Posted\")",
            extra = """
            data class Exit(val amount: Long)

            @AggregateRoot
            class Journal(private val state: JournalState) {
                fun onCommand(command: Exit): Noted = Noted("exit")
                fun onCommand(command: Post): Noted = Noted("post")
            }

            @Alias("bank.journal.Posted")
            data class Post(val amount: Long)

            class JournalState(val id: String) {
                fun onSourcing(event: Noted) = Unit
            }
            """,
        )
        val (_, result) = compileModel("generate", mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "bank"), source)
        failed(result).assert()
            .contains(
                "command Enter's former name \"bank.journal.Posted\" is also a former name of command Post of sample.Journal"
            )
            .contains(
                "command Post's former name \"bank.journal.Posted\" is also a former name of command Enter of sample.Ledger"
            )
    }

    @Test
    fun `a former name equal to another command's name is a compile error`() {
        val source = SourceFile.kotlin(
            "Till.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Alias

            @Alias("Close")
            data class Open(val note: String)
            data class Close(val note: String)
            data class Changed(val note: String)

            @AggregateRoot
            class Till(val id: String) {
                fun onCommand(command: Open): Changed = Changed(command.note)
                fun onCommand(command: Close): Changed = Changed(command.note)
                fun onSourcing(event: Changed) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        failed(result).assert()
            .contains("command Open's former name \"Close\" is also the name of command Close of sample.Till")
    }
}
