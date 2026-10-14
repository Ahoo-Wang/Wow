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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** The V10 annotation set (design 01 §3, decisions D67 and D68) as the model processor reads it. */
@OptIn(ExperimentalCompilerApi::class)
class V10AnnotationSetTest {
    private val basket = SourceFile.kotlin(
        "Basket.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Alias
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.Revision
        import me.ahoo.wow.api.annotation.Upgrade
        import tools.jackson.databind.JsonNode

        @CreateAggregate
        @Name("Put")
        @Alias("Add")
        data class PutItem(val item: String)

        @Revision(2)
        @Alias("ItemAdded", "legacy.basket.ItemAdded")
        data class ItemPut(val item: String)

        @Upgrade(event = ItemPut::class, from = 1)
        fun itemPutFrom1(node: JsonNode): JsonNode = node

        @AggregateRoot
        @Name("shopping_basket")
        class Basket(private val state: BasketState) {
            fun onCommand(command: PutItem): ItemPut = ItemPut(command.item)
        }

        class BasketState(val id: String) {
            val items = mutableListOf<String>()
            fun onSourcing(event: ItemPut) { items += event.item }
        }
        """.trimIndent(),
    )

    @Test
    fun `names, revisions and aliases flow into the generated model`() {
        val scenario = SourceFile.kotlin(
            "BasketScenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.kernel.CommandOutcome
            import me.ahoo.wow.kernel.memory.InMemoryEventLog
            import me.ahoo.wow.kernel.Kernel

            fun run(): String = runBlocking {
                val model = basketModel("shop")
                val ref = model.type.ref("tenant", "b-1")
                val outcome = Kernel(InMemoryEventLog()).execute(model, Command("c1", "r1", ref, PutItem("pen")))
                val committed = outcome as CommandOutcome.Committed
                val event = committed.stream.events.single()
                listOf(
                    committed.stream.commandType,
                    event.name + "#" + event.revision,
                    model.eventType("shop.shopping_basket.ItemAdded").name,
                    model.eventType("legacy.basket.ItemAdded").name,
                    model.findCommandType("shop.shopping_basket.Add")?.name,
                    (committed.state as BasketState).items.toString(),
                ).joinToString(" | ")
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", basket, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val kt = result.classLoader.loadClass("sample.BasketScenarioKt")
        kt.getMethod("run").invoke(null).assert().isEqualTo(
            "shop.shopping_basket.Put | shop.shopping_basket.ItemPut#2 | shop.shopping_basket.ItemPut | " +
                "shop.shopping_basket.ItemPut | shop.shopping_basket.Put | [pen]",
        )
        result.messages.assert().doesNotContain("is not bound")
        val generated = compilation.workingDir.walkTopDown().single { it.name == "BasketModel.kt" }.readText()
        generated.assert().contains("revision = 2").contains("alias<sample.ItemPut>(\"\$type.ItemAdded\")")
            .contains("upgrade<sample.ItemPut>(1) { node -> sample.itemPutFrom1(node) }")
            .contains("alias<sample.ItemPut>(\"legacy.basket.ItemAdded\")")
            .contains("alias<sample.PutItem>(\"\$type.Add\")")
    }

    @Test
    fun `a revision belongs to events and starts at 1`() {
        val source = SourceFile.kotlin(
            "Pad.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Revision

            @Revision(2)
            data class Write(val text: String)

            @Revision(0)
            data class Written(val text: String)

            @AggregateRoot
            class Pad(private val state: PadState) {
                fun onCommand(command: Write): Written = Written(command.text)
            }

            class PadState(val id: String) {
                fun onSourcing(event: Written) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("command Write is annotated @Revision: only events have revisions")
            .contains("event Written has @Revision(0): a revision starts at 1")
    }

    private val legacy = SourceFile.kotlin(
        "Legacy.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateName
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.AggregateRoute
        import me.ahoo.wow.api.annotation.CommandRoute
        import me.ahoo.wow.api.annotation.EntityObject
        import me.ahoo.wow.api.annotation.Event
        import me.ahoo.wow.api.annotation.Generated
        import me.ahoo.wow.api.annotation.OnError
        import me.ahoo.wow.api.annotation.OnStateEvent
        import me.ahoo.wow.api.annotation.Order
        import me.ahoo.wow.api.annotation.Retry
        import me.ahoo.wow.api.annotation.Spaced
        import me.ahoo.wow.api.annotation.ValueObject
        import me.ahoo.wow.api.annotation.VoidCommand

        @VoidCommand
        @CommandRoute
        data class Ping(@Generated val note: String)

        @Event
        @ValueObject
        data class Pinged(val note: String)

        @AggregateRoot
        @AggregateRoute
        @Spaced
        class Legacy(private val state: LegacyState) {
            @Order
            fun onCommand(command: Ping): Pinged = Pinged(command.note)

            @OnError
            @Retry
            fun onError(command: Ping, error: Throwable) = Unit

            @OnStateEvent
            fun onStateEvent(event: Any) = Unit
        }

        @EntityObject
        class LegacyState(val id: String) {
            @AggregateName
            val name: String = ""

            fun onSourcing(event: Pinged) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `an annotation V10 removed is a compile error that points to the migration skill`() {
        val (_, result) = compileModel("generate", legacy)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        val skill = "the wow-migrate skill rewrites 9.x code into V10"
        result.messages.assert()
            .contains("aggregate root Legacy is annotated @AggregateRoute, which V10 removed: routes are materialized")
            .contains(
                "aggregate root Legacy is annotated @Spaced, which V10 removed: it is removed with no counterpart"
            )
            .contains("aggregate root Legacy's function onCommand is annotated @Order")
            .contains(
                "aggregate root Legacy's function onError is annotated @OnError, which V10 removed: reject with a " +
                    "Rejection, and compensate in a @Saga; $skill"
            )
            .contains(
                "aggregate root Legacy's function onError is annotated @Retry, which only a subscription processor"
            )
            .contains("aggregate root Legacy's function onStateEvent is annotated @OnStateEvent")
            .contains("state LegacyState is annotated @EntityObject")
            .contains("state LegacyState's field name is annotated @AggregateName")
            .contains("command Ping is annotated @VoidCommand")
            .contains("command Ping is annotated @CommandRoute")
            .contains("command Ping's field note is annotated @Generated")
            .contains("event Pinged is annotated @Event, which V10 removed: use @Revision")
            .contains("event Pinged is annotated @ValueObject")
    }

    @Test
    fun `a command carries no identity in its body`() {
        val kotlinCommands = SourceFile.kotlin(
            "Wallet.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateId
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.AggregateVersion
            import me.ahoo.wow.api.annotation.OwnerId
            import me.ahoo.wow.api.annotation.TenantId
            import me.ahoo.wow.api.decide.Command

            data class Open(
                @AggregateId val id: String,
                @TenantId val tenantId: String,
                @OwnerId val ownerId: String,
                @AggregateVersion val version: Int?,
            )
            data class Opened(val id: String)

            @AggregateRoot
            class Wallet(private val state: WalletState) {
                fun onCommand(command: Command<Open>): Opened = Opened(command.ref.id)
                fun onCommand(command: Close): Opened = Opened(command.id)
            }

            class WalletState(val id: String) {
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val javaCommand = SourceFile.java(
            "Close.java",
            """
            package sample;

            import me.ahoo.wow.api.annotation.AggregateId;

            public class Close {
                @AggregateId
                public String id;
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", kotlinCommands, javaCommand)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        val identity = "which V10 removed (decision D68): a command carries no identity: it is Command.ref"
        result.messages.assert()
            .contains("command Open's field id is annotated @AggregateId, $identity")
            .contains("command Open's field tenantId is annotated @TenantId, $identity")
            .contains("command Open's field ownerId is annotated @OwnerId, $identity")
            .contains("command Open's field version is annotated @AggregateVersion, $identity")
            .contains("command Close's field id is annotated @AggregateId, $identity")
    }

    private val processors = SourceFile.kotlin(
        "Processors.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.ProjectContext
        import me.ahoo.wow.api.subscribe.ReactContext
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Mono

        class Summary(val id: String)
        data class Notify(val to: String)

        @Projection
        @Name("summary")
        class SummaryProjection(private val rows: MutableMap<String, Int>) {
            @OnEvent
            fun onEvent(event: StateEvent<Summary>) { rows[event.state.id] = event.version }

            @OnEvent
            suspend fun onAny(event: StateEvent<*>, context: ProjectContext) = Unit
        }

        @Reactor
        class Notifier {
            @OnEvent
            fun onEvent(event: StateEvent<Summary>, context: ReactContext): Mono<Void> = Mono.empty()
        }

        @Saga
        class Notifying {
            fun onEvent(event: StateEvent<Summary>, context: SagaContext): List<Command<*>> =
                listOf(context.command(AggregateType("mail", "box").ref(event.ref.tenantId, "m"), Notify(event.state.id)))

            @OnEvent
            fun once(event: StateEvent<Summary>): Command<Notify>? = null
        }
        """.trimIndent(),
    )

    @Test
    fun `projections, reactors and sagas are checked and reported`() {
        val (compilation, result) = compileModel("report", processors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val report = compilation.workingDir.walkTopDown().single { it.name == "model-report.json" }.readText()
        report.assert()
            .contains("\"processor\" : \"sample.SummaryProjection\"")
            .contains("\"kind\" : \"PROJECT\"")
            .contains("\"summary.onEvent\"")
            .contains("\"summary.onAny\"")
            .contains("\"kind\" : \"REACT\"")
            .contains("\"Notifier.onEvent\"")
            .contains("\"kind\" : \"SAGA\"")
            .contains("\"Notifying.onEvent\"")
            .contains("\"Notifying.once\"")
            .doesNotContain("\"problems\" : [ \"")
    }

    @Test
    fun `well-formed processors compile with their bindings`() {
        val (compilation, result) = compileModel("generate", processors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val generated = compilation.workingDir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        generated.assert().contains(
            "SummaryProjectionSubscriptions.kt",
            "NotifierSubscriptions.kt",
            "NotifyingSubscriptions.kt",
        )
        result.classLoader.loadClass("sample.SummaryProjectionSubscriptionsKt")
            .getMethod("summaryProjectionHandlers", me.ahoo.wow.kernel.Dependencies::class.java)
            .assert().isNotNull()
    }

    private val broken = SourceFile.kotlin(
        "Broken.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Retry
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.annotation.StatelessSaga
        import me.ahoo.wow.api.subscribe.ReactContext
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent

        class S(val id: String)

        @Projection
        @Reactor
        class Both {
            @OnEvent
            fun onEvent(event: StateEvent<S>) = Unit
        }

        class Kindless {
            @OnEvent
            fun onEvent(event: StateEvent<S>) = Unit
        }

        @Saga
        class Empty

        @Reactor
        class Shapes {
            @OnEvent
            fun first(event: S) = Unit

            @OnEvent
            fun context(event: StateEvent<S>, context: SagaContext) = Unit

            @OnEvent
            fun extra(event: StateEvent<S>, context: ReactContext, more: String) = Unit

            @OnEvent
            fun returns(event: StateEvent<S>): String = ""

            @OnEvent
            private fun hidden(event: StateEvent<S>) = Unit

            @OnEvent
            fun twice(event: StateEvent<S>) = Unit

            @OnEvent
            fun twice(event: StateEvent<S>, context: ReactContext) = Unit

            @OnEvent
            @Retry(timeout = "soon")
            fun retried(event: StateEvent<S>) = Unit
        }

        @Saga
        class Deriving {
            @OnEvent
            fun onEvent(event: StateEvent<S>): String = ""
        }

        @StatelessSaga
        class Legacy {
            @OnEvent
            fun onEvent(event: StateEvent<S>) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `a processor's shape is checked`() {
        val (_, result) = compileModel("generate", broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("processor Both is @Projection and @Reactor: a processor is of exactly one kind")
            .contains("processor Kindless is none of @Projection, @Reactor, @Saga")
            .contains("processor Empty declares no @OnEvent function")
            .contains("handler Shapes.first must take a me.ahoo.wow.api.subscribe.StateEvent first, not sample.S")
            .contains(
                "handler Shapes.context takes me.ahoo.wow.api.subscribe.SagaContext: a @Reactor handler takes a " +
                    "me.ahoo.wow.api.subscribe.ReactContext"
            )
            .contains("handler Shapes.extra takes 3 parameters")
            .contains("handler Shapes.returns returns kotlin.String: a @Reactor handler returns nothing")
            .contains("handler Shapes.hidden is private")
            .contains("processor Shapes has two handlers named twice")
            .contains("handler Shapes.retried is annotated @Retry(timeout = \"soon\"): it is a duration")
            .contains("handler Deriving.onEvent returns kotlin.String: a @Saga handler returns the commands it derives")
            .contains("processor Legacy is annotated @StatelessSaga, which V10 removed: use @Saga")
    }
}
