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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.days

/** The provider of the fixture `commands/local`, whose fragment names the order's commands. */
class FixtureModelProvider : ModelProvider {
    override val context: String = "shop"
    override val binding: Int = ModelProvider.BINDING
    override val fragment: String = "META-INF/wow/manifest/fixture.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()
}

/** Decision D234 ⑤: a saga under test sends a command whose ref it leaves out where the service would. */
class ClassPathCommandTypesTest {
    data class Reserve(val orderId: String)
    data class Note(val text: String)
    data class Import(val text: String)
    data class Place(val orderId: String)
    data class Restock(val productId: String)

    private val stock = AggregateType("inventory", "stock")
    private val order = AggregateType("shop", "order")

    private fun loaderOf(vararg fixtures: String): ClassLoader = URLClassLoader(
        fixtures.map { requireNotNull(javaClass.getResource("/commands/$it/")) }.toTypedArray(),
        javaClass.classLoader,
    )

    @Test
    fun `the fragments of the module and the contracts of its api jars should assign each command class`() {
        val types = ClassPathCommandTypes.of(loaderOf("local", "peer", "broken"))
        types.aggregatesOf(Reserve::class.java).assert().containsExactly(stock)
        types.aggregatesOf(Place::class.java).assert().containsExactly(order)
        types.aggregatesOf(Note::class.java).assert().containsExactly(stock, AggregateType("inventory", "warehouse"))
        // A mounted command is only an edge's; an unknown class is no aggregate's.
        types.aggregatesOf(Import::class.java).assert().isEmpty()
        types.aggregatesOf(String::class.java).assert().isEmpty()
        types.nameOf(stock, Reserve::class.java).assert().isEqualTo("inventory.stock.Reserve")
        types.nameOf(AggregateType("inventory", "warehouse"), Note::class.java)
            .assert().isEqualTo("inventory.warehouse.Note")
        types.nameOf(order, Place::class.java).assert().isEqualTo("shop.order.Place")
        // As a node's contract directory names them: none for a class the target's contract does not list, for a
        // mounted command, or for a target no contract on the class path describes.
        types.nameOf(stock, Place::class.java).assert().isNull()
        types.nameOf(stock, Import::class.java).assert().isNull()
        types.nameOf(AggregateType("logistics", "shipment"), Reserve::class.java).assert().isNull()
        // A command whose contract reads its aggregate's id from its body.
        types.aggregateIdOf(stock, Restock("mug")).assert().isEqualTo("mug")
        types.aggregateIdOf(stock, Reserve("o-1")).assert().isNull()
    }

    @Test
    fun `the command types of a class loader should be read once`() {
        val loader = loaderOf("peer")
        ClassPathCommandTypes.of(loader).assert().isSameAs(ClassPathCommandTypes.of(loader))
        ClassPathCommandTypes.of(loaderOf()).aggregatesOf(Reserve::class.java).assert().isEmpty()
    }

