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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Handlers a root or its state inherits (decision D187, work package P.8): a reusable generic aggregate, such as
 * wow-models' `Tree`, declares its command and sourcing functions in a superclass, often in a library jar, with the
 * command and event types as type parameters the user's subclass binds. The processor binds them with the type
 * arguments resolved, and an override that leaves out the handler's annotation still overrides the handler.
 */
@OptIn(ExperimentalCompilerApi::class)
class InheritedHandlersTest {
    /** The reusable aggregate: generic in its command and event types, with its handlers in the superclasses. */
    private val ledger = SourceFile.kotlin(
        "Ledger.kt",
        """
        package lib

        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.decide.DecideScope
        import me.ahoo.wow.api.decide.Decision

        interface Add<E : Any> {
            val text: String
            fun toEvent(): E
        }

        interface Entry {
            val text: String
        }

        abstract class Ledger<S : LedgerState<*, *>, A : Add<*>, R : Any, RE : Any>(protected val state: S) {
            @OnCommand
            open fun onAdd(command: A, scope: DecideScope): Decision {
                if (command.text.isBlank()) {
                    return Decision.reject("ledger.blank", "blank")
                }
                scope.putResult("size", state.entries.size + 1)
                return Decision.events(command.toEvent()!!)
            }

            // Named by convention, overridden by the subclass without the annotation.
            open fun onCommand(command: R): RE = toRemoved(command)

            abstract fun toRemoved(command: R): RE
        }

        abstract class LedgerState<E : Entry, RE : Any> {
            val entries = mutableListOf<String>()

            @OnSourcing
            open fun onAdded(event: E) {
                entries += event.text
            }

            fun onSourcing(event: RE) {
                entries.removeLastOrNull()
            }
        }
        """.trimIndent(),
    )

    /** The user's module: the subclass binds the type parameters to its own commands and events. */
    private val journal = SourceFile.kotlin(
        "Journal.kt",
        """
        package sample

        import lib.Add
        import lib.Entry
        import lib.Ledger
        import lib.LedgerState
        import me.ahoo.wow.api.annotation.AllowCreate
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "journal")
        object JournalContext

        @AllowCreate
        data class AddEntry(override val text: String) : Add<EntryAdded> {
            override fun toEvent(): EntryAdded = EntryAdded(text)
        }

        data class RemoveEntry(val reason: String)

        data class EntryAdded(override val text: String) : Entry

        data class EntryRemoved(val reason: String)

        class JournalState : LedgerState<EntryAdded, EntryRemoved>()

        @AggregateRoot
        class Journal(state: JournalState) : Ledger<JournalState, AddEntry, RemoveEntry, EntryRemoved>(state) {
            // Overridden without the annotation: still the inherited command function.
            override fun onAdd(command: AddEntry, scope: me.ahoo.wow.api.decide.DecideScope) = super.onAdd(command, scope)

            override fun onCommand(command: RemoveEntry): EntryRemoved = super.onCommand(command)

            override fun toRemoved(command: RemoveEntry): EntryRemoved = EntryRemoved(command.reason)
        }
        """.trimIndent(),
    )

    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        fun run(): String = runBlocking {
            val model = journalModel("journal")
            val kernel = Kernel(InMemoryEventLog(), verifyDecidePurity = true)
            val ref = model.type.ref("tenant", "journal-1")
            var n = 0
            suspend fun send(body: Any): CommandOutcome {
                n++
                return kernel.execute(model, Command("c" + n, "r" + n, ref, body))
            }
            listOf(send(AddEntry("a")), send(AddEntry("b")), send(AddEntry(" ")), send(RemoveEntry("typo")))
                .joinToString(" | ") { outcome ->
                    when (outcome) {
                        is CommandOutcome.Committed -> outcome.stream.events.joinToString(",") { it.name } +
                            outcome.results + "@" + (outcome.state as JournalState).entries
                        is CommandOutcome.Rejected -> "rejected:" + outcome.rejection.code
                        else -> outcome.toString()
                    }
                }
        }
        """.trimIndent(),
    )

    private fun fragment(compilation: KotlinCompilation): Manifest {
        val file = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        return ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))
    }

    private val expectedRun = "journal.journal.EntryAdded{size=1}@[a] | " +
        "journal.journal.EntryAdded{size=2}@[a, b] | " +
        "rejected:ledger.blank | " +
        "journal.journal.EntryRemoved{}@[a]"

    private fun assertBound(compilation: KotlinCompilation, result: JvmCompilationResult) {
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo(expectedRun)
        val aggregate = fragment(compilation).contexts.single().aggregates.single()
        aggregate.commands.associate { it.name to (it.creation.name to it.produces) }.assert().isEqualTo(
            mapOf(
                // A `Decision` does not say what it produces; the resolved return type does.
                "AddEntry" to ("ALLOWED" to null),
                "RemoveEntry" to ("NONE" to listOf("EntryRemoved")),
            ),
        )
        aggregate.events.map { it.name }.assert().containsExactly("EntryAdded", "EntryRemoved")
    }

    @Test
    fun `handlers inherited from a generic superclass of a library jar should be bound with resolved types`() {
        val library = compileLibrary(ledger)
        val (compilation, result) = compileModelAgainst("generate", listOf(library), journal, scenario)
        assertBound(compilation, result)
    }

    @Test
    fun `handlers inherited from a generic superclass of the module should be bound with resolved types`() {
        val (compilation, result) = compileModel("generate", ledger, journal, scenario)
        assertBound(compilation, result)
    }
}
