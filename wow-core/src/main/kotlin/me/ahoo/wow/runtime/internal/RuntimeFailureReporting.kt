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

package me.ahoo.wow.runtime.internal

import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs every runtime-owned graceful stop action without mutating a locally
 * retained [Throwable].
 *
 * Each failure is reported through the owning runtime's sealable failure
 * boundary. The local first reference exists only to preserve the reactive
 * error contract and remains safe when a physically detached source signals
 * after runtime termination was published.
 */
internal fun stopAllReporting(
    stopActions: Iterable<() -> Mono<Void>>,
    reportFailure: (Throwable) -> Unit,
): Mono<Void> =
    Mono.defer {
        val firstFailure = AtomicReference<Throwable?>()
        Flux.fromIterable(stopActions)
            .concatMap { stopAction ->
                Mono.defer(stopAction)
                    .onErrorResume { error ->
                        Exceptions.throwIfFatal(error)
                        reportFailure(error)
                        firstFailure.compareAndSet(null, error)
                        Mono.empty()
                    }
            }
            .then(
                Mono.defer {
                    firstFailure.get()?.let { Mono.error(it) } ?: Mono.empty()
                },
            )
    }

/**
 * Runs every runtime-owned force action and reports every failure without
 * directly adding suppressed exceptions.
 */
@Suppress("TooGenericExceptionCaught")
internal fun forceAllReporting(
    forceActions: Iterable<() -> Unit>,
    reportFailure: (Throwable) -> Unit,
): Throwable? {
    var firstFailure: Throwable? = null
    forceActions.forEach { forceAction ->
        try {
            forceAction()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportFailure(error)
            if (firstFailure == null) {
                firstFailure = error
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
