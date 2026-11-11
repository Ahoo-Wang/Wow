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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An idle tail's probe of the head: what other nodes appended found by it, and this node's own commit, seen in the head
 * before its append returns, waited for and taken from memory.
 */
class HandoffProbeTest : HandoffLogFixtures() {
    @Test
    fun `an idle tail finds what other nodes appended with a probe of the head`(): Unit = runBlocking {
        val log = handoff(probeInterval = 20.milliseconds)
        val (seen, job) = collect(log)
        awaitOpen(log)
        store.append(next(refs[0]))
        seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.IDLE)
        // Probes that find the head where the tail is read nothing.
        store.tails.get().assert().isEqualTo(1)
    }

    @Test
    fun `an idle probe that finds this node's own commit before its append returns should wait for its hand-over`(): Unit =
        runBlocking {
            // The commit shows in the head while its append is still on its way back (a store's answer in flight): the
            // probe must not read what the append is about to hand over.
            val gated = GateAfterCommit(store)
            val log = handoff(probeInterval = 20.milliseconds, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
            gated.committed.await()
            // A probe reads the head while the append is held, finds the commit there, and waits for its hand-over.
            val heads = store.heads.get()
            eventually { store.heads.get() > heads }
            gated.release.complete(Unit)
            held.await()
            seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
            job.cancelAndJoin()

            log.storeReads.assert().isEqualTo(0L)
            log.delivered.assert().isEqualTo(1L)
            reads.reasons.assert().isEmpty()
        }

    @Test
    fun `an idle probe whose head read sees this node's commit returned meanwhile should take it from memory`(): Unit =
        runBlocking {
            // X-19: the append commits, hands its entry over and returns while the probe reads the head, so the head
            // shows the commit and no append is in flight; only what was handed over says it is this node's.
            val gated = GateHead(store)
            val log = handoff(probeInterval = 20.milliseconds, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            gated.arm()
            gated.entered.await()
            log.append(next(refs[0])).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            gated.release.complete(Unit)
            seen.take(1).single().position.sequence.assert().isEqualTo(1L)
            job.cancelAndJoin()

            log.storeReads.assert().isEqualTo(0L)
            log.delivered.assert().isEqualTo(1L)
            reads.reasons.assert().isEmpty()
        }

    @Test
    fun `an entry handed over the instant the probe interval ends should be taken, never lost to the timeout`() =
        runTest {
            // On virtual time, the append runs first at the instant the tail's wait times out: the entry reaches the
            // waiting tail, and the timeout comes next. A wait that a timeout cancels may lose what it took, to a read
            // of the store; the tail's wait takes the entry or leaves it.
            val probe = 1.seconds
            val log = handoff(probeInterval = probe)
            backgroundScope.launch {
                delay(probe)
                log.append(next(refs[0]))
            }
            val seen = Channel<LogEntry>(Channel.UNLIMITED)
            backgroundScope.launch { log.tail(0, null).collect { seen.send(it) } }
            seen.receive().position.sequence.assert().isEqualTo(1L)

            log.storeReads.assert().isEqualTo(0L)
            log.delivered.assert().isEqualTo(1L)
            reads.reasons.assert().isEmpty()
        }

    @Test
    fun `an idle probe that waited for this node's own commit should read other nodes' entries past it at once`(): Unit =
        runBlocking {
            val gated = GateAfterCommit(store)
            val probe = 1.seconds
            val log = handoff(probeInterval = probe, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            // This node's commit (sequence 1) is held on its way back; another node appends sequence 2 meanwhile.
            val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
            gated.committed.await()
            val heads = store.heads.get()
            store.append(next(refs[1]))
            // The probe runs, finds the head at 2 and waits for this node's append.
            eventually { store.heads.get() > heads }
            gated.release.complete(Unit)
            held.await()
            // Both, well within a probe interval: the other node's entry is read right after the hand-over.
            withTimeout(probe / 2) { seen.take(2) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
            job.cancelAndJoin()

            log.delivered.assert().isEqualTo(1L)
            log.storeReads.assert().isEqualTo(1L)
            reads.reasons.assert().containsExactly(ObservedEvent.TailRead.Reason.IDLE)
        }
}
