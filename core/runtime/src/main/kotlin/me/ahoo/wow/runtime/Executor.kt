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

package me.ahoo.wow.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.BatchOutcome
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.QueuedCommand
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs commands through [kernel], one at a time per aggregate and in arrival order, without one aggregate ever
 * waiting for another (design 02 §1, step 3).
 *
 * Each aggregate with commands in flight has a lane: a queue of its commands in arrival order, run by one of the
 * callers' coroutines at a time, the lane's leader, so a decide function suspended on I/O holds up only its own
 * aggregate. A lane exists only while it has commands; at most [laneCapacity] may wait on one, and the next is refused
 * as busy rather than queued without bound.
 *
 * The leader runs its own command, the head of the queue, and when others queue behind it, up to [maxBatch] of them
 * with it as one batch ([Kernel.executeBatch], roadmap §6 item 10): decided in turn and committed with one append, so a
 * hot aggregate commits a batch per append instead of a command. It never waits to fill a batch: an idle aggregate's
 * command runs alone, as [Kernel.execute] runs it. The commands the batch did not finish
 * ([BatchOutcome.Again]) go back to the head of the queue, in order, and the leader hands the lane to the command at
 * the head, which leads the next batch in its own coroutine: a caller waits for its own command, never for others'.
 * A [maxBatch] of 1 runs every command alone.
 *
 * The node bounds its commands in flight as a whole too (decision D228 ②): every executor of a node shares one
 * [InFlightLimit], counted when a command enters, so a store that slows down (rather than fails) turns the commands
 * beyond it into `wow.command.busy` (429, with `Retry-After`) instead of an unbounded queue spread over many lanes.
 * A command that waits in its lane with a [WaitContext] whose deadline passes before a batch takes it is withdrawn: it
 * never enters the kernel ([CommandWithdrawnException]), so the caller's timed-out wait is not followed by an execution
 * nobody waits for, and a retry with the same request id runs it afresh.
 *
 * [close] stops taking commands at once (a late command fails instead of waiting on a lane nobody will run) and
 * returns when the commands already taken have finished, or its timeout passed.
 *
 * When [views] are given, each commit's state view is recorded in them before the lane moves on to the aggregate's
 * next batch, while nothing can change the state (design 01 §2): the context's state consumers (the snapshot
 * subscription, the subscriptions of annotated processors) read it (decision D133). It is taken just before the append
 * ([StateViews.expect]), since the log may hand the commit to this node's subscriptions before the kernel returns
 * (decisions D249, D261), and recorded once the append committed. A view is only an optimisation,
 * so failing to take one never fails the committed command: the failure is logged, and the consumers rebuild the
 * state instead.
 */
