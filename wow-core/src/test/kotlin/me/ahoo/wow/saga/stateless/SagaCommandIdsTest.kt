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

package me.ahoo.wow.saga.stateless

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import io.mockk.mockk
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.converter.DatePrefixIdConverter
import me.ahoo.cosid.converter.Radix62IdConverter
import me.ahoo.cosid.converter.SnowflakeFriendlyIdConverter
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.segment.SegmentId
import me.ahoo.cosid.snowflake.ClockSyncSnowflakeId
import me.ahoo.cosid.snowflake.MillisecondSnowflakeId
import me.ahoo.cosid.snowflake.SecondSnowflakeId
import me.ahoo.cosid.snowflake.SnowflakeIdStateParser
import me.ahoo.cosid.snowflake.StringSnowflakeId
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.id.AggregateIdGeneratorRegistrar
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * B7 (K3): the IDs of the commands a saga sends are derived from the event, so a retried event sends the same
 * commands: a retried create is a duplicate request, not a second aggregate.
 */
class SagaCommandIdsTest {
    private val target: NamedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate

    @Test
    fun `a saga create command that names no aggregate gets an ID in the CosId format stamped with the event time`() {
        val event = fixtureEvent()
        val id = SagaCommandIds.aggregateId(event, saga("Saga"), 0, target)!!

        val generator = AggregateIdGeneratorRegistrar.getOrInitialize(target) as CosIdGenerator
        val state = generator.stateParser.asState(id)
        state.timestamp.assert().isEqualTo(event.createTime)
        id.length.assert().isEqualTo(generator.generateAsString().length)
    }

    @Test
    fun `the ID differs by saga function, by index and by target aggregate type, and not by retry`() {
        val event = fixtureEvent()
        val first = SagaCommandIds.aggregateId(event, saga("Saga"), 0, target)

        SagaCommandIds.aggregateId(event, saga("Saga"), 0, target).assert().isEqualTo(first)
        SagaCommandIds.aggregateId(event, saga("OtherSaga"), 0, target).assert().isNotEqualTo(first)
        SagaCommandIds.aggregateId(event, saga("Saga"), 1, target).assert().isNotEqualTo(first)
        val otherType = me.ahoo.wow.modeling.MaterializedNamedAggregate(target.contextName, "other_aggregate")
        SagaCommandIds.aggregateId(event, saga("Saga"), 0, otherType).assert().isNotEqualTo(first)
    }

    @Test
    fun `snowflake generators get a snowflake ID with the event time`() {
        val createTime = System.currentTimeMillis()
        val millis = StringSnowflakeId(MillisecondSnowflakeId(1), Radix62IdConverter.PAD_START)
        val millisId = SagaCommandIds.derive(millis, createTime, 0x5DEECE66DL)!!
        val millisState = SnowflakeIdStateParser.of(MillisecondSnowflakeId(1), ZoneId.systemDefault(), false)
            .parse(Radix62IdConverter.PAD_START.asLong(millisId))
        millisState.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().assert().isEqualTo(createTime)

        val seconds = SecondSnowflakeId(1)
        val secondsId = SagaCommandIds.derive(seconds, createTime, 0x5DEECE66DL)!!
        val secondsState = SnowflakeIdStateParser.of(seconds).parse(seconds.idConverter().asLong(secondsId))
        secondsState.timestamp.atZone(ZoneId.systemDefault()).toInstant().epochSecond
            .assert().isEqualTo(TimeUnit.MILLISECONDS.toSeconds(createTime))
    }

    @Test
    fun `a seconds snowflake wrapped in clock-sync and string decorators gets a derived ID`() {
        val createTime = System.currentTimeMillis()
        val seconds = SecondSnowflakeId(1)
        val wrapped = StringSnowflakeId(ClockSyncSnowflakeId(seconds), Radix62IdConverter.PAD_START)
        val id = SagaCommandIds.derive(wrapped, createTime, 0x5DEECE66DL)!!

        val state = SnowflakeIdStateParser.of(seconds).parse(Radix62IdConverter.PAD_START.asLong(id))
        state.timestamp.atZone(ZoneId.systemDefault()).toInstant().epochSecond
            .assert().isEqualTo(TimeUnit.MILLISECONDS.toSeconds(createTime))
    }

    @Test
    fun `generators whose string form depends on the date or the zone keep random IDs`() {
        val datePrefixed = StringSnowflakeId(
            MillisecondSnowflakeId(1),
            DatePrefixIdConverter("yyMMdd", "-", Radix62IdConverter.PAD_START),
        )
        SagaCommandIds.derive(datePrefixed, System.currentTimeMillis(), 1L).assert().isNull()
        val friendly = StringSnowflakeId(
            MillisecondSnowflakeId(1),
            SnowflakeFriendlyIdConverter(SnowflakeIdStateParser.of(MillisecondSnowflakeId(1))),
        )
        SagaCommandIds.derive(friendly, System.currentTimeMillis(), 1L).assert().isNull()
    }

