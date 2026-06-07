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

package me.ahoo.wow.hotpath

import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandHandlingBenchmark {
    private val commandAggregateFactory = SimpleCommandAggregateFactory(NoopEventStore)

    @Benchmark
    fun createAggregateAndHandle(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            HotPathFixture.aggregateId,
        )
        aggregate.onSourcing(HotPathFixture.createEventStream())
        blackhole.consume(aggregate)
    }

    @Benchmark
    fun createAggregateFromEmpty(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            HotPathFixture.aggregateId,
        )
        blackhole.consume(aggregate)
    }

    @Benchmark
    fun createCommandAggregate(blackhole: Blackhole) {
        val commandMessage = HotPathFixture.createCommandMessage()
        val stateAggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            commandMessage.aggregateId,
        )
        val commandAggregate = commandAggregateFactory.create(
            HotPathFixture.aggregateMetadata,
            stateAggregate,
        )
        blackhole.consume(commandAggregate)
    }

    @Benchmark
    fun processCommandAggregate(blackhole: Blackhole) {
        val commandMessage = HotPathFixture.createCommandMessage()
        val stateAggregate = ConstructorStateAggregateFactory.create(
            HotPathFixture.aggregateMetadata.state,
            commandMessage.aggregateId,
        )
        val commandAggregate = commandAggregateFactory.create(
            HotPathFixture.aggregateMetadata,
            stateAggregate,
        )
        val eventStream = commandAggregate.process(
            SimpleServerCommandExchange(commandMessage),
        ).block()
        blackhole.consume(eventStream)
    }
}
