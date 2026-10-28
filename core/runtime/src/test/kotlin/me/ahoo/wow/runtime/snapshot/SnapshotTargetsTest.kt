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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The snapshot targets of a context (decision D172): placements, primaries, subscriptions and the `snapshot` hop. */
class SnapshotTargetsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val a = InMemorySnapshotStore()
    private val b = InMemorySnapshotStore()

    private fun snapshot(type: AggregateType, id: String, version: Int = 1) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = version,
        schemaFingerprint = "f",
        state = "{}",
        firstEventTime = 0,
        lastEventTime = 0,
    )

    @Test
    fun `one store is every type's only target, written by the snapshot subscription`() {
        val targets = SnapshotTargets.single(a)
        targets.default.assert().isEqualTo(SnapshotTargets.DEFAULT_STORE)
        targets.placementOf(order).assert().isEqualTo(SnapshotPlacement(listOf(SnapshotTargets.DEFAULT_STORE)))
        targets.primary.assert().isSameAs(a)
        targets.primaryOf(order).assert().isSameAs(a)
        targets.subscriptionOf(SnapshotTargets.DEFAULT_STORE).assert().isEqualTo(Hop.SNAPSHOT_SUBSCRIPTION)
        targets.primarySubscriptionOf(order).assert().isEqualTo(Hop.SNAPSHOT_SUBSCRIPTION)
        targets.storesOf(listOf(order, cart)).assert()
            .isEqualTo(mapOf(SnapshotTargets.DEFAULT_STORE to setOf(order, cart)))
        targets.storesOf(emptyList()).assert().isEmpty()
    }

    @Test
    fun `each type is written to its targets and read from its primary`(): Unit = runBlocking {
        val targets = SnapshotTargets(
            "a",
            mapOf("a" to a, "b" to b),
            mapOf(order to SnapshotPlacement(listOf("a", "b"), primary = "b")),
        )
        targets.primaryOf(order).assert().isSameAs(b)
        targets.primaryOf(cart).assert().isSameAs(a)
        targets.subscriptionOf("b").assert().isEqualTo("snapshot:b")
        targets.primarySubscriptionOf(order).assert().isEqualTo("snapshot:b")
        targets.primarySubscriptionOf(cart).assert().isEqualTo("snapshot")
        targets.storesOf(listOf(order, cart)).assert().isEqualTo(mapOf("a" to setOf(order, cart), "b" to setOf(order)))
        targets.primary.save(snapshot(order, "o")).assert().isTrue()
        b.load(order.ref("tenant", "o")).assert().isNotNull()
        a.load(order.ref("tenant", "o")).assert().isNull()
        val moved = SnapshotTargets("a", mapOf("a" to a, "b" to b), fallback = SnapshotPlacement(listOf("b")))
        moved.primaryOf(cart).assert().isSameAs(b)
        moved.storesOf(listOf(cart)).assert().isEqualTo(mapOf("b" to setOf(cart)))
    }

    @Test
    fun `a placement and the targets name stores that exist`() {
        assertThrows<IllegalArgumentException> { SnapshotPlacement(emptyList()) }
        assertThrows<IllegalArgumentException> { SnapshotPlacement(listOf(" ")) }
        assertThrows<IllegalArgumentException> { SnapshotPlacement(listOf("a", "a")) }
        assertThrows<IllegalArgumentException> { SnapshotPlacement(listOf("a"), primary = "b") }
        assertThrows<IllegalArgumentException> { SnapshotTargets("x", mapOf("a" to a)) }
        assertThrows<IllegalArgumentException> {
            SnapshotTargets("a", mapOf("a" to a), mapOf(order to SnapshotPlacement(listOf("a", "z"))))
        }
    }

    @Test
    fun `the router hands every operation to the store of the type`(): Unit = runBlocking {
        val router = SnapshotStoreRouter(a, mapOf(order to b))
        SnapshotStoreRouter.of(a, mapOf(order to a)).assert().isSameAs(a)
        val saved = snapshot(order, "o", 2)
        router.save(saved).assert().isTrue()
        router.load(saved.ref).assert().isEqualTo(saved)
        router.rebuilds!!.rewrite(saved.copyWith(state = """{"x":1}""")).assert().isTrue()
        router.rebuilds!!.markStale(saved.ref).assert().isTrue()
        router.rebuilds!!.pending(order, 1, limit = 10).assert().containsExactly(saved.ref)
        router.rebuilds!!.claim(order, 2).assert().isEqualTo(SnapshotTable(2, rebuilding = true, backfilled = false))
        router.rebuilds!!.table(order).assert().isEqualTo(b.table(order))
        router.rebuilds!!.mark(order, 2, rebuilding = false, backfilled = true).assert().isEqualTo(SnapshotTable(2))
        a.table(order).assert().isNull()
        router.save(snapshot(cart, "c")).assert().isTrue()
        a.load(cart.ref("tenant", "c")).assert().isNotNull()
    }

    @Test
    fun `the snapshot hop is the primary target's subscription`(): Unit = runBlocking {
        val handled = mutableListOf<String>()
        val runtime = object : SubscriptionDirectory {
            override fun kindOf(context: String, subscription: String): FunctionKind? =
                FunctionKind.PROJECT.takeIf { subscription == "projector" }

            override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean {
                handled += subscription
                return true
            }
        }
        val targets = SnapshotTargets(
            "a",
            mapOf("a" to a, "b" to b),
            mapOf(order to SnapshotPlacement(listOf("a", "b"), primary = "b")),
        )
        val hops = SnapshotHops(runtime, "shop", targets, setOf(order))
        hops.kindOf("shop", Hop.SNAPSHOT_SUBSCRIPTION).assert().isEqualTo(FunctionKind.SNAPSHOT)
        hops.kindOf("other", Hop.SNAPSHOT_SUBSCRIPTION).assert().isNull()
        hops.kindOf("shop", "projector").assert().isEqualTo(FunctionKind.PROJECT)
        SnapshotHops(runtime, "shop", targets, emptySet()).kindOf("shop", Hop.SNAPSHOT_SUBSCRIPTION).assert().isNull()
        hops.covers(Hop.SNAPSHOT_SUBSCRIPTION, order).assert().isTrue()
        hops.covers(Hop.SNAPSHOT_SUBSCRIPTION, cart).assert().isFalse()
        hops.covers(Hop.SNAPSHOT_SUBSCRIPTION, AggregateType("other", "x")).assert().isFalse()
        hops.covers("projector", cart).assert().isTrue()
        hops.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, stream(order)).assert().isTrue()
        hops.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, stream(cart)).assert().isTrue()
        hops.hasHandled("projector", stream(order)).assert().isTrue()
        handled.assert().containsExactly("snapshot:b", Hop.SNAPSHOT_SUBSCRIPTION, "projector")
    }

    private fun stream(type: AggregateType) = EventStreamRecord(
        ref = type.ref("tenant", "x"),
        version = 1,
        commandId = "c",
        requestId = "r",
        commandType = "$type.Do",
        commitTime = 0,
        events = listOf(EventRecord("$type.Done", 1, "{}")),
        scope = Scope.NONE,
    )
}
