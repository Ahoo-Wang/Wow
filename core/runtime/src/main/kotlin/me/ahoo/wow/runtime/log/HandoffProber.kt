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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent.TailRead.Reason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The probes of one [HandoffLog]'s tails for the entries other nodes appended, which no hand-over brings them: one
 * call of the log for every tail of the node at once, rather than a read of a partition's head a tail and a probe
 * interval (#6702).
 *
 * - **Every half [HandoffOptions.probeInterval]**, it asks the log which partitions another writer appeared in since
 *   it last asked ([EventLog.appendedElsewhere]), for every open tail's partition at its cursor. Each tail of a
 *   partition named owes a read of the store, which it makes once it has been idle half a probe interval
 *   ([Reason.IDLE]): a tail this node's own commits keep busy pays nothing, as before. So an entry another node appends
 *   through a block or reservation this node has not seen is read within [HandoffOptions.probeInterval] of a pause.
 * - **Every [HandoffOptions.backstopInterval]**, it also reads the exact heads of those partitions ([EventLog.heads])
 *   and wakes each tail idle for a probe interval whose head is past its cursor, though the log did not name its
 *   partition ([Reason.BACKSTOP]): what the log's answer may leave out (on MongoDB, a node that goes on appending into
 *   a block already seen, after the partition moved and the tails' scheduled probes ended) is read within it. Its tail
 *   logs a warning when that read brings entries.
 *
 * It is the one caller of its log's [EventLog.appendedElsewhere], and asks about every open tail's partition at each
 * round, a busy tail's too: a store that answers from what it remembers forgets a partition a call leaves out.
 *
 * A read owed is kept until the tail is woken for it, and a wake until the tail takes it, so no report is lost; a
 * tail whose read of the store fails ends, and its next one reads the store up to the head as it opens. It runs while
 * the log has tails open, on [scope], and stops with the last.
 */
internal class HandoffProber(
    private val log: EventLog,
    private val context: String,
    private val options: HandoffOptions,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    /** A tail as the prober sees it. */
    interface Probed {
        val partition: Int

        /** The last sequence the tail delivered. */
        val cursor: Long

        /** How long since memory or the store last brought the tail an entry. */
        val idleFor: Duration

        /** Whether the probe named the tail's partition and the tail has not been woken for it yet. */
        var owed: Boolean

        /** Asks the tail to read the store, for [reason]; it reads once, whatever the wakes before it does. */
        fun wake(reason: Reason)
    }

    private val tails = ConcurrentHashMap.newKeySet<Probed>()
    private val running = AtomicBoolean()

    /** The rounds it made, and the backstop reads among them: for the tests. */
    @Volatile
    var rounds: Long = 0
        private set

    fun register(tail: Probed) {
        tails += tail
        start()
    }

    fun unregister(tail: Probed) {
        tails -= tail
    }

    private fun start() {
        if (tails.isNotEmpty() && running.compareAndSet(false, true)) {
            scope.launch { loop() }
        }
    }

    private suspend fun loop() {
        var backstop = time.markNow()
        try {
            while (tails.isNotEmpty()) {
                delay(options.probeInterval / 2)
                val open = tails.toList()
                if (open.isEmpty()) break
                val due = backstop.elapsedNow() >= options.backstopInterval
                round(open, due)
                if (due) backstop = time.markNow()
                rounds++
            }
        } finally {
            running.set(false)
            // A tail registered as the loop ended starts it again.
            start()
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed round is the next one's to do: the store may be down a while.
    private suspend fun round(open: List<Probed>, backstop: Boolean) {
        val cursors = open.groupBy { it.partition }.mapValues { (_, it) -> it.minOf { tail -> tail.cursor } }
        val named = try {
            log.appendedElsewhere(cursors)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug(failure) { "The probe of the tails of $context failed; the next round asks again." }
            return
        }
        open.filter { it.partition in named }.forEach { it.owed = true }
        open.filter { it.owed && it.idleFor >= options.probeInterval / 2 }.forEach {
            it.owed = false
            it.wake(Reason.IDLE)
        }
        if (!backstop) return
        val heads = try {
            log.heads(cursors.keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug(failure) { "The backstop read of the heads of $context failed; the next one reads again." }
            return
        }
        open.filter { tail ->
            tail.partition !in named && tail.idleFor >= options.probeInterval &&
                (heads[tail.partition] ?: 0L) > tail.cursor
        }.forEach { it.wake(Reason.BACKSTOP) }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
