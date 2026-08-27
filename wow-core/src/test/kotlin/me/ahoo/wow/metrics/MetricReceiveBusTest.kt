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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class MetricReceiveBusTest {

    @Test
    fun `subscription cardinality tags should distinguish none one and multiple`() {
        val first = MaterializedNamedAggregate("sales", "Order")
        val second = MaterializedNamedAggregate("shipping", "Shipment")

        MessageSubscription(emptySet()).apply {
            metricContext().assert().isEqualTo(MetricDescriptor.NONE)
            metricAggregate().assert().isEqualTo(MetricDescriptor.NONE)
        }
        MessageSubscription(setOf(first)).apply {
            metricContext().assert().isEqualTo("sales")
            metricAggregate().assert().isEqualTo("Order")
        }
        MessageSubscription(setOf(first, second)).apply {
            metricContext().assert().isEqualTo(MetricDescriptor.MULTIPLE)
            metricAggregate().assert().isEqualTo(MetricDescriptor.MULTIPLE)
        }
    }

    @Test
    fun `domain event receive should expose stable subscription tags`() {
        val subscription = subscription("DomainMetricAggregate", "domain-handler")
        val delegate = mockk<DomainEventBus> {
            every { receive(subscription) } returns Flux.empty()
        }

        assertReceiveTags("domain_event_bus", subscription) { metrics ->
            MetricDomainEventBus(delegate, metrics, "domainEventBus").receive(subscription).blockLast()
        }
    }

    @Test
    fun `state event receive should expose stable subscription tags`() {
        val subscription = subscription("StateMetricAggregate", "state-handler")
        val delegate = mockk<StateEventBus> {
            every { receive(subscription) } returns Flux.empty()
        }

        assertReceiveTags("state_event_bus", subscription) { metrics ->
            MetricStateEventBus(delegate, metrics, "stateEventBus").receive(subscription).blockLast()
        }
    }

    private fun assertReceiveTags(
        component: String,
        subscription: MessageSubscription,
        action: (WowMetrics) -> Unit,
    ) {
        val meterRegistry = SimpleMeterRegistry()
        try {
            action(WowMetrics(meterRegistry))

            val aggregateName = subscription.namedAggregates.single().aggregateName
            val meterIds = meterRegistry.meters
                .map { it.id }
                .filter { it.name == WowMetricNames.STREAM_MESSAGES }
                .filter { it.getTag(MetricDescriptor.COMPONENT_TAG) == component }
                .filter { it.getTag(MetricDescriptor.AGGREGATE_TAG) == aggregateName }
            meterIds.assert().isNotEmpty()
            meterIds.mapNotNull { it.getTag(MetricDescriptor.SUBSCRIBER_TAG) }
                .toSet()
                .assert().containsExactly(subscription.receiverGroup)
        } finally {
            meterRegistry.close()
        }
    }

    private fun subscription(aggregateName: String, receiverGroup: String): MessageSubscription =
        MessageSubscription(
            MaterializedNamedAggregate("metrics-test", aggregateName),
            receiverGroup,
        )
}
