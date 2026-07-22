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

package me.ahoo.wow.benchmark.scenario

import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.test.validation.TestValidator

class CommandWriteE2EFixture private constructor(
    private val scenarioId: String,
    private val dispatcherScenario: CommandDispatcherScenario,
) : AutoCloseable {
    val commandGateway: CommandGateway
        get() = dispatcherScenario.commandGateway

    fun nextCommand(): CommandMessage<AddCartItem> {
        return when (scenarioId) {
            CEILING_SCENARIO, NOOP_STORE_SCENARIO -> BenchmarkCommands.commandPathAddCartItem()
            IN_MEMORY_NEW_AGGREGATE_SCENARIO -> BenchmarkCommands.newAggregateAddCartItem()
            else -> error("Unsupported command write E2E scenario: $scenarioId")
        }
    }

    override fun close() {
        dispatcherScenario.close()
    }

    companion object {
        const val CEILING_SCENARIO = "ceiling"
        const val NOOP_STORE_SCENARIO = "noop-store"
        const val IN_MEMORY_NEW_AGGREGATE_SCENARIO = "in-memory-new-aggregate"

        fun create(
            scenarioId: String,
            schedulerStrategy: SchedulerStrategy,
        ): CommandWriteE2EFixture {
            val schedulerSupplier = schedulerStrategy.toSchedulerSupplier()
            val dispatcherScenario = when (scenarioId) {
                CEILING_SCENARIO -> createDispatcherScenario(
                    eventStore = NoopEventStore,
                    idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                        NoOpIdempotencyChecker
                    },
                    validator = NoOpValidator,
                    schedulerSupplier = schedulerSupplier,
                )

                NOOP_STORE_SCENARIO -> createDispatcherScenario(
                    eventStore = NoopEventStore,
                    schedulerSupplier = schedulerSupplier,
                )

                IN_MEMORY_NEW_AGGREGATE_SCENARIO -> createDispatcherScenario(
                    eventStore = InMemoryEventStore(),
                    schedulerSupplier = schedulerSupplier,
                )

                else -> error("Unsupported command write E2E scenario: $scenarioId")
            }
            return CommandWriteE2EFixture(scenarioId, dispatcherScenario)
        }

        private fun createDispatcherScenario(
            commandBus: CommandBus = InMemoryCommandBus(),
            eventStore: EventStore,
            snapshotStore: SnapshotStore = InMemorySnapshotStore(),
            domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
            stateEventBus: StateEventBus = InMemoryStateEventBus(),
            schedulerSupplier: AggregateSchedulerSupplier,
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    BenchmarkIdempotency.bloomFilterChecker()
                },
            validator: Validator = TestValidator,
        ): CommandDispatcherScenario {
            return CommandDispatcherScenario.create(
                commandBus = commandBus,
                eventStore = eventStore,
                snapshotRepository = snapshotStore,
                domainEventBus = domainEventBus,
                stateEventBus = stateEventBus,
                schedulerSupplier = schedulerSupplier,
                validator = validator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
            )
        }
    }
}
