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
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.util.context.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DemandGateFluxTest {

    @Test
    fun `gate accumulates demand until it opens`() {
        val upstreamDemand = AtomicLong()
        val values = mutableListOf<Int>()
        val gate = DemandGateFlux(
            Flux.range(1, 3)
                .doOnRequest(upstreamDemand::addAndGet),
        )
        val subscriber = RecordingSubscriber(values)

        gate.subscribe(subscriber)
        subscriber.requestItems(2)
        upstreamDemand.get().assert().isZero()

        gate.open()

        upstreamDemand.get().assert().isEqualTo(2)
        values.assert().containsExactly(1, 2)
        subscriber.cancel()
    }

    @Test
    fun `cancelling before open cancels upstream without requesting`() {
        val cancelled = AtomicBoolean()
        val upstreamDemand = AtomicLong()
        val gate = DemandGateFlux(
            Flux.never<Int>()
                .doOnRequest(upstreamDemand::addAndGet)
                .doOnCancel { cancelled.set(true) },
        )
        val subscriber = RecordingSubscriber(mutableListOf())

        gate.subscribe(subscriber)
        subscriber.requestItems(1)
        subscriber.cancel()
        gate.open()

        cancelled.get().assert().isTrue()
        upstreamDemand.get().assert().isZero()
    }

    @Test
    fun `detaching cancellation closes intake before invoking upstream cleanup`() {
        val cancelled = AtomicBoolean()
        val source = Sinks.many().unicast().onBackpressureBuffer<Int>()
        val values = mutableListOf<Int>()
        val gate = DemandGateFlux(
            source.asFlux()
                .doOnCancel { cancelled.set(true) },
        )
        val subscriber = RecordingSubscriber(values)
        gate.subscribe(subscriber)
        subscriber.requestItems(1)
        gate.open()

        val cancellation = gate.detachCancellation()

        cancellation.assert().isNotNull()
        cancelled.get().assert().isFalse()
        source.tryEmitNext(1).orThrow()
        values.assert().isEmpty()
        cancellation!!.invoke()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `detaching before subscription cancels a late upstream`() {
        val cancelled = AtomicBoolean()
        val subscribed = AtomicBoolean()
        val gate = DemandGateFlux(
            Flux.never<Int>()
                .doOnCancel { cancelled.set(true) },
        )

        gate.detachCancellation().assert().isNull()
        gate.subscribe(
            object : CoreSubscriber<Int> {
                override fun currentContext(): Context = Context.empty()

                override fun onSubscribe(subscription: Subscription) {
                    subscribed.set(true)
                }

                override fun onNext(value: Int) = Unit

                override fun onError(error: Throwable) = Unit

                override fun onComplete() = Unit
            },
        )

        subscribed.get().assert().isTrue()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `late upstream cancellation is dispatched away from the subscription thread`() {
        val cancellation = AtomicReference<(() -> Unit)?>()
        val cancelled = AtomicBoolean()
        val source = object : Flux<Int>() {
            override fun subscribe(actual: reactor.core.CoreSubscriber<in Int>) {
                actual.onSubscribe(
                    object : Subscription {
                        override fun request(n: Long) = Unit

                        override fun cancel() {
                            cancelled.set(true)
                        }
                    },
                )
            }
        }
        val gate = DemandGateFlux(source) { action ->
            check(cancellation.compareAndSet(null, action))
        }
        gate.detachCancellation()

        gate.subscribe(RecordingSubscriber(mutableListOf()))

        cancelled.get().assert().isFalse()
        checkNotNull(cancellation.get()).invoke()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `opening after upstream termination does not request`() {
        val upstreamDemand = AtomicLong()
        val gate = DemandGateFlux(
            Flux.create<Int> { sink ->
                sink.onRequest(upstreamDemand::addAndGet)
                sink.complete()
            },
        )
        val subscriber = RecordingSubscriber(mutableListOf())

        gate.subscribe(subscriber)
        subscriber.requestItems(1)
        gate.open()

        upstreamDemand.get().assert().isZero()
    }

    @Test
    fun `concurrent open and request never lose demand`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val valueReceived = CountDownLatch(1)
                val values = AtomicInteger()
                val gate = DemandGateFlux(Flux.just(1))
                val subscriber = object : BaseSubscriber<Int>() {
                    fun requestOne() = request(1)

                    override fun hookOnSubscribe(subscription: Subscription) = Unit

                    override fun hookOnNext(value: Int) {
                        values.addAndGet(value)
                        valueReceived.countDown()
                    }
                }
                gate.subscribe(subscriber)
                val start = CountDownLatch(1)

                val open = CompletableFuture.runAsync(
                    {
                        start.await()
                        gate.open()
                    },
                    executor,
                )
                val request = CompletableFuture.runAsync(
                    {
                        start.await()
                        subscriber.requestOne()
                    },
                    executor,
                )
                start.countDown()
                CompletableFuture.allOf(open, request).get(1, TimeUnit.SECONDS)

                valueReceived.await(1, TimeUnit.SECONDS).assert().isTrue()
                values.get().assert().isEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private class RecordingSubscriber(
        private val values: MutableList<Int>,
    ) : BaseSubscriber<Int>() {
        fun requestItems(count: Long) = request(count)

        override fun hookOnSubscribe(subscription: Subscription) = Unit

        override fun hookOnNext(value: Int) {
            values += value
        }
    }
}
