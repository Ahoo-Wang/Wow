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
internal class BatchRequest<T : Any>(
    val value: T,
    private val onReleaseAdmission: (BatchRequest<T>) -> Unit,
    private val onReleaseQueueSlot: (BatchRequest<T>) -> Unit,
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

    fun claim(): Boolean {
        while (true) {
            when (state.get()) {
                State.Queued -> {
                    if (state.compareAndSet(State.Queued, State.InFlight)) {
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
        }
    }

    fun discardIfCancelled(): Boolean {
        val discarded = state.compareAndSet(State.Cancelled, State.Terminated)
        if (discarded || state.get() == State.Terminated) {
            releaseAllOwnership()
        }
        return discarded
    }

    fun discardAdmission() {
        if (state.compareAndSet(State.Queued, State.Terminated)) {
            releaseAllOwnership()
        }
    }

    fun settle(outcome: BatchItemResult): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.InFlight -> {
                    if (state.compareAndSet(current, State.Settled(outcome))) {
                        return true
                    }
                }

                State.Cancelled -> {
                    discardCancelled()
                    return false
                }

                State.Queued,
                is State.Settled,
                State.Terminated,
                -> return false
            }
        }
    }

    fun settleFailure(error: Throwable): Boolean =
        settle(BatchItemResult.Failure(error))

    fun signalSettled() {
        while (true) {
            when (val current = state.get()) {
                is State.Settled -> {
                    if (state.compareAndSet(current, State.Terminated)) {
                        releaseAllOwnership()
                        signal(result, current.outcome)
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

    /**
     * Atomically releases every coordinator-owned resource without invoking
     * subscriber code. The returned signal only retains the result sink and
     * outcome, so a blocking callback cannot retain this request or admission.
     */
    fun forceDetach(error: Throwable): (() -> Unit)? {
        try {
            while (true) {
                when (val current = state.get()) {
                    State.Queued,
                    State.InFlight,
                    is State.Settled,
                    -> {
                        if (state.compareAndSet(current, State.Terminated)) {
                            val resultSink = result
                            val outcome = when (current) {
                                is State.Settled -> current.outcome
                                else -> BatchItemResult.Failure(error)
                            }
                            return {
                                signal(resultSink, outcome)
                            }
                        }
                    }

                    State.Cancelled -> {
                        state.compareAndSet(State.Cancelled, State.Terminated)
                        return null
                    }

                    State.Terminated -> return null
                }
            }
        } finally {
            // BatchAdmission removes ownership from identity sets, so these
            // callbacks are deliberately safe to retry. Retrying lets force
            // cleanup help a transition that paused after its state CAS.
            releaseAllOwnership()
        }
    }

    /**
     * Detaches queued or in-flight ownership while leaving an already settled
     * request on its accepted result-dispatch path.
     *
     * A synchronous close timeout uses this weaker boundary so successful
     * results queued behind a blocking subscriber are neither reordered nor
     * re-dispatched. A hard force-stop uses [forceDetach] instead so subscriber
     * callbacks cannot hold logical termination.
     */
    fun forceDetachIfUnsettled(error: Throwable): (() -> Unit)? {
        var preserveSettledResult = false
        try {
            while (true) {
                when (val current = state.get()) {
                    State.Queued,
                    State.InFlight,
                    -> {
                        if (state.compareAndSet(current, State.Terminated)) {
                            val resultSink = result
                            return {
                                signal(
                                    resultSink,
                                    BatchItemResult.Failure(error),
                                )
                            }
                        }
                    }

                    is State.Settled -> {
                        preserveSettledResult = true
                        return null
                    }

                    State.Cancelled -> {
                        state.compareAndSet(State.Cancelled, State.Terminated)
                        return null
                    }

                    State.Terminated -> return null
                }
            }
        } finally {
            if (!preserveSettledResult) {
                releaseAllOwnership()
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
        discardIfCancelled()
    }

    private fun releaseAdmission() = onReleaseAdmission(this)

    private fun releaseQueueSlot() = onReleaseQueueSlot(this)

    private fun releaseAllOwnership() {
        releaseQueueSlot()
        releaseAdmission()
    }

    private companion object {
        fun signal(
            result: Sinks.Empty<Void>,
            outcome: BatchItemResult,
        ) {
            when (outcome) {
                BatchItemResult.Success -> result.tryEmitEmpty()
                is BatchItemResult.Failure -> result.tryEmitError(outcome.error)
            }
        }
    }
}
