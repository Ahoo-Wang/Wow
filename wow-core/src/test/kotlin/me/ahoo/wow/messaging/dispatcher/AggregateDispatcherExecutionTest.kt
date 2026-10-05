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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.execution.KeyedExecutor
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatchers on the runtime's [KeyedExecutor] (design X7): thread count bounded by the executor, not by the number of
 * aggregate types; per-aggregate order; one aggregate's wait does not delay another's.
 */
class AggregateDispatcherExecutionTest {

    @Test
    fun `dispatchers of many aggregate types share the runtime's workers and keep per-aggregate order`() {
        val keyedExecutor = KeyedExecutor(workers = 2, name = "shared-dispatch-workers")
        val handlerThreads = ConcurrentHashMap.newKeySet<String>()
        val order = ConcurrentHashMap<Pair<Int, String>, MutableList<Int>>()
        val handledCount = AtomicInteger()
        val aggregateTypes = 30
        val exchangesPerType = 200
        val dispatchers = (0 until aggregateTypes).map { type ->
            val exchanges = (0 until exchangesPerType).map { sequence ->
                ExecutionExchange(aggregateId = "aggregate-${sequence % 7}", sequence = sequence)
            }
            ExecutionDispatcher(
                name = "aggregate-type-$type",
                messageFlux = Flux.fromIterable(exchanges),
            ) { exchange ->
                handlerThreads += Thread.currentThread().name
                order.computeIfAbsent(type to exchange.aggregateId) { CopyOnWriteArrayList() } += exchange.sequence
                val wait = if (exchange.sequence % 3 == 0) Mono.delay(Duration.ofNanos(1)).then() else Mono.empty()
                wait.doOnTerminate { handledCount.incrementAndGet() }
            }
        }
        val runtime = WowRuntime(
            components = dispatchers,
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ZERO,
            keyedExecutor = keyedExecutor,
        )
        runtime.start().block()
        try {
            dispatchers.forEach { it.terminatedSignal.block(Duration.ofSeconds(10)) }

            handledCount.get().assert().isEqualTo(aggregateTypes * exchangesPerType)
            handlerThreads.forEach { it.assert().startsWith("shared-dispatch-workers-") }
            handlerThreads.size.assert().isLessThanOrEqualTo(2)
            Thread.getAllStackTraces().keys
                .count { it.name.startsWith("shared-dispatch-workers-") }
                .assert().isLessThanOrEqualTo(2)
            order.size.assert().isEqualTo(aggregateTypes * 7)
            order.forEach { (key, sequences) ->
                sequences.assert().describedAs("order of $key").isSorted()
            }
        } finally {
            runtime.stopGracefully().block(Duration.ofSeconds(10))
        }
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a waiting aggregate does not delay another aggregate of the same dispatcher`() {
        val release = Sinks.empty<Void>()
        val otherHandled = CountDownLatch(1)
        val dispatcher = ExecutionDispatcher(
            name = "waiting-aggregate",
            messageFlux = Flux.just(
                ExecutionExchange(aggregateId = "waiting", sequence = 0),
                ExecutionExchange(aggregateId = "other", sequence = 1),
            ),
        ) { exchange ->
            if (exchange.aggregateId == "waiting") {
                release.asMono()
            } else {
                Mono.fromRunnable { otherHandled.countDown() }
            }
        }
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        try {
            otherHandled.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            release.tryEmitEmpty()
            dispatcher.terminatedSignal.block(Duration.ofSeconds(5))
        }
    }

    private class ExecutionDispatcher(
        override val name: String,
        override val messageFlux: Flux<ExecutionExchange>,
        private val handle: (ExecutionExchange) -> Mono<Void>,
    ) : AggregateDispatcher<ExecutionExchange>() {
        override val namedAggregate: NamedAggregate =
            "wow-core-test.execution_aggregate".toNamedAggregate().materialize()

        override fun ExecutionExchange.mailboxKey(): Any = aggregateId

        override fun handleExchange(exchange: ExecutionExchange): Mono<Void> = handle(exchange)
    }

    private class ExecutionExchange(
        val aggregateId: String,
        val sequence: Int,
    ) : MessageExchange<ExecutionExchange, TestNamedMessage> {
        override val message: TestNamedMessage = TestNamedMessage()
        override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    }
}
