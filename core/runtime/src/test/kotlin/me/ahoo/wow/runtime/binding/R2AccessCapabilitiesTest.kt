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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A log without the access capabilities (`AccessStamps`, `AccessWalks`), as a backend written outside Wow has in
 * 10.0: the guides promise that when an aggregate's attributes change its earlier streams are stamped again, "normally
 * within seconds" (`guide/event-queries.md`, `guide/ownership.md`), and an events query decides by them. On this log
 * the stamping subscription returns at once (`AccessStamping.stamp`: `stampsOf(type) ?: return`), nothing refuses the
 * node's start, and the earlier stream keeps the owner it was appended with for good.
 */
class R2AccessCapabilitiesTest {
    private val inner = InMemoryBackends(partitions = 4)

    /** The in-memory log behind a plain [EventLog]: every port of a log, neither access capability. */
    private val backends = BackendFactory { context ->
        val wow = inner.backends(context)
        wow.copyWith(log = object : EventLog by (wow.log as InMemoryEventLog) {})
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions().copyWith(handoff = HandoffOptions(probeInterval = 50.milliseconds)),
    )

    private val ref = CART.ref(TENANT, "c-1")
    private val log get() = inner.opened.getValue(SHOP).log

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun owned(owner: String) = AccessValues.ofStream(emptyMap(), Scope(owner))

    private suspend fun append(version: Int, owner: String) {
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
        log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    private suspend fun history() = log.read(ref).toList().map { it.access }

    @Test
    fun `on a log without the access capabilities, the start is refused or the history follows a change`(): Unit =
        runBlocking {
            node.start()
            // The owner changed at 2: the guides promise the stream before it follows within seconds.
            append(1, "alice")
            append(2, "bob")
            eventually { history() == listOf(owned("bob"), owned("bob")) }
        }
}
