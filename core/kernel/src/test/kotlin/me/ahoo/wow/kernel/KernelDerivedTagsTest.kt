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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TagsApplied
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import org.junit.jupiter.api.Test

data class Ship(val country: String)
data class Move(val country: String?)
data class Label(val label: String)
data class StopDeriving(val reason: String = "")

data class Shipped(val country: String)
data class Moved(val country: String?)
data class Labeled(val label: String)
data class DerivingStopped(val reason: String = "")

/** A state whose tags follow its country and labels, until it stops deriving them. */
class ShipmentState {
    var country: String? = null
    var labels: MutableSet<String> = linkedSetOf()
    var derives: Boolean = true

    /** Every key it owns, with no value for none: a `Set` of labels, a list of at most one country. */
    fun tags(): Map<String, Collection<String>> =
        if (derives) mapOf("country" to listOfNotNull(country), "label" to labels) else emptyMap()
}

val shipmentModel = aggregate<ShipmentState>("example", "shipment") {
    initial { ShipmentState() }
    decide<Ship>(Creation.REQUIRED) { _, command -> Decision.events(Shipped(command.country)) }
    decide<Move> { _, command -> Decision.events(Moved(command.country)) }
    decide<Label> { _, command -> Decision.events(Labeled(command.label)) }
    decide<StopDeriving> { _, _ -> Decision.events(DerivingStopped()) }
    evolve<Shipped> { country = it.country }
    evolve<Moved> { country = it.country }
    evolve<Labeled> { labels += it.label }
    evolve<DerivingStopped> { derives = false }
    tags { it.tags() }
}

/** Tags derived from the state (`@Tags`, decision D220): derived keys override the applied tags per key. */
class KernelDerivedTagsTest {
    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val ref = shipmentModel.type.ref("tenant", "shipment-1")
    private var sequence = 0

    /** No state cache: every command loads from the log, or from the snapshot when there is one. */
    private val kernel = Kernel(log, codec, snapshots = snapshots)

    private suspend fun send(body: Any, kernel: Kernel = this.kernel): CommandOutcome =
        kernel.execute(shipmentModel, Command("c-${++sequence}", "r-$sequence", ref, body))

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private suspend fun version(): Int = log.read(ref).toList().last().version

    /** The aggregate's head, replayed from the log alone. */
    private suspend fun replayed(): CachedState = Kernel(log, codec).stateAt(shipmentModel, ref, version())!!

    @Test
    fun `the derived keys join the applied tags and override them per key`(): Unit = runTest {
        send(Ship("CN")).committed().tags.assert().isEqualTo(mapOf("country" to listOf("CN")))

        val applied = send(ApplyTags(mapOf("country" to listOf("XX"), "region" to listOf("eu")))).committed()

        applied.tags.assert().isEqualTo(mapOf("country" to listOf("CN"), "region" to listOf("eu")))
        // The event keeps what the command applied; the derived key overrides it whenever it is read.
        codec.decode(shipmentModel, applied.stream.events.single())
            .assert().isEqualTo(TagsApplied(mapOf("country" to listOf("XX"), "region" to listOf("eu"))))
        replayed().tags.assert().isEqualTo(applied.tags)
    }

