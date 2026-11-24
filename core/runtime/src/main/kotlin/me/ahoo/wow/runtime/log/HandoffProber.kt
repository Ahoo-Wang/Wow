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
import kotlin.time.TimeMark
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
 * These are one node's calls, for its own tails: per log, two probes a second and a read of the heads every
 * [HandoffOptions.backstopInterval] with the defaults, whatever the node count, so a service's calls grow with its
 * nodes.
 *
 * It is the one caller of its log's [EventLog.appendedElsewhere], and asks about every open tail's partition at each
 * round, a busy tail's too: a store that answers from what it remembers forgets a partition a call leaves out.
 *
 * A read owed is kept until the tail is woken for it, and a wake until the tail takes it, so no report is lost: the
 * prober keeps the set of tails owed a read ([owed]), and each tail its wake. A tail whose read of the store fails
 * ends, and its next one reads the store up to the head as it opens. A call of the log that fails is made again at the
 * next round, the backstop's included: the prober logs a warning as the call starts failing and a line as it answers
 * again. It runs while the log has tails open, on [scope], and stops with the last.
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

        /** Asks the tail to read the store, for [reason]; it reads once, whatever the wakes before it does. */
        fun wake(reason: Reason)
    }

    private val tails = ConcurrentHashMap.newKeySet<Probed>()

    /** The tails whose partition the probe named and that have not been woken for it yet. */
    private val owed = ConcurrentHashMap.newKeySet<Probed>()
    private val running = AtomicBoolean()

    // The loop's alone: one loop runs at a time, and the next starts after the last has ended.
    private val probeOutage = Outage(
        "probe",
        "they read other nodes' entries only as they open or while their probes are on schedule, and the backstop " +
            "waits for it too",
    )
    private val backstopOutage = Outage("backstop read of the heads", "what the probe does not name is not read")

    /** The rounds it made, those that failed included: for the tests. */
    @Volatile
    var rounds: Long = 0
        private set

    fun register(tail: Probed) {
        tails += tail
        start()
    }

    fun unregister(tail: Probed) {
        tails -= tail
        owed -= tail
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
                // A backstop that could not run, the probe or its read failing, is due again at the next round.
                if (round(open, backstop.elapsedNow() >= options.backstopInterval)) backstop = time.markNow()
                rounds++
            }
        } finally {
            running.set(false)
            // A tail registered as the loop ended starts it again.
            start()
        }
    }

    /** One round of [open]'s tails, with the [backstop] when it is due; whether it read the heads. */
    @Suppress("TooGenericExceptionCaught") // A failed round is the next one's to do: the store may be down a while.
    private suspend fun round(open: List<Probed>, backstop: Boolean): Boolean {
        val cursors = open.groupBy { it.partition }.mapValues { (_, it) -> it.minOf { tail -> tail.cursor } }
        val named = try {
            log.appendedElsewhere(cursors)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            probeOutage.failed(failure)
            return false
        }
        probeOutage.answered()
        // A tail that closed while the last round marked it is not owed any more.
        owed.retainAll(tails)
        open.filterTo(owed) { it.partition in named }
        open.filter { it in owed && it.idleFor >= options.probeInterval / 2 }.forEach {
            owed -= it
            it.wake(Reason.IDLE)
        }
        if (!backstop) return false
        val heads = try {
            log.heads(cursors.keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            backstopOutage.failed(failure)
            return false
        }
        backstopOutage.answered()
        open.filter { tail ->
            tail.partition !in named && tail.idleFor >= options.probeInterval &&
                (heads[tail.partition] ?: 0L) > tail.cursor
        }.forEach { it.wake(Reason.BACKSTOP) }
        return true
    }

    /**
     * One of the prober's calls of the log, while it fails: a warning as it starts failing, naming what the tails miss
     * meanwhile ([consequence]), a line as it answers again, and debug for the rounds in between, so a store down a
     * while logs twice, not every round.
     */
    private inner class Outage(private val call: String, private val consequence: String) {
        private var since: TimeMark? = null

        fun failed(failure: Exception) {
            if (since != null) {
                logger.debug(failure) { "The $call of the tails of $context failed again; the next round makes it." }
                return
            }
            since = time.markNow()
            logger.warn(failure) {
                "The $call of the tails of $context fails, and is made again every round; until it answers, " +
                    "$consequence."
            }
        }

        fun answered() {
            val failing = since ?: return
            since = null
            logger.info { "The $call of the tails of $context answers again, after ${failing.elapsedNow()}." }
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
