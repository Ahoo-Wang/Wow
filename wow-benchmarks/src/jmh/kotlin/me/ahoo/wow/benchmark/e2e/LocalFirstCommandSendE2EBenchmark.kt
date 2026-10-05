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

import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.DiscardingDistributedCommandBus
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.messaging.shouldLocalFirst
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.atomic.AtomicInteger

/**
 * Command send through the gateway with a started command dispatcher, on a plain in-memory bus versus a
 * [LocalFirstCommandBus] in front of the same in-memory bus (its distributed bus drops messages, so no broker cost is
 * included). `sendAndWaitSent` shows the sender-side cost of local-first: the distributed copy is sent only after the
 * local dispatcher admits the message. `sendAndWaitProcessed` is the full write path on the ceiling scenario.
 *
 * Audit 9.3.0 B §F6 (design WP G2).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class LocalFirstCommandSendE2EBenchmark {
    @Param("in-memory", "local-first")
    lateinit var bus: String

    private lateinit var scenario: CommandDispatcherScenario
    private var discardingBus: DiscardingDistributedCommandBus? = null
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        val commandBus: CommandBus = when (bus) {
            "in-memory" -> InMemoryCommandBus()
            "local-first" -> LocalFirstCommandBus(
                distributedBus = DiscardingDistributedCommandBus().also { discardingBus = it },
                localBus = InMemoryCommandBus(),
            )

            else -> error("Unsupported bus: $bus")
        }
        scenario = CommandDispatcherScenario.create(
            commandBus = commandBus,
            eventStore = NoopEventStore,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            // Idempotency is off here, so the processing node does not check request IDs either (as in Spring).
            processingRequestIdChecker = null,
            validator = NoOpValidator,
        )
        if (bus == "local-first") {
            check(BenchmarkCommands.commandPathAddCartItem().shouldLocalFirst()) {
                "Benchmark aggregate must be local for local-first routing."
            }
            scenario.commandGateway.sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem()).block()
            check(checkNotNull(discardingBus).sent.sum() == 1L) { "Local-first must still send the distributed copy." }
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Local-first command send [$bus] recorded $failureCount failure(s).",
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Benchmark
    fun sendAndWaitSent(blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForSent(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
    }
}
