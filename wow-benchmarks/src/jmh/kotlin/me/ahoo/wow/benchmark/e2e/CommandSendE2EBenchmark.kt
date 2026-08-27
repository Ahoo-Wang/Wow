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
import me.ahoo.wow.benchmark.scenario.CommandGatewayScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class CommandSendE2EBenchmark {
    @Param("ceiling", "validated")
    lateinit var gatewayScenario: String

    private lateinit var scenario: CommandGatewayScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        scenario = when (gatewayScenario) {
            "ceiling" -> CommandGatewayScenario.create(
                validator = NoOpValidator,
                idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
                subscribeToCart = false,
            )

            "validated" -> CommandGatewayScenario.create(subscribeToCart = false)
            else -> error("Unsupported command send E2E scenario: $gatewayScenario")
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Command send E2E scenario [$gatewayScenario] recorded $failureCount failure(s).",
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
}
