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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.StateManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Decision D166: the commands an aggregate's decide functions and a saga's handlers schedule, as `@OnCommand(schedules)`
 * and `@OnEvent(schedules)` declare them, are the Manifest's `schedules`. The compiler does not read function bodies,
 * so the calls to `schedule` are declared, not inferred.
 */
@OptIn(ExperimentalCompilerApi::class)
class ScheduleManifestTest {
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
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.decide.DecideScope
        import me.ahoo.wow.api.schedule.ScheduleRule
        import kotlin.time.Duration.Companion.minutes

        @CreateAggregate
        data class PlaceOrder(val id: String)
        data class PayOrder(val amount: Int)
        data class CancelOrder(val reason: String)
        data class OrderPlaced(val id: String)
        data class OrderPaid(val amount: Int)
        data class OrderCancelled(val reason: String)

        @AggregateRoot
        class Order(val id: String) {
            @OnCommand(schedules = ["shop.order.CancelOrder", "shop.order.CancelOrder"])
            fun onCommand(command: PlaceOrder, scope: DecideScope): OrderPlaced {
                scope.schedule("pay-timeout", ScheduleRule.after(30.minutes), CancelOrder("unpaid"))
                return OrderPlaced(command.id)
            }

            @OnCommand(schedules = ["inventory.stock.Release"])
            fun onCommand(command: PayOrder, scope: DecideScope): OrderPaid {
                scope.cancelSchedule("pay-timeout")
                return OrderPaid(command.amount)
            }

            fun onCommand(command: CancelOrder): OrderCancelled = OrderCancelled(command.reason)
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: OrderPaid) = Unit
            fun onSourcing(event: OrderCancelled) = Unit
        }
        """.trimIndent(),
    )

    private val saga = SourceFile.kotlin(
        "Reminder.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.schedule.ScheduleRule
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import kotlin.time.Duration.Companion.hours

        @Saga
        class Reminder {
            @OnEvent(schedules = ["shop.order.CancelOrder"])
            fun onEvent(event: StateEvent<Order>, context: SagaContext): List<Command<*>> {
                return listOf(context.schedule("remind", ScheduleRule.after(1.hours), event.ref, CancelOrder("reminder")))
            }

            @OnEvent
            fun undeclared(event: StateEvent<Order>, context: SagaContext): List<Command<*>> = emptyList()
        }
        """.trimIndent(),
    )

    @Test
    fun `the fragment should list what an aggregate and a saga schedule`() {
        val (compilation, result) = compileModel("generate", options, model, saga)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val file = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val context = ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java)).contexts.single()
        context.aggregates.single().schedules.assert()
            .isEqualTo(listOf("inventory.stock.Release", "shop.order.CancelOrder"))
        context.subscriptions.associate { it.name to it.schedules }.assert().isEqualTo(
            mapOf("Reminder.onEvent" to listOf("shop.order.CancelOrder"), "Reminder.undeclared" to null),
        )
    }

    @Test
    fun `a malformed name, or a projection that schedules, should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot
            class Account(val id: String) {
                @OnCommand(schedules = ["Close"])
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @Projection
            class Board {
                @OnEvent(schedules = ["shop.account.Close"])
                fun onEvent(event: StateEvent<Account>) = Unit
            }

            @Saga
            class Closer {
                @OnEvent(schedules = ["account.Close"])
                fun onEvent(event: StateEvent<Account>): List<Command<*>> = emptyList()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("schedules \"Close\": @OnCommand(schedules) names each command as context.aggregate.Command")
            .contains("declares @OnEvent(schedules), but a @Projection handler schedules no commands")
            .contains(
                "schedules \"account.Close\": @OnEvent(schedules) names each command as context.aggregate.Command"
            )
    }

    @Test
    fun `the schedules should read back from the tree as written, and be left out when unknown`() {
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            "order",
                            state = StateManifest("#/\$defs/Order"),
                            schedules = listOf("shop.order.CancelOrder"),
                        ),
                        AggregateManifest(
                            "cart",
                            state = StateManifest("#/\$defs/Cart"),
                        ),
                    ),
                ),
            ),
        )
        val tree = ManifestTree.write(manifest)
        ManifestTree.read(tree).assert().isEqualTo(manifest)
        @Suppress("UNCHECKED_CAST")
        val aggregates = ((tree["contexts"] as List<Map<String, Any?>>).single()["aggregates"] as List<Map<String, Any?>>)
        aggregates.map { it.containsKey("schedules") }.assert().containsExactly(true, false)
    }
}
