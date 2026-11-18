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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/**
 * A saga that sends a command naming no aggregate, which the service refuses unless the command may leave its id
 * out (a creation, an `@Unaddressed` command), fails its specification there, as admission would refuse the send on
 * the node: a spec does not pass on an aggregate the send never reaches.
 */
class UnaddressedSendSpecTest {
    data class Dispatch(val carrier: String)
    data class Open(val carrier: String)
    data class Route(val carrier: String)

    private val order = AggregateType("shop", "order")
    private val shipment = AggregateType("shipping", "shipment")
    private val placed = StateEvent(order.ref("acme", "o-1"), 1, "c-1", "r-1", "shop.order.Place", 1L, listOf("p"), "s")

    /** [block] with the contract of the shipments on the class path the specification reads. */
    private fun withContract(block: () -> Unit) {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = URLClassLoader(
            arrayOf(requireNotNull(javaClass.getResource("/commands/routed/"))),
            javaClass.classLoader,
        )
        try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    private fun saga(vararg requests: CommandRequest<*>) = ProcessorBinding.saga<String>(
        "ShipmentSaga",
        "onPlaced",
        String::class.java,
        derives = listOf(Dispatch::class.java, Open::class.java, Route::class.java),
    ) { _, _ -> requests.toList() }

    @Test
    fun `a command that may leave its id out, or one that names it, should be sent`() = withContract {
        val sends = saga(
            CommandRequest.of(Open("dhl")),
            CommandRequest.of(Route("dhl")),
            CommandRequest.of(Dispatch("dhl"), "s-1"),
        )
        sagaSpec(listOf(sends)) {
            whenEvent(placed) {
                commands.map { it.ref }.assert().containsExactly(
                    shipment.unassigned("acme"),
                    shipment.unassigned("acme"),
                    shipment.ref("acme", "s-1"),
                )
                expectCommands(Open("dhl"), Route("dhl"), Dispatch("dhl"))
            }
        }
    }

    @Test
    fun `a command routed by its id alone, sent naming none, should fail the specification as the service refuses it`() =
        withContract {
            sagaSpec(listOf(saga(CommandRequest.of(Dispatch("dhl"))))) {
                whenEvent(placed) {
                    expectError<IllegalStateException> {
                        message.assert().isEqualTo(
                            "The saga sends ${Dispatch::class.java.name} naming no shipping.shipment to act on, " +
                                "which the service refuses: only a command that creates its aggregate, one its body " +
                                "names the aggregate of (@AggregateId), or one a transformer addresses (@Unaddressed) " +
                                "may leave its id out. Name the aggregate: CommandRequest.of(command, id).",
                        )
                    }
                }
            }
        }

    @Test
    fun `a call naming no aggregate, or a receipt handler's send or call, should fail the specification too`() =
        withContract {
            val dispatch = CommandRequest.of(Dispatch("dhl"))
            val calling = ProcessorBinding.saga<String>(
                "ShipmentSaga",
                "onPlaced",
                String::class.java,
                calls = listOf(Dispatch::class.java),
            ) { _, context ->
                context.call(dispatch)
                emptyList()
            }
            sagaSpec(listOf(calling)) { whenEvent(placed) { expectError<IllegalStateException>() } }
            val onRejected = RejectionBinding.of(
                "ShipmentSaga",
                "onRejected",
                Open::class.java,
                derives = listOf(Dispatch::class.java),
            ) { _, _ -> listOf(dispatch) }
            val onOpened = CommittedBinding.of(
                "ShipmentSaga",
                "onOpened",
                Open::class.java,
                calls = listOf(Dispatch::class.java),
            ) { _, context ->
                context.call(dispatch)
                emptyList()
            }
            val onRouted = CommittedBinding.of(
                "ShipmentSaga",
                "onRouted",
                Route::class.java,
                derives = listOf(Dispatch::class.java),
            ) { _, _ -> listOf(dispatch) }
            val receipts = ProcessorBinding.saga<String>(
                "ShipmentSaga",
                "onPlaced",
                String::class.java,
                rejections = listOf(onRejected),
                commits = listOf(onOpened, onRouted),
                derives = listOf(Open::class.java, Route::class.java),
            ) { _, _ -> listOf(CommandRequest.of(Open("dhl")), CommandRequest.of(Route("dhl"))) }
            sagaSpec(listOf(receipts)) {
                whenEvent(placed) {
                    val (open, route) = commands
                    whenRejected(open, FrameworkCodes.AGGREGATE_EXISTS) { expectError<IllegalStateException>() }
                    whenCommitted(open) { expectError<IllegalStateException>() }
                    whenCommitted(route) { expectError<IllegalStateException>() }
                }
            }
        }

    @Test
    fun `a Manifest's commands routed by an id alone should be those every listed route names one`() {
        ClassPathCommandTypes.addressedByIdOf(
            mapOf(
                "contexts" to listOf(
                    mapOf(
                        "aggregates" to listOf(
                            mapOf(
                                "commands" to listOf(
                                    mapOf("schema" to "#/\$defs/A", "routes" to listOf(mapOf("path" to "/a/{id}/x"))),
                                    mapOf("schema" to "#/\$defs/B", "routes" to listOf(mapOf("path" to "/b/x"))),
                                    mapOf("schema" to "#/\$defs/C"),
                                    mapOf("routes" to listOf(mapOf("path" to "/d/{id}/x"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).assert().containsExactly("#/\$defs/A")
    }
}
