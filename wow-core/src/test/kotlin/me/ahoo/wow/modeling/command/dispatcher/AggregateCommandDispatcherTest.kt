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
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

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
    fun `a command of another aggregate fails the dispatcher`() {
        val stranger = MaterializedNamedAggregate(MOCK_AGGREGATE_METADATA.contextName, "stranger")
        val dispatcher = AggregateCommandDispatcher(
            aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA),
            messageFlux = Flux.just(
                SimpleServerCommandExchange(
                    MockCreateAggregate(id = "stranger-id", data = "stranger")
                        .toCommandMessage(aggregateId = "stranger-id", namedAggregate = stranger),
                ),
            ),
            commandHandler = recordingHandler(CopyOnWriteArrayList()),
        )

        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()

        StepVerifier.create(dispatcher.terminatedSignal)
            .expectErrorMatches { it is IllegalStateException && it.message!!.contains("stranger") }
            .verify(Duration.ofSeconds(5))
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

    private fun command(metadata: AggregateMetadata<*, *>, data: String): ServerCommandExchange<*> =
        SimpleServerCommandExchange(
            MockCreateAggregate(id = "$data-id", data = data)
                .toCommandMessage(aggregateId = "$data-id", namedAggregate = metadata.namedAggregate),
        )

    private fun recordingHandler(handledWith: MutableList<String>): CommandHandler =
        object : CommandHandler {
            override fun handle(
                exchange: ServerCommandExchange<*>,
                aggregateMetadata: AggregateMetadata<*, *>,
            ): Mono<Void> = Mono.fromRunnable { handledWith += aggregateMetadata.aggregateName }
        }
}
