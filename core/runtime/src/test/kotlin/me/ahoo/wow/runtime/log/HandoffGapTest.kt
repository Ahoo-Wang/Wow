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

package me.ahoo.wow.runtime.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Commits handed over out of order, a tail too slow for its hand-overs, a pending run past the capacity, a late append,
 * and the gap wait: read at once when no append of this node can fill it, else capped.
 */
class HandoffGapTest : HandoffLogFixtures() {
    @Test
    fun `commits handed over out of order are delivered in sequence order from memory`(): Unit = runBlocking {
        // Appends without a queue run side by side: the first commits, the second commits and returns before it.
        val held = HoldFirst(store)
        val log = handoff(gapWait = 5.seconds, log = held)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val first = async(Dispatchers.Default) { log.append(next(refs[0])) }
        held.committed.await()
        log.append(next(refs[1]))
        held.release.complete(Unit)
        first.await()
        val entries = seen.take(2)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        store.tails.get().assert().isEqualTo(0)
        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `a tail too slow for its hand-overs drops them and reads the store, never holding up an append`(): Unit =
        runBlocking {
            val log = handoff(capacity = 2)
            val gate = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<Long>()
            val job = launch(Dispatchers.Default) {
                log.tail(0).collect {
                    gate.await()
                    seen += it.position.sequence
                }
            }
            awaitOpen(log)
            // The tail takes the first and waits: the appends complete all the same.
            withTimeout(10.seconds) { repeat(10) { log.append(next(refs[it % refs.size])) } }
            gate.complete(Unit)
            eventually { seen.size >= 10 }
            job.cancelAndJoin()

            seen.assert().isEqualTo((1L..10L).toList())
            reads.reasons.assert().contains(ObservedEvent.TailRead.Reason.OVERFLOW)
        }

    @Test
    fun `a pending run longer than the capacity overflows to the store`(): Unit = runBlocking {
        val gated = GateFirst(store)
        val log = handoff(capacity = 2, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        // An append of this node in flight, not committed yet: the tail waits for it while the gap lasts.
        val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
        gated.entered.await()
        store.append(next(refs[1]))
        // Three entries past the missing one: more than the tail holds while it waits for the gap to fill.
        repeat(3) { log.append(next(refs[it + 2])) }
        seen.take(4).map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L, 4L))
        gated.release.complete(Unit)
        held.await()
        seen.take(1).single().position.sequence.assert().isEqualTo(5L)
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.OVERFLOW)
    }

    @Test
    fun `a commit whose append returns long after the next ones is still delivered from memory`(): Unit =
        runBlocking {
            // A pause of the process (a GC, a starved CPU) holds an append's return past any short timer (OV-2):
            // the tail waits for the append itself, not the clock.
            val held = HoldFirst(store)
            val log = handoff(gapWait = 1.seconds, log = held)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val first = async(Dispatchers.Default) { log.append(next(refs[0])) }
            held.committed.await()
            repeat(20) { log.append(next(refs[it % (refs.size - 1) + 1])) }
            delay(400)
            held.release.complete(Unit)
            first.await()
            seen.take(21).map { it.position.sequence }.assert().isEqualTo((1L..21L).toList())
            job.cancelAndJoin()

            reads.reasons.assert().isEmpty()
            store.tails.get().assert().isEqualTo(0)
        }

    @Test
    fun `a gap no append of this node can fill is read from the store at once, without the gap wait`(): Unit =
        runBlocking {
            val log = handoff(gapWait = 30.seconds)
            val (seen, job) = collect(log)
            awaitOpen(log)
            store.append(next(refs[0]))
            log.append(next(refs[1]))
            withTimeout(5.seconds) { seen.take(2) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
            job.cancelAndJoin()

            reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
        }

    @Test
    fun `a gap is read at once even when the append after it closes only once the tail waits`(): Unit = runBlocking {
        // The tail may take an append's entry before that append marks itself returned: its return wakes the tail.
        repeat(ROUNDS) { round ->
            val store = InMemoryEventLog(PARTITIONS)
            val log = HandoffLog(store, "bank", HandoffOptions(gapWait = 30.seconds))
            val (seen, job) = collect(log)
            delay(2)
            store.append(next(refs[0]).copyWith(version = 1, requestId = "other-$round"))
            log.append(next(refs[1]).copyWith(version = 1, requestId = "own-$round"))
            withTimeout(5.seconds) { List(2) { seen.receive() } }.map { it.position.sequence }
                .assert().isEqualTo(listOf(1L, 2L))
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a gap waits for an append of this node in flight, and is read once it returns without it`(): Unit =
        runBlocking {
            val gated = GateFirst(store)
            val log = handoff(gapWait = 30.seconds, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
            gated.entered.await()
            store.append(next(refs[1]))
            log.append(next(refs[2]))
            delay(200)
            // The append in flight may still hand the missing sequence over: nothing read yet.
            reads.reasons.assert().isEmpty()
            gated.release.complete(Unit)
            held.await()
            withTimeout(5.seconds) { seen.take(3) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L))
            job.cancelAndJoin()

            reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
        }

    @Test
    fun `a gap is capped by the gap wait while an append of this node does not return`(): Unit = runBlocking {
        val gated = GateFirst(store)
        val log = handoff(gapWait = 50.milliseconds, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
        gated.entered.await()
        store.append(next(refs[1]))
        log.append(next(refs[2]))
        withTimeout(5.seconds) { seen.take(2) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
        gated.release.complete(Unit)
        held.await()
        job.cancelAndJoin()
    }

    @Test
    fun `a group whose outcomes are not all commits wakes a tail waiting on a gap`(): Unit = runBlocking {
        val gated = GateFirst(store, armed = false)
        val log = handoff(gapWait = 30.seconds, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val stream = next(refs[0])
        log.append(stream)
        seen.take(1)
        gated.arm()
        // A group held in flight, then answered with a duplicate and a commit, while another node leaves a gap.
        val group = async(Dispatchers.Default) { log.appendAll(listOf(Append.of(stream), Append.of(next(refs[1])))) }
        gated.entered.await()
        store.append(next(refs[2]))
        log.append(next(refs[3]))
        gated.release.complete(Unit)
        group.await().map { it::class }.assert()
            .containsExactly(AppendOutcome.AlreadyCommitted::class, AppendOutcome.Committed::class)
        withTimeout(5.seconds) { seen.take(3) }.map { it.position.sequence }.assert().isEqualTo(listOf(2L, 3L, 4L))
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
    }
}
