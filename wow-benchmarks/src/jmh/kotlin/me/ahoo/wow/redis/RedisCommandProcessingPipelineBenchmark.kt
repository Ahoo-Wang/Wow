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

package me.ahoo.wow.redis

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandPipelineScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 2)
@Threads(5)
@State(Scope.Benchmark)
open class RedisCommandProcessingPipelineBenchmark {
    private lateinit var redis: RedisBenchmarkFixture
    private lateinit var commandPipelineScenario: CommandPipelineScenario

    @Setup
    fun setup() {
        redis = RedisBenchmarkFixture()
        commandPipelineScenario = CommandPipelineScenario.create(
            eventStore = RedisEventStore(redis.redisTemplate),
            commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar),
            aggregateMetadata = BenchmarkAggregates.cartMetadata,
            newAggregateCommandFactory = BenchmarkCommands::newAggregateAddCartItem,
        )
    }

    @TearDown
    fun tearDown() {
        redis.close()
    }

    @Benchmark
    fun handleAggregateOnly(blackHole: Blackhole) {
        blackHole.consumeWowResult {
            commandPipelineScenario.aggregateOnlyHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateDomainAndStateEvent(blackHole: Blackhole) {
        blackHole.consumeWowResult {
            commandPipelineScenario.aggregateDomainAndStateEventHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithoutWait(blackHole: Blackhole) {
        blackHole.consumeWowResult {
            commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithLocalWait(blackHole: Blackhole) {
        blackHole.consumeWowResult {
            val commandMessage = BenchmarkCommands.newAggregateAddCartItem()
            val waitStrategy = WaitingForStage.processed(commandMessage.commandId)
            waitStrategy.propagate("", commandMessage.header)
            SimpleWaitStrategyRegistrar.register(waitStrategy)
            waitStrategy.onFinally {
                SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            }
            val exchange = commandPipelineScenario.createServerExchange(commandMessage)
            commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
                .handle(exchange)
                .then(waitStrategy.waitingLast())
                .block()
        }
    }
}
