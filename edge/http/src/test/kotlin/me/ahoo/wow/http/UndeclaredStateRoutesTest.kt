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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * A state read over HTTP answers only what the node's state schema declares (#7243): the latest state, read from a
 * snapshot a newer node wrote with a property this node's schema does not know, is answered as the state at that
 * version replayed by this node is, which holds only what its own class writes.
 */
class UndeclaredStateRoutesTest {
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    /** The cart's Manifest with the state schema the compiler writes for [Cart]: a class, closed. */
    private val manifest = cartManifest().copyWith(
        defs = mapOf(
            "Cart" to mapOf(
                "type" to "object",
                "x-wow-class" to Cart::class.java.name,
                "properties" to mapOf("items" to mapOf("type" to "integer"), "note" to mapOf("type" to "string")),
            ),
        ),
    )

    @Test
    fun `the latest state from a snapshot holding an undeclared property is the state replayed at its version`() {
        val node = HttpNode(manifest = manifest)
        val codec = EventCodec()
        runBlocking {
            (1..2).forEach { version ->
                node.log.append(
                    EventStreamRecord(
                        ref = ref,
                        version = version,
                        commandId = "cmd-$version",
                        requestId = "req-$version",
                        commandType = "shop.cart.AddItem",
                        commitTime = 1000L * version,
                        events = listOf(codec.encode(cartModel, ItemAdded("apple", 1))),
                        scope = Scope("alice", null),
                    ),
                )
            }
            node.snapshots.save(
                codec.snapshotOf(
                    cartModel,
                    ref,
                    stateText = """{"items":2,"note":"","card":"s3cr3t","extra":{"pin":"s3cr3t"}}""",
                    version = 2,
                    scope = Scope("alice"),
                    firstEventTime = 1000L,
                    lastEventTime = 2000L,
                    access = emptyMap(),
                ),
            )
        }
        val latest = node.get("/shop/cart/c-1/state")
        latest.status.assert().isEqualTo(200)
        val replayed = node.get("/shop/cart/c-1/state?version=2")
        replayed.status.assert().isEqualTo(200)
        latest.body!!["state"].toString().assert().doesNotContain("s3cr3t")
            .isEqualTo("""{"items":2,"note":""}""")
        latest.body!!["state"].assert().isEqualTo(replayed.body!!["state"])
    }
}
