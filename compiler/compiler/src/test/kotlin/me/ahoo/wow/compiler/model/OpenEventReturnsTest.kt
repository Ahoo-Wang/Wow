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
 * A command function or an after hook whose return type is an interface or abstract class that is not sealed is a
 * compile error: it returns one of several implementations, which the type does not list, so the compiler cannot
 * tell the events it produces. A sealed type, event classes and a decision with its `produces` stay the ways to say.
 */
@OptIn(ExperimentalCompilerApi::class)
class OpenEventReturnsTest {
    private fun order(root: String): SourceFile = SourceFile.kotlin(
        "Order.kt",
        """
        package sample

        import com.fasterxml.jackson.annotation.JsonSubTypes
        import com.fasterxml.jackson.annotation.JsonTypeInfo
        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.decide.Decision
        import reactor.core.publisher.Mono

        @CreateAggregate
        data class Place(val id: String)
        data class Pay(val amount: Int)
        data class Ship(val to: String)
        data class Charge(val amount: Int)

        interface OrderEvent
        data class Placed(val id: String) : OrderEvent
        data class Paid(val amount: Int) : OrderEvent
        abstract class Change : OrderEvent
        data class Shipped(val to: String) : Change()

        sealed interface Settled
        interface Refund : Settled
        data class Refunded(val amount: Int) : Refund
        data object Closed : Settled

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes(JsonSubTypes.Type(Card::class, name = "card"), JsonSubTypes.Type(Cash::class, name = "cash"))
        interface Charged
        data class Card(val amount: Int) : Charged
        data class Cash(val amount: Int) : Charged

        sealed interface Progress
        data class Packed(val box: Int) : Progress
        data object Dispatched : Progress

        @AggregateRoot
        class Order(private val state: OrderState) {
            $root
        }

        class OrderState {
            fun onSourcing(event: Placed) {}
            fun onSourcing(event: Paid) {}
            fun onSourcing(event: Shipped) {}
            fun onSourcing(event: Refunded) {}
            fun onSourcing(event: Closed) {}
            fun onSourcing(event: Packed) {}
            fun onSourcing(event: Dispatched) {}
            fun onSourcing(event: Card) {}
            fun onSourcing(event: Cash) {}
        }
        """.trimIndent(),
    )

    private fun failing(root: String): String {
        val (_, result) = compileModel("generate", order(root))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `a command function returning an interface or abstract class that is not sealed should be a compile error`() {
        val messages = failing(
            """
            fun onCommand(command: Place): OrderEvent = Placed(command.id)
            fun onCommand(command: Pay): List<OrderEvent> = listOf(Paid(command.amount))
            fun onCommand(command: Ship): Mono<Change> = Mono.just(Shipped(command.to))
            """.trimIndent(),
        )
        messages.assert().contains(
            "onCommand returns sample.OrderEvent, an interface that is not sealed, so the events it produces are not " +
                "listed: make it a sealed interface whose subclasses are the events, return the event classes " +
                "themselves (one, or a List or Flux of them), or return a Decision and list them in " +
                "@OnCommand(produces = [...])",
            "onCommand returns sample.Change, an abstract class that is not sealed",
        )
        // The type is refused alone: no sourcing function is asked of it.
        messages.assert().doesNotContain("produces sample.OrderEvent")
    }

    @Test
    fun `a sealed type with a subtype that is not sealed, and an after hook, should be refused alike`() {
        failing(
            """
            fun onCommand(command: Place): Settled = Closed
            @AfterCommand fun audit(command: Any): OrderEvent? = null
            """.trimIndent(),
        ).assert().contains(
            "onCommand returns sample.Refund, an interface that is not sealed",
            "after hook audit returns sample.OrderEvent, an interface that is not sealed, so the events it produces " +
                "are not listed: make it a sealed interface whose subclasses are the events, return the event " +
                "classes themselves (one, or a List or Flux of them)\n",
        )
    }

    @Test
    fun `an interface Jackson tells the subtypes of should be refused too, as the kernel keys events by class`() {
        // A sourcing function takes a concrete class, the kernel finds an event's type by its exact class, and the
        // produced events are checked by class: the subtypes Jackson writes are the events, and nothing lists them.
        failing("fun onCommand(command: Charge): Charged = Card(command.amount)").assert()
            .contains("onCommand returns sample.Charged, an interface that is not sealed")
    }

    @Test
    fun `a sealed type, event classes and a decision with its produces should list the events`() {
        val source = order(
            """
            fun onCommand(command: Place): Placed = Placed(command.id)
            fun onCommand(command: Pay): Progress = if (command.amount > 0) Packed(command.amount) else Dispatched
            @OnCommand(produces = [Shipped::class])
            fun onCommand(command: Ship): Decision = Decision.events(Shipped(command.to))
            """.trimIndent(),
        )
        val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop")
        val (compilation, result) = compileModel("generate", options, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val commands = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().aggregates.single().commands.associate { it.name to it.produces }
        commands["Place"].assert().isEqualTo(listOf("Placed"))
        commands["Pay"].assert().isEqualTo(listOf("Dispatched", "Packed"))
        commands["Ship"].assert().isEqualTo(listOf("Shipped"))
    }
}
