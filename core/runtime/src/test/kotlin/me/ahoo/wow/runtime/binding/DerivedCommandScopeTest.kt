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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decision D199: a command a saga derives is not checked against the scope of the existing aggregate it targets, so a
 * flow across owners (alice's order taking items out of bob's cart) goes through, while a caller of alice's own is
 * still refused bob's cart (D94, D189).
 */
class DerivedCommandScopeTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val decided = CopyOnWriteArrayList<String>()

    /** Takes the ordered items out of bob's cart, whoever ordered. */
    private val saga = ProcessorBinding.saga(
        "Restock",
        "onOrderPlaced",
        OrderState::class.java,
        derives = listOf(RemoveItems::class.java),
    ) { event, _ ->
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, BOB), RemoveItems(it.items))
        }
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(decided)),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun request(aggregate: String, command: String, items: List<String>, id: String, owner: String, wait: WaitPlan) =
        ServerCommandRequest(
            context = SHOP,
            aggregate = aggregate,
            command = command,
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope(ownerId = owner)),
            aggregateId = id,
            wait = wait,
        )

    private suspend fun cartItems(): Set<String> {
        val ref = CART.ref(TENANT, BOB)
        val version = backends.opened.getValue(SHOP).log.read(ref).toList().last().version
        return (node.context(SHOP).kernel.stateAt(cartModel(), ref, version)!!.state as CartState).items
    }

    private suspend fun place(id: String, vararg items: String): CommandResult {
        val chain = WaitPlan.of(Hop.committed(), Hop.handled(saga.name), Hop.committed())
        return node.gateway.send(request("order", "Place", items.toList(), id, ALICE, chain)).toList().last()
    }

    @Test
    fun `alice's saga should change bob's existing cart, which alice herself may not`(): Unit = runBlocking {
        node.start()
        val filled = node.gateway.execute(
            request("cart", "AddItems", listOf("apple", "pear", "plum"), BOB, BOB, WaitPlan.COMMITTED),
        )
        filled.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)

        val derived = place("o-1", "apple")
        derived.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        derived.ref.assert().isEqualTo(CART.ref(TENANT, BOB))
        place("o-2", "pear").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        cartItems().assert().containsExactly("plum")
        decided.assert().hasSize(2)
        // Bob's cart keeps bob's scope; the derived commands carried alice's, recorded for the audit.
        val streams = backends.opened.getValue(SHOP).log.read(CART.ref(TENANT, BOB)).toList()
        streams.map { it.scope }.distinct().assert().containsExactly(Scope(ownerId = BOB))

        // An external caller of alice's is refused bob's cart (D94).
        val external = node.gateway.execute(
            request("cart", "RemoveItems", listOf("plum"), BOB, ALICE, WaitPlan.COMMITTED),
        )
        external.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        external.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        cartItems().assert().containsExactly("plum")
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
    }
}
