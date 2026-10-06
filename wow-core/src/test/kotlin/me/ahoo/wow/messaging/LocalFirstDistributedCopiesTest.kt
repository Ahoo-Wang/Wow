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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.metrics.getMetricsSubscriber
import me.ahoo.wow.metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LocalFirstDistributedCopiesTest {
    private val aggregate = "context.aggregate".toNamedAggregate()
    private val sent = CopyOnWriteArrayList<Pair<String, Boolean>>()

    @BeforeEach
    fun clearSent() {
        sent.clear()
    }

    private fun LocalFirstDistributedCopies.enqueue(
        id: String,
        key: Any = "aggregate-1",
        send: Mono<Void> = Mono.empty(),
    ) = enqueue(key, aggregate, Context.empty(), { "message[$id]" }) { admitted ->
        send.doOnSubscribe { sent += id to admitted }
    }

    @Test
    fun `an aggregate's copies are sent in enqueue order whatever order the decisions arrive in`() {
        val copies = LocalFirstDistributedCopies()
        val firstAdmission = Sinks.one<Boolean>()
        val first = copies.enqueue("1")
        val second = copies.enqueue("2")
        val third = copies.enqueue("3")

        // The refused third and the admitted second are decided first; they wait for the first's admission.
        third.decide(LocalHandoff.REFUSED)
        second.decide(LocalHandoff.accepted(Mono.just(true)))
        first.decide(LocalHandoff.accepted(firstAdmission.asMono()))
        sent.assert().isEmpty()
        copies.pending.assert().isEqualTo(3)
        copies.backlog(aggregate).assert().isEqualTo(3)

        firstAdmission.tryEmitValue(false).orThrow()

        sent.assert().containsExactly("1" to false, "2" to true, "3" to false)
        third.sent.test().verifyComplete()
        copies.pending.assert().isZero()
        copies.backlog(aggregate).assert().isZero()
    }

    @Test
    fun `different aggregates do not wait for each other`() {
        val copies = LocalFirstDistributedCopies()
        copies.enqueue("blocked", key = "aggregate-1").decide(LocalHandoff.accepted(Mono.never()))

        copies.enqueue("other", key = "aggregate-2").decide(LocalHandoff.REFUSED)

        sent.assert().containsExactly("other" to false)
        copies.pending.assert().isEqualTo(1)
    }

    @Test
    fun `a long run of copies completing synchronously does not overflow the stack`() {
        val copies = LocalFirstDistributedCopies()
        val blocker = Sinks.one<Boolean>()
        copies.enqueue("0").decide(LocalHandoff.accepted(blocker.asMono()))
        (1..20_000).forEach { copies.enqueue("$it").decide(LocalHandoff.REFUSED) }

        blocker.tryEmitValue(true).orThrow()

        sent.size.assert().isEqualTo(20_001)
        copies.pending.assert().isZero()
    }

    @Test
    fun `only the first decision counts`() {
        val copies = LocalFirstDistributedCopies()
        val copy = copies.enqueue("1")

        copy.decide(LocalHandoff.accepted(Mono.just(true)))
        copy.decide(LocalHandoff.REFUSED)

        sent.assert().containsExactly("1" to true)
    }

    @Test
    fun `a failed admission or one that never answers sends the copy unsuppressed`() {
        val copies = LocalFirstDistributedCopies()

        copies.enqueue("error").decide(LocalHandoff.accepted(Mono.error(IllegalStateException("closed"))))
        copies.enqueue("empty").decide(LocalHandoff.accepted(Mono.empty()))

        sent.assert().containsExactly("error" to false, "empty" to false)
    }

    @Test
    fun `a failed copy fails only its own sent signal and the queue moves on`() {
        val copies = LocalFirstDistributedCopies()
        val failed = copies.enqueue("1", send = Mono.error(IllegalStateException("broker down")))
        val next = copies.enqueue("2")

        failed.decide(LocalHandoff.REFUSED)
        next.decide(LocalHandoff.REFUSED)

        failed.sent.test().expectErrorMessage("broker down").verify()
        next.sent.test().verifyComplete()
        copies.pending.assert().isZero()
    }

    @Test
    fun `the copy keeps only the captured parts of the sender's context`() {
        val copies = LocalFirstDistributedCopies()
        val sender = Mono.deferContextual { Mono.just(it) }.flux()
            .writeMetricsSubscriber("subscriber-1")
            .contextWrite(Context.of("request", "web-request"))
            .blockFirst()!!
        val seen = AtomicReference<ContextView>()
        val copy = copies.enqueue("aggregate-1", aggregate, sender, { "message[1]" }) {
            Mono.deferContextual { context ->
                seen.set(context)
                Mono.empty()
            }
        }

        copy.decide(LocalHandoff.REFUSED)

        seen.get().getMetricsSubscriber().assert().isEqualTo("subscriber-1")
        seen.get().hasKey("request").assert().isFalse()
    }

    @Test
    fun `a hand-off result that never arrives is a refusal after the timeout`() {
        val copies = LocalFirstDistributedCopies(handOffTimeout = Duration.ofMillis(50))
        val copy = copies.enqueue("1")

        copy.decideFrom(Mono.never()).test().expectNext(LocalHandoff.REFUSED).verifyComplete()
        copy.decideFrom(Mono.error(IllegalStateException("late"))).test()
            .expectNext(LocalHandoff.REFUSED).verifyComplete()

        copy.sent.test().verifyComplete()
        sent.assert().containsExactly("1" to false)
    }

    @Test
    fun `the hand-off timeout must be positive`() {
        assertThrows<IllegalArgumentException> { LocalFirstDistributedCopies(handOffTimeout = Duration.ZERO) }
    }

    @Test
    fun `an aggregate's queue is removed once it is empty`() {
        val copies = LocalFirstDistributedCopies()
        val first = copies.enqueue("1", key = "aggregate-1")
        copies.enqueue("2", key = "aggregate-2").decide(LocalHandoff.REFUSED)
        copies.queues.assert().isEqualTo(1)

        first.decide(LocalHandoff.REFUSED)

        copies.queues.assert().isZero()
        // A later copy of the same aggregate gets a fresh queue.
        copies.enqueue("3", key = "aggregate-1").decide(LocalHandoff.REFUSED)
        sent.assert().containsExactly("2" to false, "1" to false, "3" to false)
        copies.queues.assert().isZero()
    }

    @Test
    fun `concurrent enqueue, decide and finish neither loses a wake-up nor reorders an aggregate`() {
        val copies = LocalFirstDistributedCopies()
        val aggregates = 8
        val perAggregate = 2_000
        val received = ConcurrentHashMap<Int, MutableList<Int>>()
        val finisher = Schedulers.newParallel("copies-finish", 4)
        val decider = Executors.newFixedThreadPool(4)
        val senders = Executors.newFixedThreadPool(aggregates)
        try {
            val done = CountDownLatch(aggregates)
            repeat(aggregates) { aggregateIndex ->
                senders.execute {
                    repeat(perAggregate) { sequence ->
                        val description = { "$aggregateIndex-$sequence" }
                        val copy = copies.enqueue(aggregateIndex, aggregate, Context.empty(), description) {
                            Mono.fromRunnable<Void> {
                                received.computeIfAbsent(aggregateIndex) { CopyOnWriteArrayList() } += sequence
                            }.subscribeOn(finisher)
                        }
                        decider.execute {
                            copy.decide(
                                if (sequence % 2 == 0) {
                                    LocalHandoff.REFUSED
                                } else {
                                    LocalHandoff.accepted(Mono.just(false).subscribeOn(finisher))
                                }
                            )
                        }
                    }
                    done.countDown()
                }
            }
            done.await(30, TimeUnit.SECONDS).assert().isTrue()

            copies.stopGracefully().test().expectComplete().verify(Duration.ofSeconds(30))

            copies.pending.assert().isZero()
            copies.queues.assert().isZero()
            repeat(aggregates) { aggregateIndex ->
                received[aggregateIndex].assert().isEqualTo((0 until perAggregate).toList())
            }
        } finally {
            senders.shutdownNow()
            decider.shutdownNow()
            finisher.dispose()
        }
    }

    @Test
    fun `the backlog is a gauge per aggregate type`() {
        val registry = SimpleMeterRegistry()
        val copies = LocalFirstDistributedCopies("copies", WowMetrics(registry), backlogHighWaterMark = 2)
        copies.enqueue("1").decide(LocalHandoff.accepted(Mono.never()))
        copies.enqueue("2")
        copies.enqueue("3")

        registry.find(LocalFirstDistributedCopies.BACKLOG_METRIC).tag("aggregate", "aggregate").gauge()!!
            .value().assert().isEqualTo(3.0)
        assertThrows<IllegalArgumentException> { LocalFirstDistributedCopies(backlogHighWaterMark = 0) }
    }

    @Test
    fun `a graceful stop waits for the copies to be sent`() {
        val copies = LocalFirstDistributedCopies("copies")
        val inFlight = Sinks.empty<Void>()
        copies.enqueue("1", send = inFlight.asMono()).decide(LocalHandoff.REFUSED)

        val stopped = copies.stopGracefully().toFuture()
        Thread.sleep(50)
        stopped.isDone.assert().isFalse()

        inFlight.tryEmitEmpty().orThrow()
        stopped.get()
        copies.pending.assert().isZero()
    }

    @Test
    fun `a graceful stop with nothing queued completes at once`() {
        val copies = LocalFirstDistributedCopies()

        copies.prepare(DefaultRuntimeContext()).test().verifyComplete()
        copies.start()
        copies.stopGracefully().test().verifyComplete()
        copies.toString().assert().isEqualTo("LocalFirstDistributedCopies")
    }

    @Test
    fun `a force stop cancels the running and the queued copies`() {
        val copies = LocalFirstDistributedCopies()
        val cancelled = AtomicBoolean()
        val running = copies.enqueue("1", send = Mono.never<Void>().doOnCancel { cancelled.set(true) })
        running.decide(LocalHandoff.REFUSED)
        val queued = copies.enqueue("2")

        copies.forceStop()

        cancelled.get().assert().isTrue()
        running.sent.test().expectError().verify()
        queued.sent.test().expectError().verify()
        copies.pending.assert().isZero()
    }

    @Test
    fun `the runtime waits for the queued copies within its shutdown deadline`() {
        val copies = LocalFirstDistributedCopies()
        val inFlight = Sinks.empty<Void>()
        copies.enqueue("1", send = inFlight.asMono()).decide(LocalHandoff.REFUSED)
        val runtime = WowRuntime(
            components = listOf(copies),
            shutdownTimeout = Duration.ofSeconds(5),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block(Duration.ofSeconds(5))

        val stopped = runtime.stopGracefully().toFuture()
        Thread.sleep(50)
        stopped.isDone.assert().isFalse()
        inFlight.tryEmitEmpty().orThrow()

        stopped.get()
        copies.pending.assert().isZero()
    }

    @Test
    fun `the runtime cancels copies still queued at its shutdown deadline`() {
        val copies = LocalFirstDistributedCopies()
        val cancelled = AtomicBoolean()
        copies.enqueue("1", send = Mono.never<Void>().doOnCancel { cancelled.set(true) }).decide(LocalHandoff.REFUSED)
        val runtime = WowRuntime(
            components = listOf(copies),
            shutdownTimeout = Duration.ofMillis(200),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block(Duration.ofSeconds(5))

        runCatching { runtime.stopGracefully().block(Duration.ofSeconds(5)) }

        cancelled.get().assert().isTrue()
    }
}
