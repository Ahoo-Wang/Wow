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

package me.ahoo.wow.modeling.command.dispatcher

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.modeling.state.MockCommandAggregateWithTenantId
import me.ahoo.wow.tck.modeling.state.MockStateAggregateWithTenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One command dispatcher for the aggregates of one bounded context (design X7). */
class AggregateCommandDispatcherTest {
    private val tenantAggregateMetadata =
        aggregateMetadata<MockCommandAggregateWithTenantId, MockStateAggregateWithTenantId>()

    @Test
    fun `each command is handled with the metadata of its own aggregate and keeps per-aggregate metric tags`() {
        MOCK_AGGREGATE_METADATA.contextName.assert().isEqualTo(tenantAggregateMetadata.contextName)
        val handledWith = CopyOnWriteArrayList<String>()
        val meterRegistry = SimpleMeterRegistry()
        val dispatcher = AggregateCommandDispatcher(
            aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA, tenantAggregateMetadata),
            messageFlux = Flux.just(
                command(MOCK_AGGREGATE_METADATA, "mock"),
                command(tenantAggregateMetadata, "tenant"),
            ),
            commandHandler = recordingHandler(handledWith),
            metrics = WowMetrics(meterRegistry),
        )

        dispatcher.namedAggregates.assert().containsExactly(
            MOCK_AGGREGATE_METADATA.namedAggregate,
            tenantAggregateMetadata.namedAggregate,
        )
        dispatcher.name.assert().isEqualTo("${MOCK_AGGREGATE_METADATA.contextName}-AggregateCommandDispatcher")
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        StepVerifier.create(dispatcher.terminatedSignal).expectComplete().verify(Duration.ofSeconds(5))

        handledWith.assert().containsExactlyInAnyOrder(
            MOCK_AGGREGATE_METADATA.aggregateName,
            tenantAggregateMetadata.aggregateName,
        )
        meterRegistry.meters
            .filter { it.id.getTag("component") == "dispatcher" }
            .map { it.id.getTag("aggregate") to it.id.getTag("processor") }
            .toSet()
            .assert().containsExactlyInAnyOrder(
                MOCK_AGGREGATE_METADATA.aggregateName to
                    "${MOCK_AGGREGATE_METADATA.aggregateName}-AggregateCommandDispatcher",
                tenantAggregateMetadata.aggregateName to
                    "${tenantAggregateMetadata.aggregateName}-AggregateCommandDispatcher",
            )
        meterRegistry.close()
    }

    @Test
    fun `a command of another aggregate is acknowledged and skipped without failing the dispatcher`() {
        val stranger = MaterializedNamedAggregate(MOCK_AGGREGATE_METADATA.contextName, "stranger")
        val acknowledged = AtomicBoolean()
        val strangerCommand = MockCreateAggregate(id = "stranger-id", data = "stranger")
            .toCommandMessage(aggregateId = "stranger-id", namedAggregate = stranger)
        val misrouted = object : ServerCommandExchange<MockCreateAggregate> by SimpleServerCommandExchange(
            strangerCommand,
        ) {
            override fun acknowledge(): Mono<Void> = Mono.fromRunnable { acknowledged.set(true) }
        }
        val handledWith = CopyOnWriteArrayList<String>()
        val dispatcher = AggregateCommandDispatcher(
            aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA),
            messageFlux = Flux.just(misrouted, command(MOCK_AGGREGATE_METADATA, "mock")),
            commandHandler = recordingHandler(handledWith),
        )

        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()

        StepVerifier.create(dispatcher.terminatedSignal).expectComplete().verify(Duration.ofSeconds(5))
        acknowledged.get().assert().isTrue()
        handledWith.assert().containsExactly(MOCK_AGGREGATE_METADATA.aggregateName)
    }

    @Test
    fun `aggregates of different types sharing an ID get separate mailboxes`() {
        val release = Sinks.empty<Void>()
        val otherHandled = CountDownLatch(1)
        val sharedId = "shared-id"
        val dispatcher = AggregateCommandDispatcher(
            aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA, tenantAggregateMetadata),
            messageFlux = Flux.just(
                command(MOCK_AGGREGATE_METADATA, "blocked", aggregateId = sharedId),
                command(tenantAggregateMetadata, "other", aggregateId = sharedId),
            ),
            commandHandler = object : CommandHandler {
                override fun handle(
                    exchange: ServerCommandExchange<*>,
                    aggregateMetadata: AggregateMetadata<*, *>,
                ): Mono<Void> =
                    if (aggregateMetadata === MOCK_AGGREGATE_METADATA) {
                        release.asMono()
                    } else {
                        Mono.fromRunnable { otherHandled.countDown() }
                    }
            },
        )

        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        try {
            otherHandled.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            release.tryEmitEmpty()
            dispatcher.terminatedSignal.block(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `aggregates must be given and belong to one bounded context`() {
        assertThrows<IllegalArgumentException> {
            AggregateCommandDispatcher(
                aggregateMetadata = emptyList(),
                name = "empty",
                messageFlux = Flux.empty(),
                commandHandler = recordingHandler(CopyOnWriteArrayList()),
            )
        }
        val otherContext = mockk<AggregateMetadata<*, *>> {
            every { contextName } returns "other-context"
        }
        assertThrows<IllegalArgumentException> {
            AggregateCommandDispatcher(
                aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA, otherContext),
                messageFlux = Flux.empty(),
                commandHandler = recordingHandler(CopyOnWriteArrayList()),
            )
        }
    }

    private fun command(
        metadata: AggregateMetadata<*, *>,
        data: String,
        aggregateId: String = "$data-id",
    ): ServerCommandExchange<*> =
        SimpleServerCommandExchange(
            MockCreateAggregate(id = aggregateId, data = data)
                .toCommandMessage(aggregateId = aggregateId, namedAggregate = metadata.namedAggregate),
        )

    private fun recordingHandler(handledWith: MutableList<String>): CommandHandler =
        object : CommandHandler {
            override fun handle(
                exchange: ServerCommandExchange<*>,
                aggregateMetadata: AggregateMetadata<*, *>,
            ): Mono<Void> = Mono.fromRunnable { handledWith += aggregateMetadata.aggregateName }
        }
}
