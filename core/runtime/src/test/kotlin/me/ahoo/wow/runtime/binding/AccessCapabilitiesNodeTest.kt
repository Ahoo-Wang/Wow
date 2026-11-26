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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.access.EventsSource
import me.ahoo.wow.runtime.access.EventsSources
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

/**
 * A node whose types' `events` models are served by stores that cannot keep their histories' access attributes current
 * refuses to start (#7157): an events query would otherwise decide by an owner the aggregate no longer has. Wow's own
 * logs, the in-memory one included, keep them current and start; a type whose model no store serves starts as before.
 */
class AccessCapabilitiesNodeTest {
    private val inner = InMemoryBackends(partitions = 4)

    /** The in-memory log behind a plain [EventLog]: every port of a log, neither access capability. */
    private val outside = BackendFactory { context ->
        val wow = inner.backends(context)
        wow.copyWith(log = object : EventLog by (wow.log as InMemoryEventLog) {})
    }

    private fun node(
        backends: BackendFactory,
        sources: EventsSources,
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
    ) = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions().copyWith(handoff = HandoffOptions(probeInterval = 50.milliseconds)),
        subscriptions = subscriptions,
        eventsSources = sources,
    )

    private val fromLogs = EventsSources { EventsSource.Log }

    @Test
    fun `a node whose events models a log without the access capabilities serves refuses to start`() {
        assertThrows<IllegalStateException> { node(outside, fromLogs) }.message.assert()
            .contains("shop.cart", "shop.order", "its log on the store 'default'", "no AccessStamps")
    }

    @Test
    fun `on that log, a node that serves no events model starts as before`(): Unit = runBlocking {
        val node = node(outside, EventsSources.NONE)
        node.start()
        node.stop()
    }

    @Test
    fun `events served from another store refuse a log that does not walk, and start with a stamping Sink`(): Unit =
        runBlocking {
            val search = EventsSources { EventsSource.Store("search") }
            assertThrows<IllegalStateException> { node(outside, search) }.message.assert()
                .contains("shop.cart", "the store 'search'", "no AccessWalks")
            assertThrows<IllegalStateException> { node(inner, search) }.message.assert()
                .contains("shop.cart", "whose Sink", "no AccessStamps")
            val stamping = SubscriptionFactory { context ->
                context.models.forEach { model -> context.accessWalking?.target(model.type, NoStamps) }
                emptyList()
            }
            val node = node(inner, search, stamping)
            node.start()
            node.stop()
        }

    @Test
    fun `Wow's in-memory log keeps the attributes current, and is not refused`(): Unit = runBlocking {
        val node = node(inner, fromLogs)
        try {
            node.start()
            val ref = CART.ref(TENANT, "c-1")
            append(ref, 1, "alice")
            append(ref, 2, "bob")
            eventually { inner.opened.getValue(SHOP).log.read(ref).toList().map { it.access } == List(2) { owned("bob") } }
        } finally {
            node.stop()
        }
    }

    private fun owned(owner: String) = AccessValues.ofStream(emptyMap(), Scope(owner))

    private suspend fun append(ref: AggregateRef, version: Int, owner: String) {
        val stream = EventStreamRecord(
            ref = ref,
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "shop.cart.AddItems",
            commitTime = System.currentTimeMillis(),
            events = listOf(EventRecord("shop.cart.ItemsAdded", 1, """{"items":["item-$version"]}""")),
            scope = Scope(owner),
            access = owned(owner),
        )
        inner.opened.getValue(SHOP).log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    /** A Sink's stamps that write nothing: what the check asks is that a Sink of the type stamps. */
    private object NoStamps : AccessStamps {
        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int) = 0
    }
}
