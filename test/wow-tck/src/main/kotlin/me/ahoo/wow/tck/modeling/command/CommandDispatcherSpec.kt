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
package me.ahoo.wow.tck.modeling.command

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
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
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.CommandDispatcher
import me.ahoo.wow.modeling.command.DefaultCommandHandler
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.tck.metrics.LoggingMeterRegistryInitializer
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.validation.TestValidator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

@ExtendWith(LoggingMeterRegistryInitializer::class)
abstract class CommandDispatcherSpec {
    protected val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    protected val serviceProvider: ServiceProvider = SimpleServiceProvider()
    protected val idempotencyChecker: IdempotencyChecker = BloomFilterIdempotencyChecker(
        Duration.ofSeconds(1),
    ) {
        BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 10000000)
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
    open fun setup() {
//        Schedulers.enableMetrics()
        commandBus = createCommandBus().metrizable()
        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitStrategyRegistrar = waitStrategyRegistrar,
            commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        )
        eventStore = createEventStore().metrizable()
        snapshotRepository = createSnapshotRepository().metrizable()
        stateAggregateRepository = createStateAggregateRepository(stateAggregateFactory, snapshotRepository, eventStore)
        commandAggregateFactory =
            createCommandAggregateFactory(eventStore)
        aggregateProcessorFactory =
            RetryableAggregateProcessorFactory(stateAggregateFactory, stateAggregateRepository, commandAggregateFactory)
        domainEventBus = createEventBus().metrizable()
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
        eventStore: EventStore
    ): StateAggregateRepository {
        return EventSourcingStateAggregateRepository(stateAggregateFactory, snapshotRepository, eventStore)
    }

    protected fun createCommandAggregateFactory(
        eventStore: EventStore
    ): CommandAggregateFactory {
        return SimpleCommandAggregateFactory(eventStore)
    }

    protected open fun onCommandSeek(): Mono<Void> = Mono.empty()
    val concurrency: Int = 10
    val aggregateCount: Int = 200

    @Test
    fun run() {
        val chain = FilterChainBuilder<ServerCommandExchange<*>>()
            .addFilter(AggregateProcessorFilter)
            .addFilter(SendDomainEventStreamFilter(domainEventBus))
            .addFilter(ProcessedNotifierFilter(LocalCommandWaitNotifier(waitStrategyRegistrar)))
            .build()

        val commandDispatcher = CommandDispatcher(
            namedAggregates = setOf(aggregateMetadata.materialize()),
            commandBus = commandBus,
            aggregateProcessorFactory = aggregateProcessorFactory,
            commandHandler = DefaultCommandHandler(chain).metrizable(),
            serviceProvider = serviceProvider,
        )

        commandDispatcher.use {
            it.run()
            onCommandSeek().block()
            warmUp()
            orchestra()
            commandBus.close()
        }
    }

    private fun warmUp() {
        val mockCreateAggregate = MockCreateAggregate(
            id = generateGlobalId(),
            data = generateGlobalId(),
        )
        commandGateway
            .sendAndWaitForProcessed(mockCreateAggregate.toCommandMessage())
            .then()
            .test()
            .verifyComplete()
    }

    @Suppress("LongMethod")
    private fun orchestra() {
        val creates = buildList {
            repeat(aggregateCount) {
                add(
                    MockCreateAggregate(
                        id = generateGlobalId(),
                        data = generateGlobalId(),
                    ),
                )
            }
        }
        creates.distinctBy { it.id }.size.assert().isEqualTo(aggregateCount)
        println("------------- CreateAggregate -------------")
        val createdDuration = creates.toFlux()
            .subscribeOn(Schedulers.single())
            .name("test.create-aggregate")
            .metrics()
            .flatMap({
                // 生成聚合
                commandGateway
                    .sendAndWaitForProcessed(it!!.toCommandMessage())
            }, Int.MAX_VALUE).doOnNext {
                it.succeeded.assert().isTrue()
            }
            .timeout(Duration.ofMinutes(1))
            .then()
            .test()
            .verifyComplete()
        println(
            "------------- Aggregate Created Duration:[$createdDuration] Throughput:[${creates.size.toDouble() / createdDuration.toMillis() * 1000}/s]-------------",
        )
        LoggingMeterRegistryInitializer.publishMeters()
        /*
         * 模拟聚合命令乱序
         */
        val changedDuration = buildList {
            repeat(concurrency) {
                val randomCreate = creates[ThreadLocalRandom.current().nextInt(0, aggregateCount)]
                add(
                    MockChangeAggregate(
                        randomCreate.id,
                        generateGlobalId(),
                    ),
                )
            }
        }.toFlux()
            .subscribeOn(Schedulers.single())
            .flatMap({
                commandGateway.sendAndWaitForProcessed(it.toCommandMessage())
            }, Int.MAX_VALUE)
            .doOnNext {
                it.succeeded.assert().isTrue()
            }
            .timeout(Duration.ofSeconds(30))
            .then()
            .test()
            .verifyComplete()
        println(
            "------- Aggregate Changed Duration:[$changedDuration]  Throughput:[${concurrency.toDouble() / changedDuration.toMillis() * 1000}/s]-------",
        )
        LoggingMeterRegistryInitializer.publishMeters()
    }
}