    @Test
    fun `a Manifest without the expected shape should list no commands`() {
        ClassPathCommandTypes.commandsOf(mapOf("contexts" to "none")).assert().isEmpty()
        ClassPathCommandTypes.commandsOf(
            mapOf(
                "contexts" to listOf(
                    mapOf("aggregates" to emptyList<Any>()),
                    mapOf("name" to "shop", "aggregates" to listOf(mapOf("commands" to emptyList<Any>()))),
                    mapOf(
                        "name" to "shop",
                        "aggregates" to listOf(mapOf("name" to "order", "commands" to listOf(mapOf("name" to "X")))),
                    ),
                ),
            ),
        ).assert().isEmpty()
        ClassPathCommandTypes.idPropertiesOf(
            mapOf(
                "contexts" to listOf(
                    mapOf(
                        "aggregates" to listOf(
                            mapOf(
                                "commands" to listOf(
                                    mapOf("idProperty" to "id"),
                                    mapOf("schema" to "#/\$defs/A"),
                                    mapOf("schema" to "#/\$defs/B", "idProperty" to "bId"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).assert().isEqualTo(mapOf("#/\$defs/B" to "bId"))
        // A command without a schema or a name, a mounted one and a system one name no type.
        ClassPathCommandTypes.typesOf(
            mapOf(
                "contexts" to listOf(
                    mapOf("aggregates" to emptyList<Any>()),
                    mapOf("name" to "shop", "aggregates" to listOf(mapOf("commands" to emptyList<Any>()))),
                    mapOf(
                        "name" to "shop",
                        "aggregates" to listOf(
                            mapOf(
                                "name" to "order",
                                "commands" to listOf(
                                    mapOf("name" to "X"),
                                    mapOf("schema" to "#/\$defs/Y"),
                                    mapOf("name" to "M", "schema" to "#/\$defs/M", "mounted" to true),
                                    mapOf("name" to "S", "schema" to "#/\$defs/S", "system" to true),
                                    mapOf("name" to "Z", "schema" to "#/\$defs/Z"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).assert().containsExactly(Triple("#/\$defs/Z", "shop.order", "shop.order.Z"))
    }

    class Stock(val id: String)
    data class Count(val sku: String)
    data class Counted(val sku: String)

    private val countingStock: AggregateModel<Stock> = aggregate("inventory", "stock") {
        initial { Stock(it.id) }
        decide<Count> { _, body -> Decision.events(Counted(body.sku)) }
        evolve<Counted> { }
    }

    @Test
    fun `the models a specification is given should name and place their commands before the contracts`() {
        val classPath = ClassPathCommandTypes.of(loaderOf("peer"))
        val types = classPath.withModels { listOf(countingStock) }
        // As a node's context: its own aggregate's commands by its model alone, so Reserve, which the peer contract
        // lists for the stock, is not this stock's; another aggregate's by its contract.
        types.nameOf(stock, Count::class.java).assert().isEqualTo("inventory.stock.Count")
        types.nameOf(stock, Reserve::class.java).assert().isNull()
        types.nameOf(AggregateType("inventory", "warehouse"), Note::class.java)
            .assert().isEqualTo("inventory.warehouse.Note")
        types.aggregatesOf(Count::class.java).assert().containsExactly(stock)
        types.aggregatesOf(Note::class.java).assert().containsExactly(stock, AggregateType("inventory", "warehouse"))
        types.aggregateIdOf(stock, Count("mug")).assert().isNull()
        classPath.withModels { emptyList() }.aggregateIdOf(stock, Restock("mug")).assert().isEqualTo("mug")
    }

    data class Plan(val orderId: String)
    data class Planned(val orderId: String)

    class Planner(val id: String)

    /** Plans an order's restock: the inventory's stock is sent the peer's Reserve a day later. */
    private val plannerModel: AggregateModel<Planner> = aggregate("shop", "planner") {
        initial { Planner(it.id) }
        decide<Plan>(Creation.REQUIRED) { _, body ->
            val restock = stock.ref(command.ref.tenantId, "mug")
            schedule("restock", ScheduleRule.after(1.days), Reserve(body.orderId), restock)
            Decision.events(Planned(body.orderId))
        }
        evolve<Planned> { }
    }

    /** Runs [block] with [loader] as the context class loader, which a specification reads its contracts from. */
    private fun <T> within(loader: ClassLoader, block: () -> T): T {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    @Test
    fun `a decision that schedules a peer's command for its aggregate should commit the schedule`() =
        within(loaderOf("peer")) {
            // As a node's kernel, which names a peer's command by its contract (ContextCommandTypes): moved from
            // review/r1-testkit (DslReviewR1Test), where the spec's kernel had no command types and threw Misuse.
            aggregateSpec(plannerModel) {
                whenCommand(Plan("order-1")) {
                    expectScheduled("restock", Reserve("order-1"), target = stock.ref(DEFAULT_TENANT, "mug"))
                }
            }
        }

    @Test
    fun `a saga that schedules a command its target does not decide should park`() = within(loaderOf("peer")) {
        // As on a node (ContextCommandTypes): the stock's contract lists no command of the class Count, so the
        // schedule is the saga's mistake, unrecoverable. Moved from review/r1-testkit (DslReviewR1Test).
        val saga = ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Planner::class.java,
            schedules = listOf(Count::class.java),
        ) { event: StateEvent<Planner>, context ->
            val target = stock.ref(event.ref.tenantId, "mug")
            context.schedule("later", ScheduleRule.after(1.days), CommandRequest.to(target, Count("mug")))
            emptyList()
        }
        sagaSpec(listOf(saga)) {
            givenAggregate(plannerModel, "planner-1", Planned("order-1"))
            whenEvent(Planned("order-2")) {
                expectError<Misuse.Argument> { message.assert().contains("a command no Manifest") }
                expectParked()
            }
        }
    }

    @Test
    fun `a saga spec should resolve the commands whose ref a handler leaves out from the test's class path`() {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loaderOf("local", "peer")
        try {
            val saga = ProcessorBinding.saga<String>(
                "OrderSaga",
                "onPlaced",
                String::class.java,
                derives = listOf(Reserve::class.java, Place::class.java, Restock::class.java),
            ) { event, context ->
                listOf(
                    CommandRequest.of(Reserve(event.ref.id), "product-1"),
                    CommandRequest.of(Place("next")),
                    CommandRequest.of(Restock("mug")),
                )
            }
            val placed = StateEvent(order.ref("acme", "o-1"), 1, "c-1", "r-1", "shop.order.Place", 1L, listOf("p"), "s")
            sagaSpec(listOf(saga)) {
                whenEvent(placed) {
                    commands.map { it.ref }.assert()
                        .containsExactly(
                            stock.ref("acme", "product-1"),
                            order.unassigned("acme"),
                            stock.ref("acme", "mug"),
                        )
                    expectCommands(Reserve("o-1"), Place("next"), Restock("mug"))
                    expectDeterministic()
                }
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }
}
