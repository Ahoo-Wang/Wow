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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The routes read once more before a command whose route names no owner runs without the lease (RouteLookup). */
class RouteLookupTest {
    private val time = TestTimeSource()
    private val owner = Member("service", "node-2", "127.0.0.1:19091", Long.MAX_VALUE)

    /** The routes as the lookup reads them: [named] once a read made them so. */
    private var named: Member? = null
    private val reads = AtomicInteger()

    private fun lookup(read: suspend () -> Unit = { reads.incrementAndGet() }) = RouteLookup(
        "group",
        read,
        { named },
        backOff = 1.seconds,
        timeSource = time,
    )

    @Test
    fun `an owner the read names is the one the command goes to`(): Unit = runBlocking {
        val lookup = lookup {
            reads.incrementAndGet()
            named = owner
        }
        val found = lookup.ownerOf(7)
        listOf(found.owner, found.read, found.failed).assert().containsExactly(owner, true, false)
        found.observed.assert().isEqualTo(ObservedEvent.RouteLookedUp.Outcome.OWNER)
        listOf(lookup.reads, lookup.forwards).assert().containsExactly(1L, 1L)
    }

    @Test
    fun `a read that names no owner leaves the command to run here, as before`(): Unit = runBlocking {
        val lookup = lookup()
        val found = lookup.ownerOf(7)
        listOf(found.owner, found.read, found.failed).assert().containsExactly(null, true, false)
        found.observed.assert().isEqualTo(ObservedEvent.RouteLookedUp.Outcome.NONE)
        listOf(lookup.reads, lookup.forwards).assert().containsExactly(1L, 0L)
    }

    @Test
    fun `a read within the interval of the last takes it, and one after reads again`(): Unit = runBlocking {
        val lookup = lookup()
        lookup.ownerOf(7)
        time += 50.milliseconds
        lookup.ownerOf(8).read.assert().isFalse()
        reads.get().assert().isEqualTo(1)
        time += RouteLookup.INTERVAL
        lookup.ownerOf(8).read.assert().isTrue()
        reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `an unreadable store costs one timeout, then no read for the back-off`(): Unit = runBlocking {
        val lookup = lookup {
            reads.incrementAndGet()
            awaitCancellation()
        }
        val found = lookup.ownerOf(7)
        listOf(found.owner, found.read, found.failed).assert().containsExactly(null, true, true)
        found.observed.assert().isEqualTo(ObservedEvent.RouteLookedUp.Outcome.FAILED)
        // Within the back-off, no read: the command runs without the lease at once.
        time += 500.milliseconds
        lookup.ownerOf(7).let { listOf(it.owner, it.read, it.failed) }.assert().containsExactly(null, false, true)
        reads.get().assert().isEqualTo(1)
        time += 1.seconds
        lookup.ownerOf(7).read.assert().isTrue()
        reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `a store that throws is unreadable as well`(): Unit = runBlocking {
        val lookup = lookup { error("The lease store is down.") }
        lookup.ownerOf(7).let { listOf(it.owner, it.failed) }.assert().containsExactly(null, true)
        lookup.ownerOf(7).read.assert().isFalse()
    }

    @Test
    fun `a caller cancelled during the read leaves no back-off behind`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val lookup = lookup {
            reads.incrementAndGet()
            entered.complete(Unit)
            awaitCancellation()
        }
        val asking = async(Dispatchers.Default) { lookup.ownerOf(7) }
        entered.await()
        asking.cancel()
        assertThrows<CancellationException> { asking.await() }
        // Nothing was learned of the store: the next command reads it, rather than back off (here it times out).
        lookup.ownerOf(7).let { listOf(it.read, it.failed) }.assert().containsExactly(true, true)
        reads.get().assert().isEqualTo(2)
    }

    @Test
    fun `two commands at once make one read, and both take what it found`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lookup = lookup {
            reads.incrementAndGet()
            entered.complete(Unit)
            release.await()
            named = owner
        }
        val first = async(Dispatchers.Default) { lookup.ownerOf(7) }
        entered.await()
        val second = async(Dispatchers.Default) { lookup.ownerOf(9) }
        release.complete(Unit)
        val (a, b) = listOf(first, second).awaitAll()
        listOf(a.owner, b.owner).assert().containsExactly(owner, owner)
        listOf(a.read, b.read).assert().containsExactly(true, false)
        reads.get().assert().isEqualTo(1)
        lookup.forwards.assert().isEqualTo(2L)
    }
}