    @Test
    fun `segment and custom generators keep random IDs`() {
        val segment = mockk<SegmentId>()
        SagaCommandIds.derive(segment, System.currentTimeMillis(), 1L).assert().isNull()
        val custom = IdGenerator { 42L }
        SagaCommandIds.derive(custom, System.currentTimeMillis(), 1L).assert().isNull()
    }

    @Test
    fun `a returned command message whose request ID is its ID gets the derived request ID, its aggregate ID is kept`() {
        val sent = mutableListOf<CommandMessage<*>>()
        val event = fixtureEvent()
        val returned = MockChangeAggregate("chosen", "data").toCommandMessage()
        val saga = StatelessSagaFunction(
            NamedSagaFunction("Saga", Mono.just(returned)),
            gateway(RecordingBus(sent)),
            commandMessageFactory(),
        )

        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event))).expectNextCount(1).verifyComplete()

        sent.single().requestId.assert().isEqualTo("${event.id}-0")
        sent.single().aggregateId.id.assert().isEqualTo("chosen")
    }

    @Test
    fun `the same event handled twice creates one aggregate - the retry is a duplicate request the saga ignores`() {
        val eventStore = InMemoryEventStore()
        val metadata = aggregateMetadata<SagaTarget, SagaTarget>()
        val handler = DefaultCommandHandler(
            serviceProvider = SimpleServiceProvider(),
            aggregateProcessorFactory = RetryableAggregateProcessorFactory(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                stateAggregateRepository = EventSourcingStateAggregateRepository(
                    ConstructorStateAggregateFactory,
                    InMemorySnapshotStore(),
                    eventStore,
                ),
                commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
            ),
            domainEventBus = null,
            stateEventBus = null,
            commandWaitNotifier = null,
        )
        val processingBus = object : RecordingBus(mutableListOf()) {
            override fun send(message: CommandMessage<*>): Mono<Void> =
                super.send(message).then(handler.handle(SimpleServerCommandExchange(message), metadata))
        }
        val requestIdChecker = DefaultRequestIdChecker(
            DefaultAggregateIdempotencyCheckerProvider {
                BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
                    BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 1000)
                }
            },
            eventStore,
        )
        val saga = StatelessSagaFunction(
            NamedSagaFunction(
                "Saga",
                Mono.just(CreateSagaTarget("created-by-saga").commandBuilder().namedAggregate(metadata.namedAggregate)),
            ),
            gateway(processingBus, requestIdChecker),
            commandMessageFactory(),
        )
        val event = fixtureEvent()

        repeat(2) {
            StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event))).expectNextCount(1).verifyComplete()
        }

        val sent = processingBus.sent
        sent.assert().hasSize(1)
        val aggregateId = sent.single().aggregateId
        eventStore.load(aggregateId).collectList().block()!!.assert().hasSize(1)
    }

    private fun saga(processorName: String) = NamedSagaFunction(processorName, Mono.empty<Any>())

    private fun gateway(
        bus: CommandBus,
        requestIdChecker: me.ahoo.wow.command.RequestIdChecker =
            me.ahoo.wow.command.RequestIdChecker { _, _ -> Mono.just(true) },
    ) = DefaultCommandGateway(
        commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
        commandBus = bus,
        validator = NoOpValidator,
        requestIdChecker = requestIdChecker,
        waitCoordinator = DefaultWaitCoordinator(),
        commandWaitNotifier = object : CommandWaitNotifier {
            override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> = Mono.empty()
        },
    )
}

/** A create command whose body names no aggregate: a saga gives it one. */
@CreateAggregate
data class CreateSagaTarget(val data: String)

data class SagaTargetCreated(val data: String)

@AggregateRoot
// Wow finds and invokes the private command and sourcing handlers by reflection.
@Suppress("UnusedPrivateMember", "UnusedParameter")
class SagaTarget(val id: String) {
    private fun onCommand(command: CreateSagaTarget): SagaTargetCreated = SagaTargetCreated(command.data)

    private fun onSourcing(event: SagaTargetCreated) = Unit
}

internal class NamedSagaFunction(
    override val processorName: String,
    private val result: Mono<*>,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val contextName: String = "fixture"
    override val name: String = "onEvent"
    override val processor: Any = processorName
    override val supportedType: Class<*> = MockAggregateCreated::class.java
    override val supportedTopics: Set<NamedAggregate> = emptySet()
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = result
}

internal open class RecordingBus(val sent: MutableList<CommandMessage<*>>) : CommandBus {
    override fun send(message: CommandMessage<*>): Mono<Void> = Mono.fromRunnable { sent += message }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        MessageReceiver(Flux.empty())
}
