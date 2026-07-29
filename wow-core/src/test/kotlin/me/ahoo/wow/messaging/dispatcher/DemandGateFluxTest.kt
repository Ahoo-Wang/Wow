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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

class DemandGateFluxTest {

    @Test
    fun `withholds demand until opened`() {
        val received = mutableListOf<Int>()
        val gate = DemandGateFlux(Flux.just(1))

        gate.subscribe(received::add)

        received.assert().isEmpty()
        gate.open()
        received.assert().containsExactly(1)
    }

    @Test
    fun `close before upstream subscription preserves reactive streams ordering`() {
        val source = DelayedPublisher<Int>()
        val gate = DemandGateFlux(Flux.from(source))
        val completed = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val subscriber = object : BaseSubscriber<Int>() {
            override fun hookOnSubscribe(subscription: Subscription) {
                request(1)
            }

            override fun hookOnComplete() {
                completed.set(true)
            }
        }

        gate.subscribe(subscriber)
        gate.close()
        completed.get().assert().isFalse()

        source.connect(cancelled)

        cancelled.get().assert().isTrue()
        completed.get().assert().isTrue()
    }

    private class DelayedPublisher<T : Any> : Publisher<T> {
        private lateinit var subscriber: Subscriber<in T>

        override fun subscribe(subscriber: Subscriber<in T>) {
            this.subscriber = subscriber
        }

        fun connect(cancelled: AtomicBoolean) {
            subscriber.onSubscribe(
                object : Subscription {
                    override fun request(amount: Long) = Unit

                    override fun cancel() {
                        cancelled.set(true)
                    }
                }
            )
        }
    }
}