    @Test
    fun `a state change replaces the derived values instead of adding to them`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("region" to listOf("eu"))))

        val moved = send(Move("US")).committed()

        moved.tags.assert().isEqualTo(mapOf("country" to listOf("US"), "region" to listOf("eu")))
        replayed().tags.assert().isEqualTo(moved.tags)
    }

    @Test
    fun `a derived key without values removes the key, the applied one too`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("country" to listOf("XX"), "region" to listOf("eu"))))

        val moved = send(Move(null)).committed()

        moved.tags.assert().isEqualTo(mapOf("region" to listOf("eu")))
        replayed().tags.assert().isEqualTo(moved.tags)
    }

    @Test
    fun `the derived values are copied, so a cached mutable state evolving further does not change them`(): Unit =
        runTest {
            val cached = Kernel(log, codec, states = MapStateCache())
            send(Ship("CN"), cached)

            val first = send(Label("fragile"), cached).committed()
            val second = send(Label("cold"), cached).committed()

            first.tags["label"].assert().isEqualTo(listOf("fragile"))
            second.tags["label"].assert().isEqualTo(listOf("fragile", "cold"))
        }

    @Test
    fun `a snapshot's stored derived values are derived anew, with or without a tail`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("region" to listOf("eu"))))
        val head = replayed()
        // Stored tags as a snapshot writes them, with a stale derived value: the state, not the stored value, decides.
        val stale = mapOf("country" to listOf("STALE"), "label" to listOf("old"), "region" to listOf("eu"))
        snapshots.save(
            codec.snapshotOf(
                shipmentModel,
                ref,
                head.state as ShipmentState,
                head.version,
                head.scope,
                firstEventTime = 0,
                lastEventTime = 0,
                tags = stale,
            ),
        )
        val expected = mapOf("country" to listOf("CN"), "region" to listOf("eu"))

        kernel.stateAt(shipmentModel, ref, head.version, snapshots.load(ref))!!.tags.assert().isEqualTo(expected)
        // A command decided on the snapshot alone, with no tail: an ApplyTags of the same tags changes nothing.
        send(ApplyTags(mapOf("region" to listOf("eu")))).assert().isEqualTo(CommandOutcome.Unchanged(head.version))

        val moved = send(Move("US")).committed()

        moved.tags.assert().isEqualTo(mapOf("country" to listOf("US"), "region" to listOf("eu")))
        replayed().tags.assert().isEqualTo(moved.tags)
    }

    @Test
    fun `a handed-over base at the version asked for gets its derived keys`(): Unit = runTest {
        send(Ship("CN"))
        val head = replayed()
        val base = CachedState(head.state, head.version, head.scope, tags = emptyMap())

        kernel.stateAt(shipmentModel, ref, head.version, base)!!.tags
            .assert().isEqualTo(mapOf("country" to listOf("CN")))
    }

    @Test
    fun `an aggregate not created yet derives no tags`(): Unit = runTest {
        val initial = ShipmentState().apply { country = "CN" }

        kernel.stateAt(shipmentModel, ref, 0, CachedState(initial, 0))!!.tags.assert().isEmpty()
    }

    @Test
    fun `a key no longer derived keeps its last derived value until ApplyTags leaves it out`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("region" to listOf("eu"))))

        val stopped = send(StopDeriving()).committed()

        stopped.tags.assert().isEqualTo(mapOf("country" to listOf("CN"), "region" to listOf("eu")))
        replayed().tags.assert().isEqualTo(stopped.tags)
        send(Move("US")).committed().tags.assert().isEqualTo(stopped.tags)

        val cleared = send(ApplyTags(mapOf("region" to listOf("eu")))).committed()

        cleared.tags.assert().isEqualTo(mapOf("region" to listOf("eu")))
        replayed().tags.assert().isEqualTo(cleared.tags)
    }

    @Test
    fun `ApplyTags is a no-op exactly when the tags it would leave are the aggregate's`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("region" to listOf("eu")))).committed()
        val version = version()

        send(ApplyTags(mapOf("region" to listOf("eu")))).assert().isEqualTo(CommandOutcome.Unchanged(version))
        // Applying a derived key changes nothing the aggregate shows: its derived value overrides it.
        send(ApplyTags(mapOf("region" to listOf("eu"), "country" to listOf("XX"))))
            .assert().isEqualTo(CommandOutcome.Unchanged(version))

        // Clearing the applied tags commits, though the aggregate keeps a derived tag; clearing them again does not.
        val cleared = send(ApplyTags(emptyMap())).committed()

        cleared.stream.events.map { it.name }.assert().containsExactly(Lifecycle.TAGS_APPLIED)
        cleared.tags.assert().isEqualTo(mapOf("country" to listOf("CN")))
        send(ApplyTags(emptyMap())).assert().isEqualTo(CommandOutcome.Unchanged(version + 1))
        send(ApplyTags(mapOf("tier" to listOf("gold")))).committed().tags
            .assert().isEqualTo(mapOf("country" to listOf("CN"), "tier" to listOf("gold")))
    }

    @Test
    fun `a trace carries the tags after each commit`(): Unit = runTest {
        send(Ship("CN"))
        send(ApplyTags(mapOf("region" to listOf("eu"))))
        send(Move("US"))

        kernel.trace(shipmentModel, ref, 1, 3, 10).map { it.tags }.assert().containsExactly(
            mapOf("country" to listOf("CN")),
            mapOf("country" to listOf("CN"), "region" to listOf("eu")),
            mapOf("country" to listOf("US"), "region" to listOf("eu")),
        )
    }

    @Test
    fun `a model without a tags function keeps the applied tags as they are`() {
        cartModel.tagsOf(CartState("cart-1"), mapOf("region" to listOf("eu")))
            .assert().isEqualTo(mapOf("region" to listOf("eu")))
    }

    @Test
    fun `a model declares at most one tags function`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<ShipmentState>("example", "shipment") {
                initial { ShipmentState() }
                tags { it.tags() }
                tags { emptyMap() }
            }
        }
    }

    /** Keeps every state put, as a node's cache does: a mutable state then evolves in place across commands. */
    private class MapStateCache : StateCache {
        private val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }
}
