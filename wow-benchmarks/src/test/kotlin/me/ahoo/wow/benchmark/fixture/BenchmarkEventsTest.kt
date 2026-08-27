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

package me.ahoo.wow.benchmark.fixture

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.junit.jupiter.api.Test

class BenchmarkEventsTest {

    @Test
    fun `should keep recovery state size constant`() {
        val aggregateId = BenchmarkAggregates.aggregateId()
        val eventCount = 10
        val eventStreams = BenchmarkEvents.constantSizeEventStreams(aggregateId, eventCount)
        val aggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            aggregateId,
        )

        eventStreams.forEach(aggregate::onSourcing)

        eventStreams.map { it.version }.assert().containsExactlyElementsOf(1..eventCount)
        val state = aggregate.state
        state.items.assert().hasSize(1)
        state.items.single().quantity.assert().isEqualTo(eventCount)
    }

    @Test
    fun `should reject empty recovery workload`() {
        val error = runCatching {
            BenchmarkEvents.constantSizeEventStreams(eventCount = 0)
        }.exceptionOrNull()

        error.assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
