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

package me.ahoo.wow.benchmark.infrastructure.redis

import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.infrastructure.redis.RedisBenchmarkFixture
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
open class RedisCommandWriteE2EBenchmark {
    private lateinit var fixture: RedisBenchmarkFixture
    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        fixture = RedisBenchmarkFixture()
        commandDispatcherScenario = CommandDispatcherScenario.create(
            eventStore = RedisEventStore(fixture.redisTemplate),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Redis command write E2E recorded $failureCount failure(s).",
                )
            }
        } finally {
            try {
                commandDispatcherScenario.close()
            } finally {
                fixture.close()
            }
        }
    }

    @Benchmark
    fun sendAndWaitSent(blackHole: Blackhole) {
        blackHole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            commandDispatcherScenario.commandGateway
                .sendAndWaitForSent(BenchmarkCommands.newAggregateAddCartItem())
                .block()
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackHole: Blackhole) {
        blackHole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            commandDispatcherScenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.newAggregateAddCartItem())
                .block()
        }
    }
}
