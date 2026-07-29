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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RuntimeComponentGroupTest {

    @Test
    fun `duplicate component identity is rejected`() {
        val component = RecordingComponent("component", mutableListOf())

        assertThrows<IllegalArgumentException> {
            RuntimeComponentGroup(listOf(component, component), reportFailure = {})
        }
    }

    @Test
    fun `admission gate can reject lifecycle entry atomically`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup(
            listOf(RecordingComponent("component", calls)),
            reportFailure = {},
        )

        val prepared = group.prepare(
            runtimeContext = DefaultRuntimeContext(),
            admissionGate = { false },
        )

        prepared.assert().isFalse()
        calls.assert().isEmpty()
    }

    @Test
    fun `group provides a readiness barrier and reverse cleanup`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup(
            listOf(
                RecordingComponent("first", calls),
                RecordingComponent("second", calls),
            ),
            reportFailure = {},
        )

        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        group.start().assert().isTrue()
        StepVerifier.create(group.stopGracefully()).verifyComplete()

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
            "stop:second",
            "stop:first",
        )
    }

    @Test
    fun `force stop covers every registered component before preparation`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup(
            listOf(
                RecordingComponent("first", calls),
                RecordingComponent("second", calls),
            ),
            reportFailure = {},
        )

        group.forceStop()

        calls.assert().containsExactly("force:second", "force:first")
        group.prepare(DefaultRuntimeContext()).assert().isFalse()
    }

    @Test
    fun `group force stop prevents a physically uncancelled graceful chain from advancing`() {
        val calls = mutableListOf<String>()
        val stopEntered = CountDownLatch(1)
        val stopGate = Sinks.empty<Void>()
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent(
            name = "second",
            calls = calls,
            stopAction = {
                stopEntered.countDown()
                stopGate.asMono()
            },
        )
        val group = RuntimeComponentGroup(listOf(first, second), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val gracefulStop = group.stopGracefully().toFuture()

        stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        group.forceStop()
        stopGate.tryEmitEmpty().orThrow()
        gracefulStop.get(1, TimeUnit.SECONDS)

        calls.assert().contains("stop:second")
        calls.assert().doesNotContain("stop:first")
    }

    @Test
    fun `force stop compensates resources acquired by overlapping graceful method entry`() {
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val forceCount = AtomicInteger()
        val gracefulSubscriptionCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> {
                stopEntered.countDown()
                releaseStop.await()
                return Mono.defer {
                    gracefulSubscriptionCount.incrementAndGet()
                    Mono.empty()
                }
            }

            override fun forceStop() {
                forceCount.incrementAndGet()
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val executor = Executors.newSingleThreadExecutor()
        val gracefulStop = CompletableFuture.runAsync(
            { group.stopGracefully().block() },
            executor,
        )

        try {
            stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            group.forceStop()
            forceCount.get().assert().isOne()
            releaseStop.countDown()
            gracefulStop.get(1, TimeUnit.SECONDS)

            forceCount.get().assert().isEqualTo(2)
            gracefulSubscriptionCount.get().assert().isZero()
        } finally {
            releaseStop.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `synchronous graceful failure remains primary when compensation fails`() {
        val gracefulFailure = IllegalStateException("graceful")
        val compensationFailure = IllegalArgumentException("compensation")
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val forceCount = AtomicInteger()
        val reportedFailures = CopyOnWriteArrayList<Throwable>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> {
                stopEntered.countDown()
                releaseStop.await()
                throw gracefulFailure
            }

            override fun forceStop() {
                if (forceCount.incrementAndGet() == 2) {
                    throw compensationFailure
                }
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportedFailures::add)
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val executor = Executors.newSingleThreadExecutor()
        val gracefulStop = CompletableFuture.supplyAsync(
            {
                group.stopGracefully().materialize().block()!!.throwable
            },
            executor,
        )

        try {
            stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            group.forceStop()
            releaseStop.countDown()

            val failure = gracefulStop.get(1, TimeUnit.SECONDS)
            failure.assert().isSameAs(gracefulFailure)
            failure!!.suppressedExceptions.assert().containsExactly(compensationFailure)
            forceCount.get().assert().isEqualTo(2)
            reportedFailures.take(2).assert()
                .containsExactly(gracefulFailure, compensationFailure)
        } finally {
            releaseStop.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop compensates a cold graceful subscription race`() {
        val subscriptionEntered = CountDownLatch(1)
        val releaseSubscription = CountDownLatch(1)
        val resourceOwned = AtomicInteger()
        val forceCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.defer {
                    subscriptionEntered.countDown()
                    releaseSubscription.await()
                    resourceOwned.incrementAndGet()
                    Mono.empty()
                }

            override fun forceStop() {
                forceCount.incrementAndGet()
                resourceOwned.set(0)
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val executor = Executors.newSingleThreadExecutor()
        val gracefulStop = CompletableFuture.runAsync(
            { group.stopGracefully().block() },
            executor,
        )

        try {
            subscriptionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            group.forceStop()
            forceCount.get().assert().isOne()
            releaseSubscription.countDown()
            gracefulStop.get(1, TimeUnit.SECONDS)

            forceCount.get().assert().isEqualTo(2)
            resourceOwned.get().assert().isZero()
        } finally {
            releaseSubscription.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop compensates a resource acquired after subscription`() {
        val executionEntered = CountDownLatch(1)
        val releaseExecution = CountDownLatch(1)
        val resourceOwned = AtomicInteger()
        val forceCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.fromRunnable {
                    executionEntered.countDown()
                    releaseExecution.await()
                    resourceOwned.incrementAndGet()
                }

            override fun forceStop() {
                forceCount.incrementAndGet()
                resourceOwned.set(0)
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val executor = Executors.newSingleThreadExecutor()
        val gracefulStop = CompletableFuture.runAsync(
            { group.stopGracefully().block() },
            executor,
        )

        try {
            executionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            group.forceStop()
            forceCount.get().assert().isOne()
            releaseExecution.countDown()
            gracefulStop.get(1, TimeUnit.SECONDS)

            forceCount.get().assert().isEqualTo(2)
            resourceOwned.get().assert().isZero()
        } finally {
            releaseExecution.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `graceful failure remains primary when force compensation fails`() {
        val gracefulFailure = IllegalStateException("graceful")
        val compensationFailure = IllegalArgumentException("compensation")
        val stopSubscribed = CountDownLatch(1)
        val stopSignal = Sinks.empty<Void>()
        val forceCount = AtomicInteger()
        val reportedFailures = CopyOnWriteArrayList<Throwable>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                stopSignal.asMono().doOnSubscribe {
                    stopSubscribed.countDown()
                }

            override fun forceStop() {
                if (forceCount.incrementAndGet() == 2) {
                    throw compensationFailure
                }
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportedFailures::add)
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val result = group.stopGracefully().materialize().toFuture()

        stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        group.forceStop()
        stopSignal.tryEmitError(gracefulFailure).orThrow()

        val failure = result.get(1, TimeUnit.SECONDS)!!.throwable!!
        failure.assert().isSameAs(gracefulFailure)
        failure.suppressedExceptions.assert().containsExactly(compensationFailure)
        reportedFailures.take(2).assert()
            .containsExactly(gracefulFailure, compensationFailure)
    }

    @Test
    fun `force compensation failure terminates an otherwise graceful stop`() {
        val compensationFailure = IllegalArgumentException("compensation")
        val stopSubscribed = CountDownLatch(1)
        val stopSignal = Sinks.empty<Void>()
        val forceCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                stopSignal.asMono().doOnSubscribe {
                    stopSubscribed.countDown()
                }

            override fun forceStop() {
                if (forceCount.incrementAndGet() == 2) {
                    throw compensationFailure
                }
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val result = group.stopGracefully().materialize().toFuture()

        stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        group.forceStop()
        stopSignal.tryEmitEmpty().orThrow()

        result.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(compensationFailure)
    }

    @Test
    fun `cancellation reports force compensation failure without downstream signaling`() {
        val compensationFailure = IllegalArgumentException("compensation")
        val stopSubscribed = CountDownLatch(1)
        val forceCount = AtomicInteger()
        val reportedFailures = CopyOnWriteArrayList<Throwable>()
        val downstreamFailure = AtomicReference<Throwable?>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.never<Void>().doOnSubscribe {
                    stopSubscribed.countDown()
                }

            override fun forceStop() {
                if (forceCount.incrementAndGet() == 2) {
                    throw compensationFailure
                }
            }
        }
        val group = RuntimeComponentGroup(listOf(component), reportedFailures::add)
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val gracefulStop = group.stopGracefully().subscribe({}, downstreamFailure::set)

        stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        group.forceStop()
        gracefulStop.dispose()

        forceCount.get().assert().isEqualTo(2)
        reportedFailures.assert().containsExactly(compensationFailure)
        downstreamFailure.get().assert().isNull()
    }

    @Test
    fun `cancelling graceful stop releases the component lifecycle slot`() {
        val subscriptionCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                if (subscriptionCount.getAndIncrement() == 0) {
                    Mono.never()
                } else {
                    Mono.empty()
                }

            override fun forceStop() = Unit
        }
        val group = RuntimeComponentGroup(listOf(component), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()

        group.stopGracefully().subscribe().dispose()

        StepVerifier.create(group.stopGracefully()).verifyComplete()
        subscriptionCount.get().assert().isEqualTo(2)
    }

    private class RecordingComponent(
        private val name: String,
        private val calls: MutableList<String>,
        private val stopAction: () -> Mono<Void> = { Mono.empty() },
    ) : RuntimeComponent {
        override fun prepare(runtimeContext: RuntimeContext) {
            calls += "prepare:$name"
        }

        override fun start() {
            calls += "start:$name"
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.defer {
                calls += "stop:$name"
                stopAction()
            }

        override fun forceStop() {
            calls += "force:$name"
        }
    }
}
