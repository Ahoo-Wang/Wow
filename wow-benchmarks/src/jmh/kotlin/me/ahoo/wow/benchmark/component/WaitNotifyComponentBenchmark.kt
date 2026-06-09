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

import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.messaging.DefaultHeader
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class WaitNotifyComponentBenchmark {
    private lateinit var notifier: LocalCommandWaitNotifier

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        notifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
    }

    @Benchmark
    fun registerWaitStrategy(blackhole: Blackhole) {
        val waitStrategy = WaitingForStage.processed(BenchmarkIds.nextGlobalId())
        val previous = SimpleWaitStrategyRegistrar.register(waitStrategy)
        SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
        blackhole.consume(previous)
    }

    @Benchmark
    fun notifyProcessed(blackhole: Blackhole) {
        val waitStrategy = WaitingForStage.processed(BenchmarkIds.nextGlobalId())
        SimpleWaitStrategyRegistrar.register(waitStrategy)
        try {
            val result = notifier.notify("", waitSignal(waitStrategy.waitCommandId)).block()
            blackhole.consume(result)
        } finally {
            SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
        }
    }

    @Benchmark
    fun waitForProcessed(blackhole: Blackhole) {
        val waitStrategy = WaitingForStage.processed(BenchmarkIds.nextGlobalId())
        SimpleWaitStrategyRegistrar.register(waitStrategy)
        waitStrategy.onFinally {
            SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
        }
        val signal = waitSignal(waitStrategy.waitCommandId)
        val result = notifier.notify("", signal)
            .then(waitStrategy.waitingLast())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun propagateSentWaitHeaders(blackhole: Blackhole) {
        val waitStrategy = WaitingForStage.sent(BenchmarkIds.nextGlobalId())
        val header = DefaultHeader.empty()
        waitStrategy.propagate("", header)
        blackhole.consume(header)
    }

    @Benchmark
    fun waitForSent(blackhole: Blackhole) {
        val waitStrategy = WaitingForStage.sent(BenchmarkIds.nextGlobalId())
        SimpleWaitStrategyRegistrar.register(waitStrategy)
        waitStrategy.onFinally {
            SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
        }
        val signal = sentSignal(waitStrategy.waitCommandId)
        val result = notifier.notify("", signal)
            .then(waitStrategy.waitingLast())
            .block()
        blackhole.consume(result)
    }

    private fun waitSignal(waitCommandId: String): WaitSignal {
        return SimpleWaitSignal(
            id = BenchmarkIds.nextGlobalId(),
            waitCommandId = waitCommandId,
            commandId = waitCommandId,
            aggregateId = BenchmarkAggregates.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = BenchmarkAggregates.namedAggregate.contextName,
                processorName = "BenchmarkCommandProcessor",
                name = "process",
            ),
            aggregateVersion = 1,
            isLastProjection = true,
        )
    }

    private fun sentSignal(waitCommandId: String): WaitSignal {
        return SimpleWaitSignal(
            id = BenchmarkIds.nextGlobalId(),
            waitCommandId = waitCommandId,
            commandId = waitCommandId,
            aggregateId = BenchmarkAggregates.aggregateId(),
            stage = CommandStage.SENT,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = BenchmarkAggregates.namedAggregate.contextName,
                processorName = "BenchmarkCommandGateway",
                name = "send",
            ),
            aggregateVersion = null,
            isLastProjection = true,
        )
    }
}
