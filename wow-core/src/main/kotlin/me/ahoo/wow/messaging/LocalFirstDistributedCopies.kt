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

package me.ahoo.wow.messaging

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The distributed copies a [LocalFirstMessageBus] sends without its senders waiting for them (decision D3).
 *
 * As a [RuntimeComponent] stopped after the dispatchers and before the transports' runtime resources, it lets every
 * copy in flight finish within the runtime's shutdown deadline ([stopGracefully]); a force stop cancels them.
 */
class LocalFirstDistributedCopies(
    private val name: String = "LocalFirstDistributedCopies",
) : RuntimeComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private val DRAIN_POLL_INTERVAL: Duration = Duration.ofMillis(10)
    }

    private val inFlight: MutableSet<Disposable.Swap> = ConcurrentHashMap.newKeySet()

    /** The copies not yet sent or failed. */
    val pending: Int
        get() = inFlight.size

    /**
     * Subscribes [copy] and tracks it until it completes; a failure is logged with [description] and never thrown to
     * a sender.
     */
    fun send(copy: Mono<Void>, description: () -> String) {
        val subscription = Disposables.swap()
        inFlight.add(subscription)
        subscription.update(
            copy.doFinally { inFlight.remove(subscription) }
                .subscribe(
                    null,
                    { error ->
                        log.warn(error) {
                            "[$name] Failed to send the distributed copy of ${description()}; only consumers " +
                                "outside this process miss it."
                        }
                    },
                ),
        )
    }

    override fun prepare(runtimeContext: RuntimeContext): Mono<Void> = Mono.empty()

    override fun start() = Unit

    override fun stopGracefully(): Mono<Void> =
        Mono.defer {
            if (inFlight.isEmpty()) {
                Mono.empty()
            } else {
                log.info { "[$name] Wait for ${inFlight.size} distributed copies in flight." }
                Flux.interval(DRAIN_POLL_INTERVAL)
                    .filter { inFlight.isEmpty() }
                    .next()
                    .then()
            }
        }

    override fun forceStop() {
        if (inFlight.isNotEmpty()) {
            log.warn { "[$name] Cancel ${inFlight.size} distributed copies in flight." }
        }
        inFlight.toList().forEach(Disposable::dispose)
        inFlight.clear()
    }

    override fun toString(): String = name
}
