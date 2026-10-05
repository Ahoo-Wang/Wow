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

package me.ahoo.wow.execution

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import reactor.util.retry.Retry
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class KeyedDispatchTest {
    private val executor = KeyedExecutor(workers = 4, maxInFlight = 64, name = "keyed-dispatch-test")

    @AfterAll
    fun close() {
        executor.close()
    }

    private data class Item(val key: Int, val sequence: Int)

    @Test
    fun `elements of one key run one at a time in arrival order while keys run in parallel`() {
        val keys = 200
        val perKey = 25
        val items = (0 until perKey).flatMap { sequence -> (0 until keys).map { Item(it, sequence) } }
        val seen = ConcurrentHashMap<Int, MutableList<Int>>()
        val active = ConcurrentHashMap<Int, AtomicInteger>()
        val overlap = AtomicBoolean()
        val maxParallel = AtomicInteger()
        val running = AtomicInteger()

        val dispatched = Flux.fromIterable(items).dispatchKeyed(executor, Item::key) { item ->
            Mono.defer {
                if (active.computeIfAbsent(item.key) { AtomicInteger() }.incrementAndGet() != 1) {
                    overlap.set(true)
                }
                maxParallel.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                seen.computeIfAbsent(item.key) { Collections.synchronizedList(mutableListOf()) }.add(item.sequence)
                // Half the handlers complete asynchronously, off the worker that started them.
                val delay = if (ThreadLocalRandom.current().nextBoolean()) {
                    Mono.delay(Duration.ofNanos(ThreadLocalRandom.current().nextLong(200_000))).then()
                } else {
                    Mono.empty()
                }
                // Before the completion signal: the mailbox may start the next element as soon as it is signalled.
                delay.doOnTerminate {
                    running.decrementAndGet()
                    active.getValue(item.key).decrementAndGet()
                }
            }
        }

        StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(30))

        overlap.get().assert().isFalse()
        seen.keys.assert().hasSize(keys)
        seen.values.forEach { sequences -> sequences.assert().isEqualTo((0 until perKey).toList()) }
        maxParallel.get().assert().isGreaterThan(1)
    }

    @Test
    fun `handlers run only on the executor workers`() {
        val threads = ConcurrentHashMap.newKeySet<String>()
        val dispatched = Flux.range(0, 2_000).dispatchKeyed(executor, { it % 97 }) {
            Mono.fromRunnable<Void> { threads += Thread.currentThread().name }
                .then(if (it % 3 == 0) Mono.delay(Duration.ofNanos(1)).then() else Mono.empty())
        }

        StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(30))

        threads.assert().isNotEmpty()
        threads.forEach { it.assert().startsWith("keyed-dispatch-test-") }
        threads.size.assert().isLessThanOrEqualTo(executor.workers)
    }

    @Test
    fun `a waiting key does not delay other keys`() {
        val release = Sinks.empty<Void>()
        val othersDone = CountDownLatch(50)
        val dispatched = Flux.range(0, 51).dispatchKeyed(executor, { it }) { key ->
            if (key == 0) {
                release.asMono()
            } else {
                Mono.fromRunnable { othersDone.countDown() }
            }
        }
        val completed = AtomicBoolean()
        val subscription = dispatched.doOnSuccess { completed.set(true) }.subscribe()

        othersDone.await(5, TimeUnit.SECONDS).assert().isTrue()
        completed.get().assert().isFalse()
        release.tryEmitEmpty().orThrow()
        awaitTrue { completed.get() }
        subscription.dispose()
    }

    @Test
    fun `a retry backoff delays only its own key and holds no worker`() {
        val singleWorker = KeyedExecutor(workers = 1, name = "keyed-dispatch-retry")
        try {
            val attempts = AtomicInteger()
            val retriedAt = AtomicLong()
            val otherDoneAt = ConcurrentHashMap<Int, Long>()
            val start = System.nanoTime()
            val dispatched = Flux.range(0, 20).dispatchKeyed(singleWorker, { it }) { key ->
                if (key == 0) {
                    Mono.defer {
                        if (attempts.incrementAndGet() < 3) {
                            Mono.error(IllegalStateException("conflict"))
                        } else {
                            Mono.fromRunnable<Void> { retriedAt.set(System.nanoTime()) }
                        }
                    }.retryWhen(Retry.backoff(3, Duration.ofMillis(200)).jitter(0.0))
                } else {
                    Mono.fromRunnable { otherDoneAt[key] = System.nanoTime() }
                }
            }

            StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(10))

            attempts.get().assert().isEqualTo(3)
            otherDoneAt.keys.assert().hasSize(19)
            val retriedAfter = Duration.ofNanos(retriedAt.get() - start)
            retriedAfter.toMillis().assert().isGreaterThanOrEqualTo(600)
            // The one worker served all other keys while key 0 was backing off.
            otherDoneAt.values.forEach { doneAt -> doneAt.assert().isLessThan(retriedAt.get()) }
        } finally {
            singleWorker.close()
        }
    }

    @Test
    fun `at most maxInFlight elements are requested before any finishes`() {
        val bounded = KeyedExecutor(workers = 2, maxInFlight = 8, name = "keyed-dispatch-bounded")
        try {
            val requested = AtomicLong()
            val release = Sinks.empty<Void>()
            val started = AtomicInteger()
            val source = Flux.range(0, 100).doOnRequest { requested.addAndGet(it) }
            val dispatched = source.dispatchKeyed(bounded, { it }) {
                started.incrementAndGet()
                release.asMono()
            }
            val completed = AtomicBoolean()
            val subscription = dispatched.doOnSuccess { completed.set(true) }.subscribe()

            awaitTrue { started.get() == 8 }
            requested.get().assert().isEqualTo(8)
            release.tryEmitEmpty().orThrow()
            awaitTrue { completed.get() }
            started.get().assert().isEqualTo(100)
            subscription.dispose()
        } finally {
            bounded.close()
        }
    }

    @Test
    fun `completion waits for every accepted element`() {
        val finished = AtomicInteger()
        val dispatched = Flux.range(0, 10).dispatchKeyed(executor, { it % 2 }) {
            Mono.delay(Duration.ofMillis(5)).doOnSuccess { finished.incrementAndGet() }.then()
        }

        StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(5))
        finished.get().assert().isEqualTo(10)
    }

    @Test
    fun `a long synchronous run of one key is rescheduled fairly and stays ordered`() {
        val seen = CopyOnWriteArrayList<Int>()
        val dispatched = Flux.range(0, 500).dispatchKeyed(executor, { "one" }) {
            Mono.fromRunnable { seen += it }
        }

        StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(5))
        seen.assert().isEqualTo((0 until 500).toList())
    }

    @Test
    fun `a handler error fails the dispatch, cancels running handlers and discards queued elements`() {
        val failure = IllegalStateException("handler")
        val cancelled = AtomicBoolean()
        val discarded = CopyOnWriteArrayList<Any>()
        val blocked = CountDownLatch(1)
        val source = Sinks.many().unicast().onBackpressureBuffer<Int>()
        val dispatched = source.asFlux().dispatchKeyed(executor, { if (it == 9) 9 else 0 }) { value ->
            when (value) {
                0 -> Mono.never<Void>().doOnSubscribe { blocked.countDown() }.doOnCancel { cancelled.set(true) }
                9 -> Mono.error(failure)
                else -> Mono.empty()
            }
        }.doOnDiscard(Int::class.javaObjectType) { discarded += it }

        val verifier = StepVerifier.create(dispatched)
            .then {
                source.tryEmitNext(0).orThrow()
                blocked.await(5, TimeUnit.SECONDS).assert().isTrue()
                source.tryEmitNext(1).orThrow()
                source.tryEmitNext(2).orThrow()
                source.tryEmitNext(9).orThrow()
            }
            .expectErrorMatches { it === failure }
            .verifyLater()
        verifier.verify(Duration.ofSeconds(5))

        awaitTrue { cancelled.get() }
        awaitTrue { discarded.containsAll(listOf(1, 2)) }
        source.tryEmitNext(3)
        discarded.assert().doesNotContain(0, 9)
    }

    @Test
    fun `a source error cancels running handlers and fails the dispatch`() {
        val failure = IllegalStateException("source")
        val cancelled = CountDownLatch(1)
        val started = CountDownLatch(1)
        val source = Sinks.many().unicast().onBackpressureBuffer<Int>()
        val dispatched = source.asFlux().dispatchKeyed(executor, { it }) {
            Mono.never<Void>().doOnSubscribe { started.countDown() }.doOnCancel { cancelled.countDown() }
        }

        StepVerifier.create(dispatched)
            .then {
                source.tryEmitNext(1).orThrow()
                started.await(5, TimeUnit.SECONDS).assert().isTrue()
                source.tryEmitError(failure).orThrow()
            }
            .expectErrorMatches { it === failure }
            .verify(Duration.ofSeconds(5))
        cancelled.await(5, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `a key selector failure fails the dispatch and discards the element`() {
        val failure = IllegalArgumentException("key")
        val discarded = CopyOnWriteArrayList<Any>()
        val dispatched = Flux.just(1).dispatchKeyed(executor, { throw failure }) { Mono.empty() }
            .doOnDiscard(Int::class.javaObjectType) { discarded += it }

        StepVerifier.create(dispatched).expectErrorMatches { it === failure }.verify(Duration.ofSeconds(5))
        discarded.assert().containsExactly(1)
    }

    @Test
    fun `a handler that throws instead of returning fails the dispatch`() {
        val failure = IllegalStateException("throw")
        val dispatched = Flux.just(1).dispatchKeyed(executor, { it }) { throw failure }

        StepVerifier.create(dispatched).expectErrorMatches { it === failure }.verify(Duration.ofSeconds(5))
    }

    @Test
    fun `cancel stops the source, cancels running handlers and discards queued and late elements`() {
        val cancelled = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sourceCancelled = AtomicBoolean()
        val discarded = CopyOnWriteArrayList<Any>()
        val source = Sinks.many().multicast().directBestEffort<Int>()
        val dispatched = source.asFlux()
            .doOnCancel { sourceCancelled.set(true) }
            .dispatchKeyed(executor, { 0 }) {
                Mono.never<Void>().doOnSubscribe { started.countDown() }.doOnCancel { cancelled.countDown() }
            }
            .doOnDiscard(Int::class.javaObjectType) { discarded += it }
        val subscription = dispatched.subscribe()

        source.tryEmitNext(1).orThrow()
        started.await(5, TimeUnit.SECONDS).assert().isTrue()
        source.tryEmitNext(2).orThrow()
        subscription.dispose()

        cancelled.await(5, TimeUnit.SECONDS).assert().isTrue()
        sourceCancelled.get().assert().isTrue()
        discarded.assert().containsExactly(2)
    }

    @Test
    fun `a closed executor rejects the element and fails the dispatch`() {
        val closed = KeyedExecutor(workers = 1, name = "keyed-dispatch-closed").apply { close() }
        val discarded = CopyOnWriteArrayList<Any>()
        val dispatched = Flux.just(1).dispatchKeyed(closed, { it }) { Mono.empty() }
            .doOnDiscard(Int::class.javaObjectType) { discarded += it }

        StepVerifier.create(dispatched)
            .expectError(RejectedExecutionException::class.java)
            .verify(Duration.ofSeconds(5))
        discarded.assert().containsExactly(1)
        closed.isDisposed.assert().isTrue()
    }

    @Test
    fun `handlers see the executor's coroutine dispatcher in their context`() {
        val dispatched = Flux.just(1).dispatchKeyed(executor, { it }) {
            Mono.deferContextual { context ->
                KeyedExecutorContext.coroutineDispatcherOf(context).assert().isSameAs(executor.coroutineDispatcher)
                Mono.empty()
            }
        }

        StepVerifier.create(dispatched).expectComplete().verify(Duration.ofSeconds(5))
    }

    @Test
    fun `executor validates its configuration`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { KeyedExecutor(workers = 0) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { KeyedExecutor(maxInFlight = 0) }
        KeyedExecutor.shared.assert().isSameAs(KeyedExecutor.shared)
        executor.toString().assert().contains("keyed-dispatch-test")
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time." }
            Thread.sleep(5)
        }
    }
}
