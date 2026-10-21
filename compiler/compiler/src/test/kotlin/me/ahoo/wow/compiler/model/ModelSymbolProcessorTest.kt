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

/** Spike H4 / roadmap 2.3: annotated aggregates compile into kernel models that run. */
@OptIn(ExperimentalCompilerApi::class)
class ModelSymbolProcessorTest {
    private fun compile(mode: String, vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> =
        compileModel(mode, *sources)

    private val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.decide.Decision

        @CreateAggregate
        data class CreateCart(val owner: String)
        data class AddItem(val productId: String, val quantity: Int)
        @Name("PutItems")
        data class AddItems(val productIds: List<String>)
        data class Inspect(val note: String)

        data class CartCreated(val owner: String)
        @Name("ItemPut")
        data class ItemAdded(val productId: String, val quantity: Int)
        data class Audited(val command: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            fun onCommand(command: CreateCart): CartCreated = CartCreated(command.owner)

            fun onCommand(command: AddItem): Any =
                if (state.items.size >= 2) Decision.reject("cart.full", "full") else ItemAdded(command.productId, command.quantity)

            fun onCommand(command: AddItems): List<ItemAdded> = command.productIds.map { ItemAdded(it, 1) }

            suspend fun onCommand(command: Inspect) = Unit

            @AfterCommand(exclude = [CreateCart::class])
            fun afterCommand(command: Any): Audited = Audited(command.javaClass.simpleName)
        }

        class CartState(val id: String) {
            var owner: String? = null
            val items = mutableMapOf<String, Int>()
            var audits = 0

            @OnSourcing
            fun onCreated(event: CartCreated) { owner = event.owner }

            fun onSourcing(event: ItemAdded) { items[event.productId] = (items[event.productId] ?: 0) + event.quantity }

            fun onSourcing(event: Audited) { audits++ }
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
        import me.ahoo.wow.kernel.memory.InMemoryEventLog
        import me.ahoo.wow.kernel.Kernel

        fun run(): String = runBlocking {
            val model = cartModel("shop")
            val kernel = Kernel(InMemoryEventLog(), verifyDecidePurity = true)
            val ref = model.type.ref("tenant", "cart-1")
            var n = 0
            suspend fun send(body: Any): CommandOutcome {
                n++
                return kernel.execute(model, Command("c" + n, "r" + n, ref, body))
            }
            val outcomes = listOf(
                send(CreateCart("ahoo")),
                send(AddItem("apple", 2)),
                send(AddItems(listOf("pear"))),
                send(AddItem("plum", 1)),
                send(Inspect("look")),
            )
            outcomes.joinToString(" | ") { outcome ->
                when (outcome) {
                    is CommandOutcome.Committed -> outcome.stream.commandType + ">" +
                        outcome.stream.events.joinToString(",") { it.name } +
                        "@" + (outcome.state as CartState).let { it.items.toString() + "/" + it.audits }
                    is CommandOutcome.Rejected -> "rejected:" + outcome.rejection.code
                    else -> outcome.toString()
                }
            }
        }
        """.trimIndent(),
    )

    @Test
    fun `an annotated aggregate compiles into a model the kernel runs`() {
        val (compilation, result) = compile("generate", cart, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val run = result.classLoader.loadClass("sample.ScenarioKt").getMethod("run")
        run.invoke(null).assert().isEqualTo(
            "shop.cart.CreateCart>shop.cart.CartCreated@{}/0 | " +
                "shop.cart.AddItem>shop.cart.ItemPut,shop.cart.Audited@{apple=2}/1 | " +
                "shop.cart.PutItems>shop.cart.ItemPut,shop.cart.Audited@{apple=2, pear=1}/2 | " +
                "rejected:cart.full | " +
                "shop.cart.Inspect>shop.cart.Audited@{apple=2, pear=1}/3",
        )
        compilation.workingDir.walkTopDown().any { it.name == "CartModel.kt" }.assert().isTrue()
    }

    private val account = SourceFile.kotlin(
        "Account.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.AllowCreate
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.decide.DecideScope
        import reactor.core.publisher.Flux
        import reactor.core.publisher.Mono

        @AllowCreate
        data class Deposit(val amount: Int)
        data class Split(val amounts: List<Int>)
        data class Deposited(val amount: Int)
        fun interface Fees { fun of(amount: Int): Int }

        @AggregateRoot
        class Account(private val state: AccountState) {
            fun onCommand(command: Command<Deposit>, fees: Fees, scope: DecideScope): Mono<Deposited> {
                scope.putResult("by", command.headers.getValue("app"))
                return Mono.just(Deposited(command.body.amount - fees.of(command.body.amount)))
            }

            fun onCommand(command: Split, fees: Fees): Flux<Deposited> =
                Flux.fromIterable(command.amounts).map { Deposited(it - fees.of(it)) }
        }

        class AccountState(val id: String) {
            var balance = 0
            fun onSourcing(event: Deposited) { balance += event.amount }
        }
        """.trimIndent(),
    )

    @Test
    fun `dependencies, the decide scope, allowed creation and Reactor returns bind`() {
        val run = SourceFile.kotlin(
            "AccountScenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.kernel.CommandOutcome
            import me.ahoo.wow.kernel.memory.InMemoryEventLog
            import me.ahoo.wow.kernel.Kernel

            fun run(): String = runBlocking {
                val model = accountModel("bank", Fees { 1 })
                val kernel = Kernel(InMemoryEventLog())
                val ref = model.type.ref("tenant", "a-1")
                val first = kernel.execute(model, Command("c1", "r1", ref, Deposit(10), headers = mapOf("app" to "atm")))
                val second = kernel.execute(model, Command("c2", "r2", ref, Split(listOf(5, 3))))
                listOf(first, second).joinToString(" | ") {
                    val committed = it as CommandOutcome.Committed
                    (committed.state as AccountState).balance.toString() + committed.results
                }
            }
            """.trimIndent(),
        )
        val (_, result) = compile("generate", account, run)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.AccountScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo("9{by=atm} | 15{}")
    }

    @Test
    fun `what the processor cannot bind fails the generation`() {
        val unsupported = SourceFile.kotlin(
            "Order.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.command.CommandResultAccessor
            import me.ahoo.wow.api.command.CommandMessage

            data class PlaceOrder(val id: String)
            data class CancelOrder(val id: String)
            data class OrderPlaced(val id: String)

            @AggregateRoot
            class Order(private val state: OrderState) {
                fun onCommand(command: PlaceOrder, results: CommandResultAccessor): OrderPlaced = OrderPlaced(command.id)

                fun onCommand(command: CommandMessage<CancelOrder>): OrderPlaced = OrderPlaced(command.body.id)
            }

            class OrderState(val id: String) {
                fun onSourcing(event: OrderPlaced) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compile("generate", unsupported)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("takes me.ahoo.wow.api.command.CommandResultAccessor, a 9.x framework type")
            .contains(
                "takes me.ahoo.wow.api.command.CommandMessage<sample.CancelOrder>: a decide function takes the"
            )
    }

    @Test
    fun `report mode generates nothing and lists what it could not bind`() {
        val (compilation, result) = compile("report", cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val files = compilation.workingDir.walkTopDown().filter { it.isFile }.toList()
        files.any { it.name == "CartModel.kt" }.assert().isFalse()
        val report = files.single { it.name == "model-report.json" }.readText()
        report.assert().contains("\"aggregates\" : [")
            .contains("\"root\" : \"sample.Cart\"")
            .contains("\"commands\" : 4")
            .contains("\"problems\" : [ ]")
            .contains("\"subscriptions\" : [ ]")
    }
}
