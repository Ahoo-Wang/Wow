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

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a tail takes from memory and what it reads from the store: a node's own commits, other nodes' gaps, read-backs,
 * an unknown outcome, a tail reopened, every open tail, other outcomes, and the options.
 */
class HandoffLogTest : HandoffLogFixtures() {
    @Test
    fun `a node's own commits reach an open tail from memory, without reading the store`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        val appended = (1..20).map { n ->
            val stream = next(refs[n % refs.size])
            log.append(stream, transport(n))
            stream
        }
        val entries = seen.take(20)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo((1L..20L).toList())
        entries.map { it.stream }.assert().isEqualTo(appended)
        entries.map { it.decodedTransport?.trace }.assert()
            .isEqualTo((1..20).map { mapOf("traceparent" to "trace-$it") })
        store.tails.get().assert().isEqualTo(0)
        log.storeReads.assert().isEqualTo(0L)
        log.delivered.assert().isEqualTo(20L)
        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `an entry handed over is what a read of the store gives`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        log.appendAll(listOf(next(refs[0]) to transport(1), next(refs[1]) to null))
        val handed = seen.take(2)
        job.cancelAndJoin()

        handed.assert().isEqualTo(withTimeout(10.seconds) { store.tail(0).takeTwo() })
    }

    @Test
    fun `a tail opened behind the head reads the store up to it, then goes on from memory`(): Unit = runBlocking {
        repeat(5) { store.append(next(refs[it])) }
        val log = handoff()
        val (seen, job) = collect(log, after = 2)
        seen.take(3).map { it.position.sequence }.assert().isEqualTo(listOf(3L, 4L, 5L))
        repeat(5) { log.append(next(refs[it])) }
        seen.take(5).map { it.position.sequence }.assert().isEqualTo((6L..10L).toList())
        job.cancelAndJoin()

        store.tails.get().assert().isEqualTo(1)
        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.START)
        log.delivered.assert().isEqualTo(5L)
    }

    @Test
    fun `another node's append leaves a gap that is read from the store, in sequence order`(): Unit = runBlocking {
        val log = handoff(gapWait = 20.milliseconds)
        val (seen, job) = collect(log)
        awaitOpen(log)
        log.append(next(refs[0]))
        // Another node appends to the partition during a hand-off (decision D71): this one hands nothing over.
        store.append(next(refs[1]))
        log.append(next(refs[2]))
        log.append(next(refs[3]))
        val entries = seen.take(4)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L, 4L))
        entries[1].stream.ref.assert().isEqualTo(refs[1])
        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
    }

    @Test
    fun `each entry a tail reads from the store is read back, none it took from memory`(): Unit = runBlocking {
        val readBack = CopyOnWriteArrayList<LogEntry>()
        val log = handoff(gapWait = 20.milliseconds, readBack = { readBack += it })
        val (seen, job) = collect(log)
        awaitOpen(log)
        log.append(next(refs[0]))
        seen.take(1)
        // Another node's commit, which no hand-over brings: the gap it leaves reads the store from it on.
        val other = next(refs[1])
        store.append(other)
        log.append(next(refs[2]))
        seen.take(2)
        log.append(next(refs[3]))
        seen.take(1)
        job.cancelAndJoin()

        readBack.map { it.position.sequence }.assert().isEqualTo(listOf(2L, 3L))
        readBack.first().stream.assert().isEqualTo(other)
        log.delivered.assert().isEqualTo(2L)
    }

    @Test
    fun `a commit whose outcome was unknown is read from the store when the next commit leaves a gap`(): Unit =
        runBlocking {
            val unknown = UnknownOnce(store)
            val log = handoff(gapWait = 20.milliseconds, log = unknown)
            val (seen, job) = collect(log)
            awaitOpen(log)
            unknown.next = true
            log.append(next(refs[0])).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            log.append(next(refs[1]))
            val entries = seen.take(2)
            job.cancelAndJoin()

            entries.map { it.stream.ref }.assert().isEqualTo(listOf(refs[0], refs[1]))
            reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.GAP)
        }

    @Test
    fun `a tail closed and opened again, as a partition handed off and acquired, reads only what it missed`(): Unit =
        runBlocking {
            val log = handoff()
            val (first, job) = collect(log)
            awaitOpen(log)
            repeat(3) { log.append(next(refs[it])) }
            val last = first.take(3).last().position.sequence
            job.cancelAndJoin()
            // Appended while no tail is open here: no one to hand it to.
            repeat(2) { log.append(next(refs[it])) }

            val (again, rejoined) = collect(log, after = last)
            again.take(2).map { it.position.sequence }.assert().isEqualTo(listOf(4L, 5L))
            log.append(next(refs[5]))
            again.take(1).single().position.sequence.assert().isEqualTo(6L)
            rejoined.cancelAndJoin()

            reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.START)
            store.tails.get().assert().isEqualTo(1)
        }

    @Test
    fun `every open tail of the partition gets each commit, those of other partitions none`(): Unit = runBlocking {
        val log = handoff()
        val (one, first) = collect(log)
        val (two, second) = collect(log)
        awaitOpen(log, tails = 2)
        val other = generateSequence(0) { it + 1 }.map { type.ref("tenant", "x-$it") }
            .first { Partitioner.partitionOf(it, PARTITIONS) == 1 }
        log.append(next(other))
        log.append(next(refs[0]))
        one.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        two.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        first.cancelAndJoin()
        second.cancelAndJoin()

        store.tails.get().assert().isEqualTo(0)
    }

    @Test
    fun `outcomes other than committed hand nothing over`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        val stream = next(refs[0])
        log.append(stream)
        log.append(stream).assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
        log.append(stream.copyWith(requestId = "other")).assert().isInstanceOf(AppendOutcome.Conflict::class.java)
        log.append(next(refs[0]))
        seen.take(2).map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        job.cancelAndJoin()

        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `a tail of a partition the log does not have is refused`() {
        assertThrows<IllegalArgumentException> { handoff().tail(PARTITIONS, null) }
    }

    @Test
    fun `options are checked and compared by value`() {
        assertThrows<IllegalArgumentException> { HandoffOptions(capacity = 0) }
        assertThrows<IllegalArgumentException> { HandoffOptions(gapWait = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { HandoffOptions(probeInterval = Duration.ZERO) }
        HandoffOptions().assert().isEqualTo(HandoffOptions())
        HandoffOptions().hashCode().assert().isEqualTo(HandoffOptions().hashCode())
        HandoffOptions(capacity = 1).assert().isNotEqualTo(HandoffOptions())
        HandoffOptions().toString().assert().contains("capacity=1024")
    }
}
