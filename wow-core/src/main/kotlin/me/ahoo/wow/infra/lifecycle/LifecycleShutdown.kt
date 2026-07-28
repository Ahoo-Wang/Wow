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

package me.ahoo.wow.infra.lifecycle

import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Runs every graceful stop action in order, retaining the first failure and
 * suppressing later failures.
 */
@Suppress("TooGenericExceptionCaught")
fun stopAllGracefully(
    stopActions: Iterable<() -> Mono<Void>>,
    initialFailure: Throwable? = null,
    onFailure: (Throwable) -> Unit = {},
): Mono<Void> =
    Mono.defer {
        var firstFailure = initialFailure
        fun recordFailure(error: Throwable) {
            val primary = firstFailure
            if (primary == null) {
                firstFailure = error
            } else {
                primary.addSuppressedIfAbsent(error)
            }
        }
        Flux.fromIterable(stopActions)
            .concatMap { stopAction ->
                Mono.defer(stopAction)
                    .onErrorResume { error ->
                        Exceptions.throwIfFatal(error)
                        recordFailure(error)
                        try {
                            onFailure(error)
                        } catch (callbackFailure: Throwable) {
                            Exceptions.throwIfFatal(callbackFailure)
                            recordFailure(callbackFailure)
                        }
                        Mono.empty()
                    }
            }
            .then(
                Mono.defer {
                    firstFailure?.let { Mono.error(it) } ?: Mono.empty()
                },
            )
    }

@JvmName("stopAllGracefullyStoppables")
fun Iterable<GracefullyStoppable>.stopAllGracefully(): Mono<Void> =
    stopAllGracefully(map { stoppable -> stoppable::stopGracefully })

/**
 * Forces every action, retaining the first failure and suppressing later failures.
 */
@Suppress("TooGenericExceptionCaught")
fun forceStopAll(forceActions: Iterable<() -> Unit>) {
    forceStopAll(forceActions, initialFailure = null)?.let { throw it }
}

@JvmName("forceStopAllStoppables")
fun Iterable<ForceStoppable>.forceStopAll() {
    forceStopAll(map { stoppable -> stoppable::forceStop })
}

@Suppress("TooGenericExceptionCaught")
internal fun forceStopAll(
    forceActions: Iterable<() -> Unit>,
    initialFailure: Throwable?,
): Throwable? {
    var firstFailure = initialFailure
    forceActions.forEach { forceAction ->
        try {
            forceAction()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            val primary = firstFailure
            if (primary == null) {
                firstFailure = error
            } else {
                primary.addSuppressedIfAbsent(error)
            }
        }
    }
    return firstFailure
}

internal fun Throwable.addSuppressedIfAbsent(error: Throwable) {
    if (this === error) {
        return
    }
    synchronized(this) {
        if (suppressedExceptions.none { suppressed -> suppressed === error }) {
            addSuppressed(error)
        }
    }
}
