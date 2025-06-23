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
package me.ahoo.wow.tck.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test

/**
 * DomainEventStreamSpec .
 *
 * @author ahoo wang
 */
abstract class DomainEventStreamSpec {
    protected val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
    private val testAggregateId = namedAggregate.aggregateId(generateGlobalId())
    protected val testEvents: List<*> = listOf(
        MockAggregateCreated(generateGlobalId()),
        MockAggregateChanged(generateGlobalId()),
    )

    protected abstract fun createDomainEventStream(
        events: List<*>,
        aggregateId: AggregateId,
        aggregateVersion: Int
    ): DomainEventStream

    @Test
    fun aggregateId() {
        val domainEventStream = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.aggregateId.assert().isEqualTo(testAggregateId)
        domainEventStream.contextName.assert().isEqualTo(testAggregateId.contextName)
        domainEventStream.aggregateName.assert().isEqualTo(testAggregateId.aggregateName)
    }

    @Test
    fun size() {
        val domainEventStream = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.aggregateId.assert().isEqualTo(testAggregateId)
        domainEventStream.size.assert().isEqualTo(testEvents.size)
    }

    @Test
    fun version() {
        val domainEventStream = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.aggregateId.assert().isEqualTo(testAggregateId)
        domainEventStream.version.assert().isEqualTo(1)
        domainEventStream.size.assert().isEqualTo(2)
    }

    @Test
    fun whenEquals() {
        val domainEventStream = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.assert().isEqualTo(domainEventStream)
        domainEventStream.assert().isNotEqualTo(Any())
        val other = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.assert().isNotEqualTo(other)
    }

    @Test
    fun whenHashCode() {
        val domainEventStream = createDomainEventStream(testEvents, testAggregateId, 0)
        domainEventStream.hashCode()
    }
}
