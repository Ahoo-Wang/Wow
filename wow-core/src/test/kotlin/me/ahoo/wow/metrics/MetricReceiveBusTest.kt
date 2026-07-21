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
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class MetricReceiveBusTest {

    @Test
    fun `domain event receive should expose stable subscription tags`() {
        val subscription = subscription("DomainMetricAggregate", "domain-handler")
        val delegate = mockk<DomainEventBus> {
            every { receive(subscription) } returns Flux.empty()
        }

        assertReceiveTags("wow.event.receive", subscription) {
            MetricDomainEventBus(delegate).receive(subscription).blockLast()
        }
    }

    @Test
    fun `state event receive should expose stable subscription tags`() {
        val subscription = subscription("StateMetricAggregate", "state-handler")
        val delegate = mockk<StateEventBus> {
            every { receive(subscription) } returns Flux.empty()
        }

        assertReceiveTags("wow.state.receive", subscription) {
            MetricStateEventBus(delegate).receive(subscription).blockLast()
        }
    }

    private fun assertReceiveTags(
        metricName: String,
        subscription: MessageSubscription,
        action: () -> Unit,
    ) {
        val meterRegistry = SimpleMeterRegistry()
        MicrometerMetrics.addRegistry(meterRegistry)
        try {
            action()

            val aggregateName = subscription.namedAggregates.single().aggregateName
            val meterIds = meterRegistry.meters
                .map { it.id }
                .filter { it.name.startsWith(metricName) }
                .filter { it.getTag(Metrics.AGGREGATE_KEY) == aggregateName }
            meterIds.assert().isNotEmpty()
            meterIds.mapNotNull { it.getTag(Metrics.SUBSCRIBER_KEY) }
                .toSet()
                .assert().containsExactly(subscription.receiverGroup)
        } finally {
            MicrometerMetrics.removeRegistry(meterRegistry)
            meterRegistry.close()
        }
    }

    private fun subscription(aggregateName: String, receiverGroup: String): MessageSubscription =
        MessageSubscription(
            MaterializedNamedAggregate("metrics-test", aggregateName),
            receiverGroup,
        )
}