@InternalWowApi
class Executor(
    private val kernel: Kernel,
    private val laneCapacity: Int = DEFAULT_LANE_CAPACITY,
    /** The most commands of one aggregate a batch takes (`wow.executor.max-batch`); 1 runs each alone. */
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    private val views: StateViews? = null,
    /** The node's bound on its commands in flight, shared by the executors of all its contexts. */
    private val limit: InFlightLimit = InFlightLimit(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lanes = ConcurrentHashMap<AggregateRef, Lane>()
    private val inFlight = AtomicInteger()

    @Volatile
    private var closed = false
    private val drained = CompletableDeferred<Unit>()

    init {
        require(laneCapacity >= 1) { "A lane takes at least one command." }
        require(maxBatch >= 1) { "A batch takes at least one command." }
    }

    /** The aggregates with commands in flight. */
    val activeLanes: Int get() = lanes.size

    /** The commands taken and not finished, waiting in their lanes or in the kernel (review A5-11). */
    val commandsInFlight: Int get() = inFlight.get()

    /** Runs [command] through the kernel in its aggregate's lane; [wait] is passed on to [Kernel.execute]. */
    suspend fun execute(model: AggregateModel<*>, command: Command<*>, wait: WaitContext? = null): CommandOutcome {
        val lane = enter(command.ref)
        try {
            val own = Queued(model, command, wait)
            val leading = synchronized(lane) {
                lane.queue.addLast(own)
                !lane.leading.also { lane.leading = true }
            }
            val turn = if (leading) BatchOutcome.Again else turnOf(lane, own)
            return when (val outcome = if (turn is BatchOutcome.Again) lead(lane, own) else turn) {
                is BatchOutcome.Done -> outcome.outcome
                is BatchOutcome.Failed -> throw outcome.failure
                BatchOutcome.Again -> error("The leader's own command always runs.")
            }
        } finally {
            leave(command.ref)
        }
    }

    /**
     * Waits for [own]'s turn behind [lane]'s leader: its outcome, from the batch that took it, or [BatchOutcome.Again]
     * when the lane is handed to it to lead. When its [WaitContext]'s deadline passes while it is still queued, it is
     * withdrawn instead; a turn that came just as the deadline passed is kept: the command then runs.
     */
    private suspend fun turnOf(lane: Lane, own: Queued): BatchOutcome {
        try {
            val deadline = own.wait?.deadline ?: return own.turn.await()
            val remaining = (deadline - clock()).coerceAtLeast(0).milliseconds
            withTimeoutOrNull(remaining) { own.turn.await() }?.let { return it }
            synchronized(lane) {
                if (!own.turn.isCompleted && lane.queue.remove(own)) {
                    throw CommandWithdrawnException(own.command.ref, own.command.id)
                }
            }
            // Taken by a batch, or handed the lane: no longer withdrawn.
            return own.turn.await()
        } catch (cancelled: CancellationException) {
            abandon(lane, own)
            throw cancelled
        }
    }

    /**
     * [own]'s caller was cancelled: it leaves the queue, and hands the lane on when it was handed it. A batch that took
     * it gives its outcome to nobody, and does not put it back when it did not finish it: nobody would lead it.
     */
    private fun abandon(lane: Lane, own: Queued) = synchronized(lane) {
        if (!own.turn.isCompleted) {
            if (!lane.queue.remove(own)) own.abandoned = true
        } else if (own.turn.getCompleted() is BatchOutcome.Again) {
            lane.queue.remove(own)
            handOff(lane)
        }
    }

    /**
     * Runs a batch from the head of [lane]'s queue, [own] first, then hands the lane on: [own]'s outcome. The others
     * get theirs, or go back to the head of the queue, in order, when the batch did not finish them (or threw); those
     * whose callers were cancelled meanwhile are dropped instead, as unfinished means nothing of theirs committed.
     */
    private suspend fun lead(lane: Lane, own: Queued): BatchOutcome {
        val batch = synchronized(lane) {
            check(lane.queue.first() === own) { "The leader's command heads its lane." }
            List(minOf(maxBatch, lane.queue.size)) { lane.queue.removeFirst() }
        }
        val model = own.model
        var outcomes: List<BatchOutcome>? = null
        try {
            val appending = views?.let { expecting(it, model) }
            outcomes = if (batch.size == 1) {
                listOf(BatchOutcome.Done(kernel.execute(model, own.command, own.wait, appending)))
            } else {
                kernel.executeBatch(model, batch.map { QueuedCommand(it.command, it.wait) }, appending)
            }
            if (views != null) {
                outcomes.forEach { outcome ->
                    ((outcome as? BatchOutcome.Done)?.outcome as? CommandOutcome.Committed)?.let {
                        recordView(views, model, it)
                    }
                }
            }
            return outcomes.first()
        } finally {
            // Before the lane moves on: the aggregate's next batch expects its own.
            views?.forget(own.command.ref)
            synchronized(lane) {
                for (at in batch.indices.reversed()) {
                    val again = outcomes == null || outcomes[at] is BatchOutcome.Again
                    if (at > 0 && again && !batch[at].abandoned) lane.queue.addFirst(batch[at])
                }
                handOff(lane)
            }
            outcomes?.forEachIndexed { at, outcome ->
                if (at > 0 && outcome !is BatchOutcome.Again) batch[at].turn.complete(outcome)
            }
        }
    }

    /** Hands [lane] to the command at the head of its queue, or leaves it without a leader; under [lane]'s monitor. */
    private fun handOff(lane: Lane) {
        val next = lane.queue.firstOrNull()
        if (next == null) {
            lane.leading = false
        } else {
            next.turn.complete(BatchOutcome.Again)
        }
    }

    /** What the kernel calls before each append of [model]'s aggregate: [StateViews.expect] of its state. */
    private fun expecting(views: StateViews, model: AggregateModel<*>): (EventStreamRecord, CachedState) -> Unit =
        { stream, state -> expectView(views, model, stream, state) }

    @Suppress("TooGenericExceptionCaught") // Whatever fails, the append goes on; the consumers rebuild the state.
    private fun expectView(views: StateViews, model: AggregateModel<*>, stream: EventStreamRecord, state: CachedState) {
        try {
            views.expect(model, stream, state)
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to take the state view of ${stream.ref}@${stream.version}; skipping it." }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails, the command is committed; only cancellation propagates.
    private fun recordView(views: StateViews, model: AggregateModel<*>, committed: CommandOutcome.Committed) {
        try {
            views.record(model, committed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val stream = committed.stream
            logger.warn(failure) { "Failed to take the state view of ${stream.ref}@${stream.version}; skipping it." }
        }
    }

    /**
     * Refuses new commands from now on, and returns once the commands in flight have finished: true, or false when
     * [timeout] passed first, logging the lanes still busy.
     */
    suspend fun close(timeout: Duration): Boolean {
        closed = true
        if (inFlight.get() == 0) {
            drained.complete(Unit)
        }
        if (withTimeoutOrNull(timeout) { drained.await() } != null) {
            return true
        }
        logger.warn {
            "The executor closes with ${inFlight.get()} commands still in flight, in ${lanes.size} lanes: " +
                lanes.keys.take(LOGGED_LANES).joinToString() + if (lanes.size > LOGGED_LANES) ", ..." else ""
        }
        return false
    }

    private fun enter(ref: AggregateRef): Lane {
        refuseWhenClosed()
        inFlight.incrementAndGet()
        if (!limit.tryAcquire()) {
            finish()
            throw ExecutorBusyException(ref, limit.max, node = true)
        }
        var busy = false
        val lane = lanes.compute(ref) { _, lane ->
            val current = lane ?: Lane()
            if (current.commands >= laneCapacity) {
                busy = true
            } else {
                current.commands++
            }
            current
        }!!
        if (busy) {
            limit.release()
            finish()
            throw ExecutorBusyException(ref, laneCapacity)
        }
        // Closed while entering: close() may already have seen nothing in flight and returned.
        refuseWhenClosed { leave(ref) }
        return lane
    }

    private inline fun refuseWhenClosed(release: () -> Unit = {}) {
        if (closed) {
            release()
            throw ExecutorClosedException()
        }
    }

    private fun leave(ref: AggregateRef) {
        lanes.compute(ref) { _, lane -> lane!!.also { it.commands-- }.takeIf { it.commands > 0 } }
        limit.release()
        finish()
    }

    private fun finish() {
        if (inFlight.decrementAndGet() == 0 && closed) {
            drained.complete(Unit)
        }
    }

    /**
     * [commands] changes only inside [ConcurrentHashMap.compute]; [queue] and [leading] only under the lane's monitor.
     * The lane has a leader while its queue is not empty, and its queue never holds an abandoned command: one is
     * abandoned only once a batch took it ([abandon]), and the batch never puts it back ([lead]).
     */
    private class Lane {
        var commands = 0
        val queue = ArrayDeque<Queued>()
        var leading = false
    }

    /**
     * A command in its lane: [turn] is its outcome, or [BatchOutcome.Again] when it is handed the lane to lead.
     * [abandoned], under the lane's monitor: its caller was cancelled while a batch had taken it.
     */
    private class Queued(val model: AggregateModel<*>, val command: Command<*>, val wait: WaitContext?) {
        val turn = CompletableDeferred<BatchOutcome>()
        var abandoned = false
    }

    @InternalWowApi
    companion object {
        /** The commands one aggregate may have waiting by default (`wow.executor.lane-capacity`). */
        const val DEFAULT_LANE_CAPACITY: Int = 1_024

        /** A batch's commands decide on the same state one after another: 32 take well under a millisecond. */
        const val DEFAULT_MAX_BATCH: Int = 32
        private const val LOGGED_LANES = 10
        private val logger = KotlinLogging.logger {}
    }
}

/** The executor is closed: the command was not taken. */
internal class ExecutorClosedException : IllegalStateException("The executor is closed.")

/**
 * The command of [ref] was not taken: its aggregate already has [capacity] commands waiting, or, when [node], the node
 * already has [capacity] commands in flight ([InFlightLimit]).
 */
@InternalWowApi
class ExecutorBusyException(val ref: AggregateRef, val capacity: Int, val node: Boolean = false) :
    IllegalStateException(
        if (node) {
            "The node already has $capacity commands in flight; retry later."
        } else {
            "$ref already has $capacity commands waiting; retry later."
        },
    )

/**
 * A command the node's own code sent (a command topic's intake, a saga's derived command, a timer) was refused as
 * busy ([me.ahoo.wow.runtime.wait.ResultCodes.BUSY]): its aggregate's lane was full or the node had its most commands
 * in flight (decision D228 ②). It never ran. For an outside caller that is a 429 to back off from; for these sources
 * it is backpressure, not a failure (review R2.1-1): a subscription retries the entry after a backoff without spending
 * its retry budget, so a burst never parks an aggregate, and a timer is sent again without counting an attempt.
 */
internal class CommandBusyException(val ref: AggregateRef, val commandId: String, reason: String?) :
    IllegalStateException("The command $commandId on $ref was refused as busy ($reason); it is sent again later.")

/**
 * Command [commandId] of [ref] waited in its aggregate's lane past its wait's deadline and was withdrawn before it
 * entered the kernel (decision D228 ②): it never executed, so a retry with the same request id runs it.
 */
@InternalWowApi
class CommandWithdrawnException(val ref: AggregateRef, val commandId: String) :
    IllegalStateException("The wait timed out before command $commandId on $ref ran; it was withdrawn, not executed.")

/**
 * A node's bound on the commands in flight in its executors (decision D228 ②, `wow.executor.max-in-flight`): those
 * taken and not finished, waiting in their lanes or in the kernel. The executors of every context on a node share
 * one, so it bounds the node's memory and the work it queues ahead of its stores, whichever aggregates the commands
 * are for.
 *
 * The default, [DEFAULT_MAX], is sized by Little's law for the slow store A5-2 describes: a node whose store commits
 * a few thousand appends a second (a connection pool of tens at a few milliseconds each, the backend's group commit
 * on top) drains 4096 commands in one to two seconds, well within the default 10-second wait; beyond that, queueing
 * longer only turns into wait timeouts, and an early 429 lets the client back off instead. A node with more cores or
 * a larger pool raises it.
 */
@InternalWowApi
class InFlightLimit(val max: Int = DEFAULT_MAX) {
    private val count = AtomicInteger()

    /** When a command last finished, or the first was taken while none was in flight: `System.nanoTime()`. */
    @Volatile
    private var progressedAt: Long = System.nanoTime()

    init {
        require(max >= 1) { "A node takes at least one command in flight." }
    }

    /** The commands in flight now. */
    val inFlight: Int get() = count.get()

    /**
     * When the executors last made progress, as `System.nanoTime()` (review R3.5-3): a command finished, or one was
     * taken while none was in flight. With commands in flight and no progress for long, the kernel is stuck.
     */
    internal val lastProgress: Long get() = progressedAt

    /**
     * The commands the node's command intakes execute at once, all contexts together (review R2.1-1): half of [max],
     * so a node that owns many partitions of a command topic's backlog never fills the bound alone, and the commands of
     * callers, sagas and timers always have the other half.
     */
    val intakes: Semaphore = Semaphore((max / 2).coerceAtLeast(1))

    /** Takes one place, or false when all [max] are taken. */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= max) {
                return false
            }
            if (count.compareAndSet(current, current + 1)) {
                if (current == 0) {
                    progressedAt = System.nanoTime()
                }
                return true
            }
        }
    }

    /** Gives back a place [tryAcquire] took. */
    fun release() {
        count.decrementAndGet()
        progressedAt = System.nanoTime()
    }

    companion object {
        const val DEFAULT_MAX: Int = 4_096
    }
}
