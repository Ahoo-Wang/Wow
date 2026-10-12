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
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.snapshot.StateViews
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands through [kernel], one at a time per aggregate and in arrival order, without one aggregate ever
 * waiting for another (design 02 §1, step 3).
 *
 * Each aggregate with commands in flight has a lane: a fair lock its commands queue on, in the callers' coroutines,
 * so a decide function suspended on I/O holds up only its own aggregate. A lane exists only while it has commands;
 * at most [laneCapacity] may wait on one, and the next is refused as busy rather than queued without bound.
 *
 * [close] stops taking commands at once (a late command fails instead of waiting on a lane nobody will run) and
 * returns when the commands already taken have finished.
 *
 * When [views] are given, each commit's state view is recorded in them before the lane moves on to the aggregate's
 * next command, while nothing can change the state (design 01 §2): the snapshot subscription writes from it. A
 * view is only an optimisation, so failing to take one never fails the committed command: the failure is logged,
 * the aggregate's view is dropped, and the subscription rebuilds the state instead.
 */
class Executor(
    private val kernel: Kernel,
    private val laneCapacity: Int = DEFAULT_LANE_CAPACITY,
    private val views: StateViews? = null,
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
            return lane.lock.withLock {
                kernel.execute(model, command, wait).also {
                    if (views != null && it is CommandOutcome.Committed) {
                        recordView(views, model, it)
                    }
                }
            }
        } finally {
            leave(command.ref)
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
            logger.warn(failure) { "Failed to take the state view of ${stream.ref}@${stream.version}; dropping it." }
            views.drop(stream.ref)
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
class ExecutorClosedException : IllegalStateException("The executor is closed.")

/** [ref] already has [capacity] commands waiting: the command was not taken. */
class ExecutorBusyException(val ref: AggregateRef, val capacity: Int) :
    IllegalStateException("$ref already has $capacity commands waiting.")
