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

import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

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
    }

    @Test
    fun `a saga spec should resolve the commands whose ref a handler leaves out from the test's class path`() {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loaderOf("local", "peer")
        try {
            val saga = ProcessorBinding.saga<String>("OrderSaga", "onPlaced", String::class.java) { event, context ->
                listOf(CommandRequest.of(Reserve(event.ref.id), "product-1"), CommandRequest.of(Place("next")))
            }
            val placed = StateEvent(order.ref("acme", "o-1"), 1, "c-1", "r-1", "shop.order.Place", 1L, listOf("p"), "s")
            sagaSpec(listOf(saga)) {
                whenEvent(placed) {
                    commands.map { it.ref }.assert()
                        .containsExactly(stock.ref("acme", "product-1"), order.unassigned("acme"))
                    expectCommands(Reserve("o-1"), Place("next"))
                    expectDeterministic()
                }
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }
}
