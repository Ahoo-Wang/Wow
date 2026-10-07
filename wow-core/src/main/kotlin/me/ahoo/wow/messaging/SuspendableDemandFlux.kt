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

import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Operators
import reactor.util.context.Context

/**
 * Forwards demand to [source] until [suspendDemand], then stops requesting without cancelling it.
 *
 * Elements requested before suspension are still delivered, so a durable transport keeps its consumer (and its
 * acknowledgements) alive while it stops handing over new records; a request racing with suspension may still be
 * forwarded once. Suspension is one-way.
 */
internal class SuspendableDemandFlux<T : Any>(
    private val source: Flux<T>,
) : Flux<T>() {
    @Volatile
    private var suspended = false

    override fun subscribe(actual: CoreSubscriber<in T>) {
        source.subscribe(SuspendableDemandSubscriber(actual))
    }

    fun suspendDemand() {
        suspended = true
    }

    private inner class SuspendableDemandSubscriber(
        private val actual: CoreSubscriber<in T>,
    ) : CoreSubscriber<T>,
        Subscription {
        private var upstream: Subscription? = null

        override fun currentContext(): Context = actual.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            if (Operators.validate(upstream, subscription)) {
                upstream = subscription
                actual.onSubscribe(this)
            }
        }

        override fun onNext(value: T) = actual.onNext(value)

        override fun onError(error: Throwable) = actual.onError(error)

        override fun onComplete() = actual.onComplete()

        override fun request(n: Long) {
            if (!suspended) {
                upstream?.request(n)
            }
        }

        override fun cancel() {
            upstream?.cancel()
        }
    }
}
