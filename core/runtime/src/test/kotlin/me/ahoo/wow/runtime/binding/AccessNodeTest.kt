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
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The access stamping of a running node, its log behind the node's tracking: `wow.access` follows a change over the
 * history, the walk claims each type, and a stream without attributes after the walk's heads is stamped with its
 * aggregate's current ones.
 */
class AccessNodeTest {
    private val backends = InMemoryBackends(partitions = 4)

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
    )

    private val ref = CART.ref(TENANT, "c-1")
    private val log get() = backends.opened.getValue(SHOP).log

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun owned(owner: String) = AccessValues.ofStream(emptyMap(), Scope(owner))

    /** Appends the stream of [ref] at [version], of [owner], as a node wrote it: with [access], or none. */
    private suspend fun append(version: Int, owner: String, access: Map<String, Any?>?) {
        val stream = EventStreamRecord(
            ref = ref,
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "shop.cart.AddItems",
            commitTime = System.currentTimeMillis(),
            events = listOf(EventRecord("shop.cart.ItemsAdded", 1, """{"items":["item-$version"]}""")),
            scope = Scope(owner),
            access = access,
        )
        log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    private suspend fun history() = log.read(ref).toList().map { it.access }

    @Test
    fun `a running node follows a change over the history, and stamps an unstamped stream after the walk's heads`(): Unit =
        runBlocking {
            node.start()
            // The owner changed at 2: the stream before it follows.
            append(1, "alice", owned("alice"))
            append(2, "bob", owned("bob"))
            eventually { history() == listOf(owned("bob"), owned("bob")) }
            // The first run after the type's first append claims its walk, whose heads reach both.
            eventually { node.context(SHOP).accessWalking?.walks()?.get(CART)?.completed == true }
            history().assert().isEqualTo(listOf(owned("bob"), owned("bob")))
            // A node of an earlier version, still running, appends without them: its aggregate is stamped whole.
            append(3, "carol", null)
            eventually { history() == List(3) { owned("carol") } }
        }
}
