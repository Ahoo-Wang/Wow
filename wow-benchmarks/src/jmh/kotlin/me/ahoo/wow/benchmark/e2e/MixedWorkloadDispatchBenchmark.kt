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

package me.ahoo.wow.benchmark.e2e

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.WowRuntime
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A realistic dispatcher workload (design X7): one command dispatcher serving
 * - [slowAggregates] aggregates whose handler waits on I/O ([slowIoMillis], non-blocking), each with one command
 *   outstanding at all times (a projection over a slow store, a retry backoff after a version conflict);
 * - one hot aggregate with [hotOutstanding] commands outstanding and a little CPU per command;
 * - the measured traffic: cold commands, each to a new aggregate, fast to handle (the common case).
 *
 * The benchmark measures the round trip of a cold command (send → handled) while the background load runs. Up to
 * 9.2 the dispatcher serialized each of `64 × cores` hash groups: a cold command hashed into the group of a slow
 * aggregate waited for that aggregate's I/O (head-of-line blocking). Since 9.3 every aggregate has its own mailbox,
 * so a cold command waits only for a worker.
 *
 * The background load stays below the default in-flight bound (256), so backpressure does not decide the result.
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class MixedWorkloadDispatchBenchmark {
    /** 0 is the control: no slow aggregates, so only the hot aggregate and the cold traffic run. */
    @Param("0", "16", "128")
    var slowAggregates: Int = 128

    @Param("5")
    var slowIoMillis: Long = 5

    @Param("8")
    var hotOutstanding: Int = 8

    private lateinit var commandBus: InMemoryCommandBus
    private lateinit var runtime: WowRuntime
    private val completions = ConcurrentHashMap<String, Sinks.Empty<Void>>()
    private val running = AtomicBoolean()
    private val backgroundHandled = AtomicLong()

    @Setup(Level.Iteration)
    fun setup() {
        commandBus = InMemoryCommandBus()
        val dispatcher = CommandDispatcher(
            name = "mixed.CommandDispatcher",
            namedAggregates = setOf(BenchmarkAggregates.namedAggregate),
            commandBus = commandBus,
            commandHandler = WorkloadHandler(),
        )
        runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(30),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()
        running.set(true)
        backgroundHandled.set(0)
        repeat(slowAggregates) { sendLoop("slow-$it") }
        repeat(hotOutstanding) { sendLoop(HOT_ID) }
    }

    /** Keeps one command of [aggregateId] outstanding until the iteration ends. */
    private fun sendLoop(aggregateId: String) {
        if (!running.get()) {
            return
        }
        val command = command(aggregateId)
        val completion = Sinks.empty<Void>()
        completions[command.id] = completion
        commandBus.send(command)
            .then(completion.asMono())
            .subscribe(
                null,
                { completions.remove(command.id) },
                {
                    backgroundHandled.incrementAndGet()
                    sendLoop(aggregateId)
                },
            )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        running.set(false)
        runtime.stopGracefully().block(Duration.ofSeconds(30))
        commandBus.close()
        completions.clear()
    }

    @Benchmark
    fun coldCommand(blackhole: Blackhole) {
        val command = command(BenchmarkIds.nextGlobalId())
        val completion = Sinks.empty<Void>()
        completions[command.id] = completion
        commandBus.send(command).then(completion.asMono()).block(Duration.ofSeconds(30))
        blackhole.consume(command)
    }

    private fun command(aggregateId: String): CommandMessage<AddCartItem> =
        AddCartItem(productId = "productId").toCommandMessage(
            id = BenchmarkIds.nextGlobalId(),
            aggregateId = aggregateId,
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )

    private inner class WorkloadHandler : CommandHandler {
        override fun handle(
            exchange: ServerCommandExchange<*>,
            aggregateMetadata: AggregateMetadata<*, *>,
        ): Mono<Void> {
            val aggregateId = exchange.message.aggregateId.id
            val done = Mono.fromRunnable<Void> { completions.remove(exchange.message.id)?.tryEmitEmpty() }
            return when {
                aggregateId.startsWith("slow-") -> Mono.delay(Duration.ofMillis(slowIoMillis)).then(done)
                aggregateId == HOT_ID -> {
                    Blackhole.consumeCPU(HOT_CPU_TOKENS)
                    done
                }

                else -> done
            }
        }
    }

    private companion object {
        const val HOT_ID = "hot"
        const val HOT_CPU_TOKENS = 200L
    }
}
