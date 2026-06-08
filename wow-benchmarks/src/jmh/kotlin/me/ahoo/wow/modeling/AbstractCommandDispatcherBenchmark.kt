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

package me.ahoo.wow.modeling

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import org.openjdk.jmh.infra.Blackhole

abstract class AbstractCommandDispatcherBenchmark {
    private lateinit var scenario: CommandDispatcherScenario

    open fun setup() {
        scenario = CommandDispatcherScenario.create(
            commandBus = createCommandBus(),
            eventStore = createEventStore(),
            snapshotRepository = createSnapshotRepository(),
            domainEventBus = createDomainEventBus(),
            stateEventBus = createStateEventBus(),
            schedulerSupplier = createSchedulerSupplier(),
            idempotencyCheckerProvider = createIdempotencyCheckerProvider(),
        )
    }

    open fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    open fun createDomainEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    open fun createStateEventBus(): StateEventBus {
        return InMemoryStateEventBus()
    }

    open fun createEventStore(): EventStore {
        return InMemoryEventStore()
    }

    open fun createIdempotencyCheckerProvider(): AggregateIdempotencyCheckerProvider {
        return DefaultAggregateIdempotencyCheckerProvider {
            BenchmarkIdempotency.bloomFilterChecker()
        }
    }

    open fun createSnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository()
    }

    open fun createSchedulerSupplier(): AggregateSchedulerSupplier {
        return BenchmarkAggregateSchedulerSupplier()
    }

    open fun createBenchmarkCommandMessage(): CommandMessage<AddCartItem> {
        return BenchmarkCommands.fixedAggregateAddCartItem()
    }

    open fun destroy() {
        scenario.close()
    }

    inline fun run(blackHole: Blackhole, block: () -> Any?) {
        blackHole.consumeWowResult(block)
    }

    // Sent-only helpers are intentionally not JMH benchmarks: tight loops can
    // outpace dispatcher processing and measure backlog pressure instead.
    open fun send(blackHole: Blackhole) {
        run(blackHole) {
            scenario.commandGateway.send(createBenchmarkCommandMessage()).block()
        }
    }

    open fun sendAndWaitForSent(blackHole: Blackhole) {
        run(blackHole) {
            scenario.commandGateway.sendAndWaitForSent(createBenchmarkCommandMessage()).block()
        }

    }

    open fun sendAndWaitForProcessed(blackHole: Blackhole) {
        run(blackHole) {
            scenario.commandGateway.sendAndWaitForProcessed(createBenchmarkCommandMessage()).block()
        }
    }
}
