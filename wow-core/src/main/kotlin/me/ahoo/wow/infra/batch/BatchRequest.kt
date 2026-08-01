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

package me.ahoo.wow.infra.batch

import reactor.core.publisher.Sinks
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the lifecycle and completion signal of one admitted batch item.
 *
 * Live admission and the physical queue slot deliberately have independent
 * release points: cancellation releases the caller-facing capacity
 * immediately, while the queue slot remains held until the lane observes the
 * cancelled placeholder.
 */
internal open class BatchRequest<T : Any>(
    val value: T,
    private val onReleaseAdmission: (BatchRequest<T>) -> Unit,
    private val onReleaseQueueSlot: () -> Unit,
) {
    private sealed interface State {
        data object Queued : State

        data object InFlight : State

        data object Cancelled : State

        data class Settled(val outcome: BatchItemResult) : State

        data object Terminated : State
    }

    val result: Sinks.Empty<Void> = Sinks.empty()
    private val state = AtomicReference<State>(State.Queued)

    fun claim(lane: Int = 0): Boolean {
        while (true) {
            when (state.get()) {
                State.Queued -> {
                    if (state.compareAndSet(State.Queued, State.InFlight)) {
                        onClaimed(lane)
                        releaseQueueSlot()
                        return true
                    }
                }

                State.Cancelled -> {
                    discardCancelled()
                    return false
                }

                State.InFlight,
                is State.Settled,
                State.Terminated,
                -> return false
            }
        }
    }

    fun cancel() {
        if (state.compareAndSet(State.Queued, State.Cancelled)) {
            releaseAdmission()
            onCancelled()
        }
    }

    fun discardAdmission() {
        if (state.compareAndSet(State.Queued, State.Terminated)) {
            releaseQueueSlot()
            releaseAdmission()
        }
    }

    fun settle(outcome: BatchItemResult) {
        while (true) {
            when (val current = state.get()) {
                State.InFlight -> {
                    if (state.compareAndSet(current, State.Settled(outcome))) {
                        return
                    }
                }

                State.Cancelled -> {
                    discardCancelled()
                    return
                }

                State.Queued,
                is State.Settled,
                State.Terminated,
                -> return
            }
        }
    }

    fun settleFailure(error: Throwable) = settle(BatchItemResult.Failure(error))

    fun signalSettled() {
        while (true) {
            when (val current = state.get()) {
                is State.Settled -> {
                    if (state.compareAndSet(current, State.Terminated)) {
                        releaseAdmission()
                        when (val outcome = current.outcome) {
                            BatchItemResult.Success -> result.tryEmitEmpty()
                            is BatchItemResult.Failure -> result.tryEmitError(outcome.error)
                        }
                        return
                    }
                }

                State.Queued,
                State.InFlight,
                State.Cancelled,
                State.Terminated,
                -> return
            }
        }
    }

    fun settleFailureIfUnsettled(error: Throwable): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.Queued,
                State.InFlight,
                -> {
                    if (
                        state.compareAndSet(
                            current,
                            State.Settled(BatchItemResult.Failure(error)),
                        )
                    ) {
                        if (current == State.Queued) {
                            releaseQueueSlot()
                        }
                        return true
                    }
                }

                State.Cancelled -> {
                    discardCancelled()
                    return false
                }

                is State.Settled,
                State.Terminated,
                -> return false
            }
        }
    }

    private fun discardCancelled() {
        if (state.compareAndSet(State.Cancelled, State.Terminated)) {
            releaseQueueSlot()
        }
    }

    private fun releaseAdmission() = onReleaseAdmission(this)

    private fun releaseQueueSlot() = onReleaseQueueSlot()

    protected open fun onClaimed(lane: Int) = Unit

    protected open fun onCancelled() = Unit
}

internal class ObservedBatchRequest<T : Any>(
    value: T,
    onReleaseAdmission: (BatchRequest<T>) -> Unit,
    onReleaseQueueSlot: () -> Unit,
    private val enqueuedAtNanos: Long,
    private val observations: BatchObservationEmitter,
) : BatchRequest<T>(value, onReleaseAdmission, onReleaseQueueSlot) {
    override fun onClaimed(lane: Int) {
        observations.requestDequeued(lane, enqueuedAtNanos)
    }

    override fun onCancelled() {
        observations.requestCancelled()
    }
}
