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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs commands through [kernel], one at a time per aggregate and in arrival order, without one aggregate ever
 * waiting for another (design 02 §1, step 3).
 *
 * Each aggregate with commands in flight has a lane: a fair lock its commands queue on, in the callers' coroutines,
 * so a decide function suspended on I/O holds up only its own aggregate. A lane exists only while it has commands;
 * at most [laneCapacity] may wait on one, and the next is refused as busy rather than queued without bound.
 *
 * The node bounds its commands in flight as a whole too (decision D228 ②): every executor of a node shares one
 * [InFlightLimit], counted when a command enters, so a store that slows down (rather than fails) turns the commands
 * beyond it into `wow.command.busy` (429, with `Retry-After`) instead of an unbounded queue spread over many lanes.
 * A command that waits in its lane with a [WaitContext] whose deadline passes before its turn is withdrawn: it never
 * enters the kernel ([CommandWithdrawnException]), so the caller's timed-out wait is not followed by an execution
 * nobody waits for, and a retry with the same request id runs it afresh.
 *
 * [close] stops taking commands at once (a late command fails instead of waiting on a lane nobody will run) and
 * returns when the commands already taken have finished.
 *
 * When [views] are given, each commit's state view is recorded in them before the lane moves on to the aggregate's
 * next command, while nothing can change the state (design 01 §2): the context's state consumers (the snapshot
 * subscription, the subscriptions of annotated processors) read it (decision D133). A view is only an optimisation,
 * so failing to take one never fails the committed command: the failure is logged, and the consumers rebuild the
 * state instead.
 */
@InternalWowApi
class Executor(
    private val kernel: Kernel,
    private val laneCapacity: Int = DEFAULT_LANE_CAPACITY,
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
    }

    /** The aggregates with commands in flight. */
    val activeLanes: Int get() = lanes.size

    /** Runs [command] through the kernel in its aggregate's lane; [wait] is passed on to [Kernel.execute]. */
    suspend fun execute(model: AggregateModel<*>, command: Command<*>, wait: WaitContext? = null): CommandOutcome {
        val lane = enter(command.ref)
        try {
            lock(lane, command, wait)
            try {
                return kernel.execute(model, command, wait).also { outcome ->
                    if (outcome is CommandOutcome.Committed && views != null) {
                        recordView(views, model, outcome)
                    }
                }
            } finally {
                lane.lock.unlock()
            }
        } finally {
            leave(command.ref)
        }
    }

    /**
     * Takes [lane]'s lock for [command]; when it has to queue and [wait]'s deadline passes first, withdraws the command
     * instead. [Mutex.lock] gives the lock back when it is cancelled after taking it, and a lock taken just as the
     * timeout fires is kept ([locked]): the command then runs.
     */
    private suspend fun lock(lane: Lane, command: Command<*>, wait: WaitContext?) {
        if (lane.lock.tryLock()) {
            return
        }
        val deadline = wait?.deadline
        if (deadline == null) {
            lane.lock.lock()
            return
        }
        var locked = false
        withTimeoutOrNull((deadline - clock()).coerceAtLeast(0).milliseconds) {
            lane.lock.lock()
            locked = true
        }
        if (!locked) {
            throw CommandWithdrawnException(command.ref, command.id)
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

    /** Refuses new commands from now on, and returns once the commands in flight have finished. */
    suspend fun close() {
        closed = true
        if (inFlight.get() == 0) {
            drained.complete(Unit)
        }
        drained.await()
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

    /** Guarded by the map's per-key atomicity: [commands] changes only inside [ConcurrentHashMap.compute]. */
    private class Lane {
        val lock = Mutex()
        var commands = 0
    }

    private companion object {
        const val DEFAULT_LANE_CAPACITY = 1_024
        val logger = KotlinLogging.logger {}
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

    init {
        require(max >= 1) { "A node takes at least one command in flight." }
    }

    /** The commands in flight now. */
    val inFlight: Int get() = count.get()

    /** Takes one place, or false when all [max] are taken. */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= max) {
                return false
            }
            if (count.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    /** Gives back a place [tryAcquire] took. */
    fun release() {
        count.decrementAndGet()
    }

    companion object {
        const val DEFAULT_MAX: Int = 4_096
    }
}
