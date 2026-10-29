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

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Decision D336: a command changes its aggregate's lifecycle by producing the lifecycle event beside its own events;
 * only an `onDeleted` command restores, and the compiler refuses what a deleted or tenant-owned aggregate cannot do.
 */
@OptIn(ExperimentalCompilerApi::class)
class LifecycleCompositionTest {
    private fun ticket(root: String, aggregateRoot: String = "@AggregateRoot"): SourceFile = SourceFile.kotlin(
        "Ticket.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.aggregate.Ownership
        import me.ahoo.wow.api.decide.Decision
        import me.ahoo.wow.api.lifecycle.AggregateDeleted
        import me.ahoo.wow.api.lifecycle.AggregateRestored
        import me.ahoo.wow.api.lifecycle.DeleteAggregate
        import me.ahoo.wow.api.lifecycle.OwnerTransferred

        @CreateAggregate
        data class Open(val id: String)
        data class Close(val reason: String)
        data class Reopen(val reason: String)
        data class Touch(val by: String)
        data class Opened(val id: String)
        data class Closed(val reason: String)
        data class Reopened(val reason: String)
        data class Touched(val by: String)

        $aggregateRoot
        class Ticket(private val state: TicketState) {
            fun onCommand(command: Open): Opened = Opened(command.id)
            $root
        }

        class TicketState {
            var log = mutableListOf<String>()
            fun onSourcing(event: Opened) { log.add("opened") }
            fun onSourcing(event: Closed) { log.add("closed") }
            fun onSourcing(event: Reopened) { log.add("reopened") }
            fun onSourcing(event: Touched) { log.add("touched") }
        }
        """.trimIndent(),
    )

    private fun failing(root: String, aggregateRoot: String = "@AggregateRoot"): String {
        val (_, result) = compileModel("generate", ticket(root, aggregateRoot))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `a command composes the lifecycle event, and an onDeleted command restores the aggregate`() {
        val scenario = SourceFile.kotlin(
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
                val model = ticketModel("desk")
                val kernel = Kernel(InMemoryEventLog())
                val ref = model.type.ref("tenant", "ticket-1")
                listOf(Open("ticket-1"), Close("duplicate"), Touch("bob"), Reopen("not a duplicate"), Touch("bob"))
                    .mapIndexed { index, body ->
                        when (val outcome = kernel.execute(model, Command("c" + index, "r" + index, ref, body))) {
                            is CommandOutcome.Committed -> "deleted=" + outcome.deleted
                            is CommandOutcome.Rejected -> outcome.rejection.code
                            else -> outcome.toString()
                        }
                    }.joinToString()
            }
            """.trimIndent(),
        )
        val source = ticket(
            """
            @OnCommand(produces = [Closed::class, AggregateDeleted::class])
            fun onCommand(command: Close): Decision = Decision.events(Closed(command.reason), AggregateDeleted)
            @OnCommand(onDeleted = true, produces = [Reopened::class, AggregateRestored::class])
            fun onCommand(command: Reopen): Decision = Decision.events(Reopened(command.reason), AggregateRestored)
            fun onCommand(command: Touch): Touched = Touched(command.by)
            """.trimIndent(),
            // The lifecycle commands are disabled: a command still composes their events.
            "@AggregateRoot(lifecycle = [])",
        )
        val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "desk")
        val (compilation, result) = compileModel("generate", options, source, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null).assert()
            .isEqualTo("deleted=false, deleted=true, wow.aggregate.deleted, deleted=false, deleted=false")
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val aggregate = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().aggregates.single()
        aggregate.commands.single { it.name == "Close" }.produces.assert()
            .isEqualTo(listOf("Closed", "wow.lifecycle.AggregateDeleted"))
        aggregate.commands.single { it.name == "Reopen" }.produces.assert()
            .isEqualTo(listOf("Reopened", "wow.lifecycle.AggregateRestored"))
    }

    @Test
    fun `only an onDeleted command restores, with its own events alone`() {
        failing(
            """
            @OnCommand(produces = [Reopened::class, AggregateRestored::class])
            fun onCommand(command: Reopen): Decision = Decision.events(Reopened(command.reason), AggregateRestored)
            @OnCommand(onDeleted = true, produces = [Touched::class, OwnerTransferred::class])
            fun onCommand(command: Touch): Decision = Decision.NoOp
            """.trimIndent(),
        ).assert().contains(
            "onCommand(sample.Reopen) produces AggregateRestored, but is not decided on a deleted aggregate: mark " +
                "it @OnCommand(onDeleted = true)",
            "onCommand(sample.Touch) is decided on a deleted aggregate (onDeleted) and produces OwnerTransferred: " +
                "such a command produces AggregateRestored and the aggregate's own events alone",
        )
    }

    @Test
    fun `an aggregate of its tenant produces no OwnerTransferred`() {
        failing(
            """
            @OnCommand(produces = [Touched::class, OwnerTransferred::class])
            fun onCommand(command: Touch): Decision = Decision.NoOp
            """.trimIndent(),
            "@AggregateRoot(ownership = Ownership.TENANT)",
        ).assert().contains(
            "onCommand(sample.Touch) produces OwnerTransferred, but the aggregate belongs to its tenant: it has no owner",
        )
    }

    @Test
    fun `an aggregate whose id is its owner's produces no OwnerTransferred`() {
        failing(
            """
            @OnCommand(produces = [Touched::class, OwnerTransferred::class])
            fun onCommand(command: Touch): Decision = Decision.NoOp
            """.trimIndent(),
            "@AggregateRoot(idSource = me.ahoo.wow.api.aggregate.AggregateIdSource.OWNER)",
        ).assert().contains(
            "onCommand(sample.Touch) produces OwnerTransferred, but the aggregate's id is its owner's: its owner is fixed",
        )
    }

    @Test
    fun `a decide function of a lifecycle command names what to do instead`() {
        failing("fun onCommand(command: DeleteAggregate): Closed = Closed(\"x\")").assert().contains(
            "onCommand takes me.ahoo.wow.api.lifecycle.DeleteAggregate, a command the kernel decides: add events to " +
                "it with @AfterCommand(include = [DeleteAggregate::class]), or decide a command of the aggregate",
        )
    }
}
