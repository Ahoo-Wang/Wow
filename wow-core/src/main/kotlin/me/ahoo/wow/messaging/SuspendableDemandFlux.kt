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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards demand to the source until [suspendDemand] is called, then stops
 * requesting more elements without cancelling the source.
 *
 * Elements already requested before suspension are still delivered, so work the
 * transport has handed over keeps flowing while the source stops producing new
 * work. A request racing with suspension may still be forwarded once.
 */
internal class SuspendableDemandFlux<T : Any>(
    private val source: Flux<T>,
) : Flux<T>() {
    private val suspended = AtomicBoolean()

    override fun subscribe(actual: CoreSubscriber<in T>) {
        source.subscribe(SuspendableDemandSubscriber(actual, suspended))
    }

    fun suspendDemand() {
        suspended.set(true)
    }

    private class SuspendableDemandSubscriber<T : Any>(
        private val actual: CoreSubscriber<in T>,
        private val suspended: AtomicBoolean,
    ) : CoreSubscriber<T>,
        Subscription {
        private lateinit var upstream: Subscription

        override fun currentContext(): Context = actual.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            if (Operators.validate(if (::upstream.isInitialized) upstream else null, subscription)) {
                upstream = subscription
                actual.onSubscribe(this)
            }
        }

        override fun onNext(value: T) = actual.onNext(value)

        override fun onError(error: Throwable) = actual.onError(error)

        override fun onComplete() = actual.onComplete()

        override fun request(n: Long) {
            if (!suspended.get()) {
                upstream.request(n)
            }
        }

        override fun cancel() = upstream.cancel()
    }
}
