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
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A saga's derived command that fails with a technical failure on every attempt (#6757): its failures are not the
 * wait's outcome, since the saga's handling, which they fail, is retried; once the saga parks the order, the wait ends
 * FAILED parked at the saga's hop, at once, not at its deadline.
 */
class DerivedCommandParkedWaitTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val attempts = AtomicInteger()

    /** The cart, whose decide function fails for a broken item: a failure of the handling, not a rejection. */
    private val cart: AggregateModel<CartState> = aggregate<CartState>(SHOP, "cart") {
        initial { CartState() }
        decide<AddItems>(Creation.ALLOWED) { _, body ->
            if (BROKEN in body.items) {
                // Not a require or a check, which refuse the command as a rejection does: a failure of the handling.
                throw IOException("The cart's store is unreachable (attempt ${attempts.incrementAndGet()}).")
            }
            Decision.events(ItemsAdded(body.items))
        }
        decide<RemoveItems> { _, _ -> Decision.NoOp }
        evolve<ItemsAdded> { items = items + it.items }
        evolve<ItemsRemoved> { items = items - it.items.toSet() }
    }

    private val saga = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        derives = listOf(AddItems::class.java),
    ) { event, _ ->
        val owner = event.scope.ownerId ?: return@saga emptyList()
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, owner), AddItems(it.items))
        }
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cart),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        node.stop()
    }

    @Test
    fun `a derived command that fails until its saga parks ends the wait failed parked at the saga's hop`(): Unit =
        runBlocking {
            node.start()
            val request = ServerCommandRequest(
                context = SHOP,
                aggregate = "order",
                command = "Place",
                body = CommandBody.of("""{"items":["$BROKEN"]}"""),
                caller = Caller(TENANT, Scope(ownerId = "bob")),
                aggregateId = "o-1",
                wait = WaitPlan.of(Hop.committed(), Hop.handled(SAGA), Hop.committed()).within(30.seconds),
            )
            // Well inside the wait's thirty seconds: the park answers it, not its deadline.
            val last = withTimeout(10.seconds) { node.gateway.send(request).toList() }.last()
            last.status.assert().describedAs("${last.hop}: ${last.error?.code}").isEqualTo(CommandResult.Status.FAILED)
            last.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            last.hop.subscription.assert().isEqualTo(SAGA)
            val ledger = backends.opened.getValue(SHOP).ledger
            eventually { ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-1")) }
            // The saga's budget: each attempt ran the derived command once.
            attempts.get().assert().isEqualTo(2)
        }

    private companion object {
        const val SAGA = "CartSaga.onOrderPlaced"
        const val BROKEN = "broken"
    }
}
