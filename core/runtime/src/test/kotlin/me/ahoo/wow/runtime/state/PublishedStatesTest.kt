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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ORDER
import me.ahoo.wow.runtime.binding.Place
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Decision D133: the published states a context's state consumers share, and when they are evicted. */
class PublishedStatesTest {
    private val codec = EventCodec()
    private val time = TestTimeSource()
    private val alice = CART.ref(TENANT, "alice")
    private val bob = CART.ref(TENANT, "bob")

    private fun views(capacity: Int = 16, maxAge: kotlin.time.Duration = 60.seconds) = PublishedStates(
        codec,
        mapOf(SNAPSHOT to setOf(CART), PROJECTION to setOf(CART, ORDER)),
        PublishedStateOptions(capacity, maxAge),
        time,
    )

    private fun view(version: Int) = SerializedState(version, "{}")

    @Test
    fun `the executor's commits should be recorded for the types some reader reads`(): Unit = runBlocking {
        val kernel = Kernel(InMemoryEventLog(), codec = codec)
        val readers = PublishedStates(codec, mapOf(PROJECTION to setOf(CART)))
        readers.types.assert().containsExactly(CART)
        readers.readers.assert().isEqualTo(mapOf(PROJECTION to setOf(CART)))
        val cart = kernel.execute(cartModel(), Command("c-1", "r-1", alice, AddItems(listOf("apple"))))
        readers.record(cartModel(), cart as CommandOutcome.Committed)
        readers.get(alice, 1)!!.state.assert().contains("apple")
        val order = kernel.execute(orderModel(), Command("c-2", "r-2", ORDER.ref(TENANT, "o-1"), Place(listOf("a"))))
        readers.record(orderModel(), order as CommandOutcome.Committed)
        readers.size.assert().isEqualTo(1)
    }

    @Test
    fun `a pending view should be read only for its very append, then be a view every reader gets`(): Unit =
        runBlocking {
            val kernel = Kernel(InMemoryEventLog(), codec = codec)
            val views = views()
            val committed = kernel.execute(cartModel(), Command("c-1", "r-1", alice, AddItems(listOf("apple")))) {
                    stream, state ->
                views.expect(cartModel(), stream, state)
            } as CommandOutcome.Committed
            val stream = committed.stream
            views.get(alice, 1).assert().isNull()
            // Another append at the version, or this one at another, is not this very append.
            listOf(
                stream.copyWith(commitTime = stream.commitTime + 1),
                stream.copyWith(commandId = "c-2"),
                stream.copyWith(requestId = "r-2"),
                stream.copyWith(version = 2),
            ).forEach { views.get(it).assert().isNull() }
            val pending = views.get(stream)!!
            pending.state.assert().contains("apple")
            views.get(alice, 1).assert().isSameAs(pending)
            // Recorded once the append committed: the view stays as the reader got it.
            views.record(cartModel(), committed)
            views.get(alice, 1).assert().isSameAs(pending)
            views.get(stream).assert().isSameAs(pending)
        }

