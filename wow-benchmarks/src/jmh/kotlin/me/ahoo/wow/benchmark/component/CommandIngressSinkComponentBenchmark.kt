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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.infra.sink.concurrent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.BenchmarkParams
import reactor.core.Disposable
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val EMISSIONS_PER_INVOCATION = 1_000_000
private const val TERMINAL_TIMEOUT_SECONDS = 10L

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class CommandIngressSinkComponentBenchmark {
    @Benchmark
    @OperationsPerInvocation(EMISSIONS_PER_INVOCATION)
    fun emitBatch(
        sharedState: CommandIngressSinkSharedState,
        threadState: CommandIngressSinkThreadState,
    ): Sinks.EmitResult {
        val sink = checkNotNull(sharedState.sink)
        val command = threadState.command
        var emissions = 0
        var result = Sinks.EmitResult.OK
        while (emissions < EMISSIONS_PER_INVOCATION) {
            result = sink.tryEmitNext(command)
            check(result == Sinks.EmitResult.OK) {
                "Command ingress sink emission failed for " +
                    "[${sharedState.strategy}/subscriberCount=${sharedState.subscriberCount}]: $result."
            }
            emissions++
        }
        return result
    }
}

@State(Scope.Benchmark)
open class CommandIngressSinkSharedState {
    @Param(
        "legacy-lock",
        "bare-mpsc",
        "atomic-mpsc",
    )
    lateinit var strategy: String

    @Param("0", "1")
    var subscriberCount: Int = 0

    internal var sink: Sinks.Many<CommandMessage<*>>? = null
    private var subscription: Disposable? = null
    private val subscriberError = AtomicReference<Throwable?>()
    private var consumedCount = 0L
    private var expectedConsumedCount = 0L
    private var terminalCompleted = false
    private lateinit var terminalLatch: CountDownLatch

    @Setup(Level.Iteration)
    fun setup(benchmarkParams: BenchmarkParams) {
        subscription = null
        subscriberError.set(null)
        consumedCount = 0
        expectedConsumedCount = EMISSIONS_PER_INVOCATION.toLong() * benchmarkParams.threads
        terminalCompleted = false
        terminalLatch = CountDownLatch(1)
        sink = createSink()
        if (subscriberCount == 1) {
            subscribe()
        } else {
            check(subscriberCount == 0) {
                "Unsupported subscriber count: $subscriberCount"
            }
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        var primaryFailure: Throwable? = null
        try {
            val currentSink = checkNotNull(sink)
            val emitResult = currentSink.tryEmitComplete()
            check(emitResult == Sinks.EmitResult.OK) {
                "Command ingress sink terminal emission failed for " +
                    "[$strategy/subscriberCount=$subscriberCount]: $emitResult."
            }
            if (subscriberCount == 1) {
                check(terminalLatch.await(TERMINAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Timed out waiting for command ingress sink terminal callback."
                }
                subscriberError.get()?.let { throw it }
                check(terminalCompleted) {
                    "Command ingress sink did not complete normally."
                }
                check(consumedCount == expectedConsumedCount) {
                    "Command ingress sink consumed $consumedCount messages; " +
                        "expected $expectedConsumedCount."
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
        } finally {
            subscription?.let { current ->
                if (!current.isDisposed) {
                    runCatching(current::dispose).exceptionOrNull()?.let { cleanupFailure ->
                        if (primaryFailure == null) {
                            primaryFailure = cleanupFailure
                        } else {
                            primaryFailure.addSuppressed(cleanupFailure)
                        }
                    }
                }
            }
            subscription = null
            sink = null
        }
        primaryFailure?.let { throw it }
    }

    private fun createSink(): Sinks.Many<CommandMessage<*>> =
        when (strategy) {
            "legacy-lock" ->
                Sinks.unsafe()
                    .many()
                    .unicast()
                    .onBackpressureBuffer<CommandMessage<*>>()
                    .concurrent()

            "bare-mpsc" ->
                Sinks.unsafe()
                    .many()
                    .unicast()
                    .onBackpressureBuffer(Queues.unboundedMultiproducer<CommandMessage<*>>().get())

            "atomic-mpsc" -> InMemoryCommandBus().sinkSupplier(BenchmarkAggregates.namedAggregate)
            else -> error("Unsupported command ingress sink strategy: $strategy")
        }

    private fun subscribe() {
        check(subscription == null) {
            "Command ingress sink already has a subscriber."
        }
        val currentSink = checkNotNull(sink)
        subscription = currentSink.asFlux().subscribe(
            { consumedCount++ },
            { error ->
                subscriberError.compareAndSet(null, error)
                terminalLatch.countDown()
            },
            {
                terminalCompleted = true
                terminalLatch.countDown()
            },
        )
        check(currentSink.currentSubscriberCount() == 1) {
            "Command ingress sink must have exactly one subscriber."
        }
    }
}

@State(Scope.Thread)
open class CommandIngressSinkThreadState {
    internal lateinit var command: CommandMessage<*>

    @Setup(Level.Trial)
    fun setup() {
        command = BenchmarkCommands.smokeAddCartItem().withReadOnly()
    }
}
