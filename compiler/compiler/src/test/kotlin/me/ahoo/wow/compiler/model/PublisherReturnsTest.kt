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

/**
 * D372 S13: a handler that returns a Reactor publisher is checked by what the publisher emits, as one that returns
 * anything else is: a projection's or a reactor's completes with nothing, a saga's emits the commands it derives.
 */
@OptIn(ExperimentalCompilerApi::class)
class PublisherReturnsTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val model = SourceFile.kotlin(
        "Model.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class PlaceOrder(val id: String)
        data class OrderPlaced(val id: String)

        @AggregateRoot
        class Order(val id: String) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.id)
            fun onSourcing(event: OrderPlaced) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `publishers of what a handler returns should compile`() {
        val handlers = SourceFile.kotlin(
            "Handlers.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent
            import reactor.core.publisher.Flux
            import reactor.core.publisher.Mono

            @Projection
            class Board {
                @OnEvent
                fun onVoid(event: StateEvent<Order>): Mono<Void> = Mono.empty()

                @OnEvent
                fun onUnit(event: StateEvent<Order>): Mono<Unit> = Mono.empty()
            }

            @Saga
            class Placing {
                @OnEvent
                fun one(event: StateEvent<Order>): Mono<CommandRequest<PlaceOrder>> = Mono.empty()

                @OnEvent
                fun many(event: StateEvent<Order>): Flux<CommandRequest<PlaceOrder>> = Flux.empty()

                @OnRejected
                fun onRejected(rejected: CommandReceipt.Rejected<PlaceOrder>): Mono<CommandRequest<PlaceOrder>> =
                    Mono.empty()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, handlers)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Suppress("LongMethod") // The broken sources, whole.
    @Test
    fun `a publisher of anything else should fail the compilation, for every handler kind`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnCommitted
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.schedule.ScheduleTick
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent
            import org.reactivestreams.Publisher
            import reactor.core.publisher.Flux
            import reactor.core.publisher.Mono

            @Projection
            class Board {
                @OnEvent
                fun text(event: StateEvent<Order>): Mono<String> = Mono.empty()

                @OnEvent
                fun stream(event: StateEvent<Order>): Flux<Void> = Flux.empty()
            }

            @Reactor
            class Mailer {
                @OnEvent
                fun publisher(event: StateEvent<Order>): Publisher<Void> = Mono.empty()
            }

            @Saga
            class Placing {
                @OnEvent
                fun nothing(event: StateEvent<Order>): Mono<Void> = Mono.empty()

                @OnEvent
                fun texts(event: StateEvent<Order>): Flux<String> = Flux.empty()

                @OnCommitted
                fun onCommitted(committed: CommandReceipt.Committed<PlaceOrder>): Mono<Unit> = Mono.empty()

                @OnSchedule(name = "sweep", cron = "0 0 3 * * *")
                fun sweep(tick: ScheduleTick): Publisher<CommandRequest<PlaceOrder>> = Flux.empty()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        val view = "that returns a publisher returns Mono<Void> or Mono<Unit>, which completes when its handling does"
        val saga = "a @Saga handler that returns a publisher returns a Mono or a Flux of " +
            "me.ahoo.wow.api.client.CommandRequest"
        result.messages.assert()
            .contains(
                "handler Board.text returns reactor.core.publisher.Mono<kotlin.String>: a @Projection handler $view"
            )
            .contains("handler Board.stream returns reactor.core.publisher.Flux<java.lang.Void>: a @Projection handler")
            .contains(
                "handler Mailer.publisher returns org.reactivestreams.Publisher<java.lang.Void>: a @Reactor handler"
            )
            .contains("handler Placing.nothing returns reactor.core.publisher.Mono<java.lang.Void>: $saga")
            .contains("handler Placing.texts returns reactor.core.publisher.Flux<kotlin.String>: $saga")
            .contains("handler Placing.onCommitted returns reactor.core.publisher.Mono<kotlin.Unit>: $saga")
            .contains("handler Placing.sweep returns org.reactivestreams.Publisher<")
    }
}