    @Test
    fun `a reader racing the executor's record should always find the view, pending or recorded`(): Unit =
        runBlocking {
            // Finding F-1: the record dropped the pending view and put the view under two holds of the lock, and a
            // reader looked the two up the same way; one in between found neither and read the store.
            val kernel = Kernel(InMemoryEventLog(), codec = codec)
            val committed = kernel.execute(cartModel(), Command("c-1", "r-1", alice, AddItems(listOf("apple"))))
                as CommandOutcome.Committed
            val state = CachedState(committed.state, 1)
            val views = PublishedStates(codec, mapOf(SNAPSHOT to setOf(CART), PROJECTION to setOf(CART)))
            val missed = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(RACES) { race ->
                    val ref = CART.ref(TENANT, "race-$race")
                    val outcome = CommandOutcome.Committed(
                        committed.stream.copyWith(ref = ref),
                        committed.position,
                        committed.state,
                        committed.results,
                        committed.deleted,
                        committed.tags,
                        committed.schedules,
                        committed.first,
                    )
                    views.expect(cartModel(), outcome.stream, state)
                    val start = CyclicBarrier(2)
                    val reader = pool.submit {
                        start.await()
                        if (views.get(outcome.stream) == null) {
                            missed.incrementAndGet()
                        }
                    }
                    val recorder = pool.submit {
                        start.await()
                        views.record(cartModel(), outcome)
                        views.forget(ref)
                    }
                    reader.get()
                    recorder.get()
                    views.pass(SNAPSHOT, ref, 2)
                    views.pass(PROJECTION, ref, 2)
                }
            } finally {
                pool.shutdownNow()
            }
            missed.get().assert().isEqualTo(0)
        }

    @Test
    fun `a batch's streams should each have a pending view, until committed or expected again`(): Unit =
        runBlocking {
            // A lane appending a batch (decision D273) expects one view per stream before any commits.
            val kernel = Kernel(InMemoryEventLog(), codec = codec)
            val views = views()
            val committed = (1..3).map { n ->
                kernel.execute(cartModel(), Command("c-$n", "r-$n", alice, AddItems(listOf("item-$n"))))
                    as CommandOutcome.Committed
            }
            committed.forEach { views.expect(cartModel(), it.stream, CachedState(it.state, it.stream.version)) }
            // The first committed: the views after it stay pending. (Reading a pending view makes it a view: the
            // reader holds its commit; so the later ones are read last.)
            views.record(cartModel(), committed[0])
            views.get(alice, 1).assert().isNotNull()
            views.get(alice, 2).assert().isNull()
            // The second expected again (decided again on a new state): it replaces itself and the one after it.
            views.expect(cartModel(), committed[1].stream, CachedState(committed[1].state, 2))
            views.get(committed[2].stream).assert().isNull()
            views.get(committed[1].stream).assert().isNotNull()
        }

    @Test
    fun `a pending view of another append should not be recorded, and forget should drop it`(): Unit = runBlocking {
        val kernel = Kernel(InMemoryEventLog(), codec = codec)
        val views = views()
        val pear = kernel.execute(cartModel(), Command("c-0", "r-0", bob, AddItems(listOf("pear"))))
            as CommandOutcome.Committed
        val apple = kernel.execute(cartModel(), Command("c-1", "r-1", alice, AddItems(listOf("apple"))))
            as CommandOutcome.Committed
        val state = CachedState(pear.state, 1)
        // The pending view of an attempt that did not commit: the commit is recorded from its own state.
        val other = apple.stream.copyWith(commitTime = apple.stream.commitTime - 1)
        views.expect(cartModel(), other, state)
        views.record(cartModel(), apple)
        views.get(alice, 1)!!.state.assert().contains("apple")
        views.get(other)!!.state.assert().contains("apple")
        // Forgotten, a pending view is gone.
        views.expect(cartModel(), pear.stream, state)
        views.forget(bob)
        views.get(pear.stream).assert().isNull()
        // Nothing is held for a type no reader reads.
        val unread = PublishedStates(codec, mapOf(PROJECTION to setOf(ORDER)))
        unread.expect(cartModel(), pear.stream, state)
        unread.get(pear.stream).assert().isNull()
        unread.forget(bob)
        unread.size.assert().isEqualTo(0)
    }

    @Test
    fun `a view should be kept until every reader of its type passed a later version`() {
        val views = views()
        views.put(alice, view(1))
        views.put(alice, view(2))
        views.put(alice, view(3))
        views.get(alice, 2).assert().isNotNull()
        views.floor(alice, 3)!!.version.assert().isEqualTo(2)
        views.floor(alice, 1).assert().isNull()
        views.pass(PROJECTION, alice, 3)
        views.size.assert().isEqualTo(3)
        views.pass(SNAPSHOT, alice, 2)
        // Both passed version 1; version 2 stays as the snapshot reader's base.
        views.get(alice, 1).assert().isNull()
        views.get(alice, 2).assert().isNotNull()
        views.pass(SNAPSHOT, alice, 3)
        views.size.assert().isEqualTo(1)
        views.get(alice, 3).assert().isNotNull()
        // A version every reader passed is not kept; one already held is kept as it is.
        views.put(alice, view(2))
        views.get(alice, 2).assert().isNull()
        val held = views.get(alice, 3)
        views.put(alice, view(3))
        views.get(alice, 3).assert().isSameAs(held)
    }

    @Test
    fun `a reader of another type, an unknown reader or an unknown aggregate should pass nothing`() {
        val views = views()
        views.put(alice, view(1))
        views.put(ORDER.ref(TENANT, "o-1"), view(1))
        views.pass(SNAPSHOT, ORDER.ref(TENANT, "o-1"), 5)
        views.pass("Unknown.reader", alice, 5)
        views.pass(PROJECTION, bob, 5)
        views.pass(PROJECTION, AggregateTypes.MAIL.ref(TENANT, "m"), 5)
        views.size.assert().isEqualTo(2)
        views.put(AggregateTypes.MAIL.ref(TENANT, "m"), view(1))
        views.size.assert().isEqualTo(2)
    }

    @Test
    fun `the views should be bounded by count, oldest first`() {
        val views = views(capacity = 2)
        views.put(alice, view(1))
        views.put(bob, view(1))
        views.put(alice, view(2))
        views.size.assert().isEqualTo(2)
        views.get(alice, 1).assert().isNull()
        views.get(bob, 1).assert().isNotNull()
        views.put(bob, view(2))
        views.put(alice, view(3))
        views.get(bob, 1).assert().isNull()
        views.get(alice, 2).assert().isNull()
        views.floor(alice, 3).assert().isNull()
    }

    @Test
    fun `the views should be bounded by the bytes of their states, oldest first, the last one kept`() {
        val views = PublishedStates(
            codec,
            mapOf(SNAPSHOT to setOf(CART)),
            PublishedStateOptions(capacity = 16, maxBytes = 40),
            time,
        )
        val state = "x".repeat(8)
        views.put(alice, SerializedState(1, state))
        views.put(bob, SerializedState(1, state))
        views.bytes.assert().isEqualTo(32L)
        views.put(alice, SerializedState(2, state))
        views.get(alice, 1).assert().isNull()
        views.bytes.assert().isEqualTo(32L)
        views.pass(SNAPSHOT, bob, 2)
        views.bytes.assert().isEqualTo(16L)
        views.put(bob, SerializedState(3, "y".repeat(100)))
        views.size.assert().isEqualTo(1)
        views.get(bob, 3).assert().isNotNull()
        views.bytes.assert().isEqualTo(200L)
    }

    @Test
    fun `the views should age out`() {
        val views = views(maxAge = 10.seconds)
        views.put(alice, view(1))
        time += 6.seconds
        views.put(bob, view(1))
        time += 6.seconds
        views.get(alice, 1).assert().isNull()
        views.get(bob, 1).assert().isNotNull()
        time += 6.seconds
        views.floor(bob, 2).assert().isNull()
        views.size.assert().isEqualTo(0)
    }

    @Test
    fun `the options and a view should be valid`() {
        assertThrownBy<IllegalArgumentException> { PublishedStateOptions(capacity = 0) }
        assertThrownBy<IllegalArgumentException> { PublishedStateOptions(maxAge = 0.seconds) }
        assertThrownBy<IllegalArgumentException> { PublishedStateOptions(maxBytes = 0) }
        assertThrownBy<IllegalArgumentException> { SerializedState(0, "{}") }
        view(3).toString().assert().isEqualTo("SerializedState(version=3)")
    }

    private object AggregateTypes {
        val MAIL = me.ahoo.wow.api.aggregate.AggregateType("shop", "mail")
    }

    private companion object {
        const val SNAPSHOT = "snapshot"
        const val PROJECTION = "View.onEvent"
        const val RACES = 20_000
    }
}
