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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Decision D385 (PA-33): the state event a node's own subscription hands over carries the aggregate's tags after the
 * commit, those `ApplyTags` set with the keys its state derives (`@Tags`) overriding them, as its snapshot holds them:
 * a saga reads them without reading the snapshot, which may lag behind the commit.
 */
class StateEventTagsTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val tagsByVersion = ConcurrentHashMap<Int, Map<String, List<String>>>()

    /** The cart of the binding fixtures, whose items are a tag of their own. */
    private val model: AggregateModel<CartState> = aggregate<CartState>(SHOP, "cart") {
        initial { CartState() }
        decide<AddItems>(Creation.ALLOWED) { _, body -> Decision.events(ItemsAdded(body.items)) }
        decide<RemoveItems> { _, body -> Decision.events(ItemsRemoved(body.items)) }
        evolve<ItemsAdded> { items = items + it.items }
        evolve<ItemsRemoved> { items = items - it.items.toSet() }
        tags { mapOf("item" to it.items.sorted()) }
    }

    private val saga = ProcessorBinding.saga("CartSaga", "onEvent", CartState::class.java) { event, _ ->
        tagsByVersion[event.version] = event.tags
        emptyList()
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), model),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private suspend fun send(command: String, body: String): CommandResult = node.gateway.execute(
        ServerCommandRequest(
            context = SHOP,
            aggregate = "cart",
            command = command,
            body = CommandBody.of(body),
            caller = Caller(TENANT, Scope.NONE),
            aggregateId = "c-1",
            wait = WaitPlan.COMMITTED,
        ),
    ).also { it.status.assert().withFailMessage { it.toString() }.isEqualTo(CommandResult.Status.SUCCEEDED) }

    @Test
    fun `a saga should receive the aggregate's tags after each commit, the derived keys overriding the applied`(): Unit =
        runBlocking {
            node.start()
            send("AddItems", """{"items":["apple"]}""")
            // ApplyTags sets the region, and an item the derived key overrides.
            send(Lifecycle.APPLY_TAGS, """{"tags":{"region":["eu"],"item":["forged"]}}""")
            send("AddItems", """{"items":["pear"]}""")

            eventually { tagsByVersion.size == 3 }
            tagsByVersion[1].assert().isEqualTo(mapOf("item" to listOf("apple")))
            tagsByVersion[2].assert().isEqualTo(mapOf("region" to listOf("eu"), "item" to listOf("apple")))
            val last = mapOf("region" to listOf("eu"), "item" to listOf("apple", "pear"))
            tagsByVersion[3].assert().isEqualTo(last)
            // The same tags as the snapshot of that commit holds.
            val snapshots = checkNotNull(backends.opened.getValue(SHOP).snapshots).primaryOf(CART)
            eventually { snapshots.load(CART.ref(TENANT, "c-1"))?.version == 3 }
            snapshots.load(CART.ref(TENANT, "c-1"))!!.tags.assert().isEqualTo(last)
        }
}
