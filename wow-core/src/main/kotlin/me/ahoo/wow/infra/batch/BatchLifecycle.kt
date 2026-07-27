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

/**
 * Serializes coordinator state transitions and admission into lane sinks.
 */
internal class BatchLifecycle(
    private val name: String,
) {
    private sealed interface State {
        data object Open : State

        data object Closing : State

        data object DrainingResults : State

        data object Closed : State

        data class Failed(val cause: Throwable) : State
    }

    sealed interface ProcessorCompletion {
        data object DrainResults : ProcessorCompletion

        data object Closed : ProcessorCompletion

        data class Failed(val cause: Throwable) : ProcessorCompletion
    }

    sealed interface ResultDrainCompletion {
        data object Closed : ResultDrainCompletion

        data class Failed(val cause: Throwable) : ResultDrainCompletion
    }

    sealed interface FailureTransition {
        data object Closed : FailureTransition

        data class Existing(val cause: Throwable) : FailureTransition

        data class Installed(val cause: Throwable) : FailureTransition
    }

    private val lock = Any()

    @Volatile
    private var state: State = State.Open

    val isFailed: Boolean
        get() = state is State.Failed

    val failureCause: Throwable?
        get() = (state as? State.Failed)?.cause

    fun terminalErrorOrClosed(): Throwable? {
        return when (val current = state) {
            State.Open -> null
            State.Closing,
            State.DrainingResults,
            State.Closed,
            -> BatchClosedException(name)

            is State.Failed -> current.cause
        }
    }

    fun emitIfOpen(emitter: () -> Sinks.EmitResult): Sinks.EmitResult {
        return synchronized(lock) {
            if (state == State.Open) {
                emitter()
            } else {
                Sinks.EmitResult.FAIL_TERMINATED
            }
        }
    }

    fun initiateClose(): Boolean {
        return synchronized(lock) {
            if (state != State.Open) {
                false
            } else {
                state = State.Closing
                true
            }
        }
    }

    fun processorCompleted(): ProcessorCompletion {
        return synchronized(lock) {
            when (val current = state) {
                State.Open,
                State.Closing,
                -> {
                    state = State.DrainingResults
                    ProcessorCompletion.DrainResults
                }

                State.DrainingResults -> ProcessorCompletion.DrainResults
                State.Closed -> ProcessorCompletion.Closed
                is State.Failed -> ProcessorCompletion.Failed(current.cause)
            }
        }
    }

    fun resultDispatcherTerminated(): ResultDrainCompletion {
        return synchronized(lock) {
            when (val current = state) {
                State.DrainingResults -> {
                    state = State.Closed
                    ResultDrainCompletion.Closed
                }

                State.Closed -> ResultDrainCompletion.Closed
                is State.Failed -> ResultDrainCompletion.Failed(current.cause)
                State.Open,
                State.Closing,
                -> {
                    val cause = IllegalStateException(
                        "Batch result dispatcher[$name] terminated before the processor."
                    )
                    state = State.Failed(cause)
                    ResultDrainCompletion.Failed(cause)
                }
            }
        }
    }

    fun fail(error: Throwable): FailureTransition {
        return synchronized(lock) {
            when (val current = state) {
                State.Open,
                State.Closing,
                State.DrainingResults,
                -> {
                    state = State.Failed(error)
                    FailureTransition.Installed(error)
                }

                State.Closed -> FailureTransition.Closed
                is State.Failed -> FailureTransition.Existing(current.cause)
            }
        }
    }
}
