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
 * Generated code names Kotlin's own declarations by their qualified names (#5666): a domain package may declare a
 * type or a function of any name Kotlin imports implicitly, which hides Kotlin's in that package, where the generated
 * sources are.
 */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedNamesTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    /** A domain declaring the names the generators write, each hiding Kotlin's in its package. */
    private val shadowing = SourceFile.kotlin(
        "Shadowing.kt",
        """
        package sample.shadow

        class Unit
        class String
        class Any
        class Int
        class Long
        class Boolean
        class List<T>
        class Set<T>
        class Map<K, V>
        class Nothing
        class Array<T>
        class Suppress
        class OptIn
        class Deprecated
        class JvmField

        fun <T> listOf(vararg elements: T): kotlin.Int = elements.size
        fun <T> setOf(vararg elements: T): kotlin.Int = elements.size
        fun <T> emptyList(): kotlin.Int = 0
        fun <K, V> mapOf(vararg pairs: kotlin.Pair<K, V>): kotlin.Int = pairs.size
        """.trimIndent(),
    )

    /** An aggregate, its access attribute and query fields, processors of each kind, a schedule and a route. */
    private val domain = SourceFile.kotlin(
        "Shop.kt",
        """
        package sample.shadow

        import me.ahoo.wow.api.annotation.Access
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.OnSchedule
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.RouteMethod
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.annotation.ServiceRoute
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.schedule.ScheduleTick
        import me.ahoo.wow.api.subscribe.CommandReceipt
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent

        @CreateAggregate
        data class Open(val owner: kotlin.String)
        data class Opened(val owner: kotlin.String)
        data class Close(val reason: kotlin.String)
        data class Closed(val reason: kotlin.String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: Open): Opened = Opened(command.owner)
            fun onCommand(command: Close): Closed = Closed(command.reason)
        }

        class CartState {
            var owner: kotlin.String = ""
            var items: kotlin.collections.List<kotlin.String> = kotlin.collections.emptyList()

            @Access val buyer: kotlin.String get() = owner

            fun onSourcing(event: Opened) { owner = event.owner }
            fun onSourcing(event: Closed) = kotlin.Unit
        }

        @Projection
        class CartProjection {
            @OnEvent fun onOpened(event: StateEvent<CartState>) = kotlin.Unit
            @OnEvent fun onAny(event: StateEvent<*>) = kotlin.Unit
        }

        @Reactor
        object CartReactor {
            @OnEvent fun onOpened(event: StateEvent<CartState>) = kotlin.Unit
        }

        @Saga
        class CartSaga {
            @OnEvent
            fun onOpened(event: StateEvent<CartState>, context: SagaContext): CommandRequest<Close>? = null

            @OnRejected
            fun onRejected(rejected: CommandReceipt.Rejected<Close>): kotlin.collections.List<CommandRequest<Close>> =
                kotlin.collections.emptyList()

            @OnSchedule(name = "sweep", cron = "@daily")
            fun sweep(tick: ScheduleTick): kotlin.collections.List<CommandRequest<Close>> = kotlin.collections.emptyList()
        }

        data class Summary(val total: kotlin.Int)

        class CartRoutes {
            @ServiceRoute(method = RouteMethod.GET, path = "summaries/{day}")
            suspend fun summary(day: kotlin.String): Summary = Summary(day.length)
        }
        """.trimIndent(),
    )

    @Test
    fun `generated sources should compile in a package that hides Kotlin's names`() {
        val (compilation, result) = compileModel("generate", options, shadowing, domain)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        // Every generator wrote its source into the package.
        val generated = compilation.workingDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.name }.toSet()
        generated.assert().contains(
            "CartModel.kt",
            "CartStateAccess.kt",
            "CartStateProperties.kt",
            "ShopDomainModelProvider.kt",
        )
        generated.assert().contains(
            "CartProjectionSubscriptions.kt",
            "CartReactorSubscriptions.kt",
            "CartSagaSubscriptions.kt"
        )
    }
}
