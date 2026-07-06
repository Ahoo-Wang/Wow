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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.metrics.Metrics.getMetricsSubscriber
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import org.junit.jupiter.api.Test
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

internal class MetricsTest {

    @Test
    fun `metrics should expose stable tag keys used by decorators`() {
        Metrics.AGGREGATE_KEY.assert().isEqualTo("aggregate")
        Metrics.SUBSCRIBER_CONTEXT_KEY.assert().isEqualTo("(MetricsSubscriber)")
        Metrics.SUBSCRIBER_KEY.assert().isEqualTo("subscriber")
        Metrics.COMMAND_KEY.assert().isEqualTo("command")
        Metrics.SOURCE_KEY.assert().isEqualTo("source")
        Metrics.EVENT_KEY.assert().isEqualTo("event")
        Metrics.PROCESSOR_KEY.assert().isEqualTo("processor")
    }

    @Test
    fun `writeMetricsSubscriber should make subscriber available in Reactor context`() {
        val publisher = Flux.deferContextual {
            Flux.just(requireNotNull(it.getMetricsSubscriber()))
        }.writeMetricsSubscriber("projection-worker")

        StepVerifier.create(publisher)
            .expectNext("projection-worker")
            .verifyComplete()
    }

    @Test
    fun `metrizable should not wrap already metrizable components repeatedly`() {
        assertNotWrappedRepeatedly(mockk<CommandHandler>())
        assertNotWrappedRepeatedly(mockk<SnapshotHandler>())
        assertNotWrappedRepeatedly(mockk<me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy>())
        assertNotWrappedRepeatedly(NoOpSnapshotStore)
    }

    @Test
    fun `snapshot store decorator should assign production metric name`() {
        val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1")
        val repository = NoOpSnapshotStore.metrizable()

        val publisher = repository.getVersion(aggregateId)

        Scannable.from(publisher).name().assert().isEqualTo("wow.snapshot.getVersion")
    }

    private inline fun <reified T : Any> assertNotWrappedRepeatedly(component: T) {
        val once = component.metrizable()
        val twice = once.metrizable()

        twice.assert().isSameAs(once)
    }
}
