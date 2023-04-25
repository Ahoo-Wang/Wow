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
package me.ahoo.wow.test.spec.modeling.command

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.AggregateDispatcher
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.CommandHandler
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

abstract class AggregateDispatcherSpec {
    protected val aggregateMetadata = aggregateMetadata<MockAggregate, MockAggregate>()
    protected val serviceProvider: ServiceProvider = SimpleServiceProvider()
    protected val idempotencyChecker: IdempotencyChecker = BloomFilterIdempotencyChecker(1000000, 0.000001)

    companion object {
        private val log = LoggerFactory.getLogger(AggregateDispatcherSpec::class.java)
    }

    protected val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
    protected val waitStrategyRegistrar = SimpleWaitStrategyRegistrar
    protected lateinit var aggregateProcessorFactory: AggregateProcessorFactory
    protected lateinit var commandBus: CommandBus
    protected lateinit var commandGateway: CommandGateway
    protected lateinit var eventStore: EventStore
    protected lateinit var snapshotRepository: SnapshotRepository
    protected lateinit var stateAggregateRepository: StateAggregateRepository
    protected lateinit var commandAggregateFactory: CommandAggregateFactory
    protected lateinit var domainEventBus: DomainEventBus

    @BeforeEach
    fun setup() {
        commandBus = createCommandBus()
        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            idempotencyChecker = idempotencyChecker,
            waitStrategyRegistrar = waitStrategyRegistrar,
            NoOpValidator,
        )
        eventStore = createEventStore()
        snapshotRepository = createSnapshotRepository()
        stateAggregateRepository = createStateAggregateRepository(stateAggregateFactory, snapshotRepository, eventStore)
        commandAggregateFactory =
            createCommandAggregateFactory(eventStore)
        aggregateProcessorFactory =
            RetryableAggregateProcessorFactory(stateAggregateFactory, stateAggregateRepository, commandAggregateFactory)
        domainEventBus = createEventBus()
    }

    protected open fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    protected open fun createEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    protected open fun createEventStore(): EventStore {
        return InMemoryEventStore()
    }

    protected open fun createSnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository()
    }

    protected fun createStateAggregateRepository(
        stateAggregateFactory: StateAggregateFactory,
        snapshotRepository: SnapshotRepository,
        eventStore: EventStore,
    ): StateAggregateRepository {
        return EventSourcingStateAggregateRepository(stateAggregateFactory, snapshotRepository, eventStore)
    }

    protected fun createCommandAggregateFactory(
        eventStore: EventStore,
    ): CommandAggregateFactory {
        return SimpleCommandAggregateFactory(eventStore)
    }

    protected open fun onCommandSeek(): Mono<Void> = Mono.empty()
    val concurrency: Int = 600
    val aggregateCount: Int = 60000

    @Test
    fun run() {
        val chain = FilterChainBuilder<ServerCommandExchange<Any>>()
            .addFilter(AggregateProcessorFilter)
            .addFilter(SendDomainEventStreamFilter(domainEventBus))
            .addFilter(ProcessedNotifierFilter(LocalCommandWaitNotifier(waitStrategyRegistrar)))
            .build()

        val aggregateDispatcher = AggregateDispatcher(
            topics = setOf(aggregateMetadata.materialize()),
            aggregateTtl = Duration.ofSeconds(30),
            commandBus = commandBus,
            aggregateProcessorFactory = aggregateProcessorFactory,
            commandHandler = CommandHandler(chain),
            serviceProvider = serviceProvider,
        )

        aggregateDispatcher.use {
            it.run()
            onCommandSeek().block()
            warmUp()
            orchestra()
        }
    }

    private fun warmUp() {
        val createAggregate = CreateAggregate(
            id = GlobalIdGenerator.generateAsString(),
            state = GlobalIdGenerator.generateAsString()
        )
        commandGateway
            .sendAndWaitForProcessed(createAggregate.asCommandMessage())
            .then()
            .test()
            .verifyComplete()
    }

    private fun orchestra() {
        val creates = buildList {
            repeat(aggregateCount) {
                add(
                    CreateAggregate(
                        id = GlobalIdGenerator.generateAsString(),
                        state = GlobalIdGenerator.generateAsString()
                    )
                )
            }
        }
        assertThat(
            creates.distinctBy { it.id }.size,
            equalTo(aggregateCount)
        )

        log.info("------------- CreateAggregate -------------")
        val createdDuration = creates.toFlux()
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap {
                // 生成聚合
                commandGateway
                    .sendAndWaitForProcessed(it!!.asCommandMessage())
            }.doOnNext {
                assertThat(it.succeeded, equalTo(true))
            }
            .timeout(Duration.ofMinutes(3))
            .then()
            .test()
            .verifyComplete()

        log.info("------------- Aggregate Created Duration:[$createdDuration]-------------")

        /*
         * 模拟聚合命令乱序
         */
        val changedDuration = buildList {
            repeat(concurrency) {
                val randomCreate = creates[ThreadLocalRandom.current().nextInt(0, aggregateCount)]
                add(
                    ChangeAggregate(
                        randomCreate.id,
                        GlobalIdGenerator.generateAsString(),
                    )
                )
            }
        }.toFlux()
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap {
                commandGateway.sendAndWaitForProcessed(it.asCommandMessage())
            }
            .doOnNext {
                assertThat(it.succeeded, equalTo(true))
            }
            .timeout(Duration.ofMinutes(2))
            .then()
            .test()
            .verifyComplete()

        log.warn("------- Aggregate Created Duration:[$createdDuration],Changed Duration:[$changedDuration] -------")
    }
}
