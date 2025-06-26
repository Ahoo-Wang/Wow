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
package me.ahoo.wow.modeling

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.MockCommandAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class AggregateIdTest {
    private val namedTypedAggregate = MOCK_AGGREGATE_METADATA

    @Test
    fun equalTo() {
        val aggregateId = namedTypedAggregate.aggregateId(generateGlobalId())
        val unCreatedAggregateId = aggregateId.copy()
        aggregateId.assert().isEqualTo(unCreatedAggregateId)
        aggregateId.hashCode().assert().isEqualTo(unCreatedAggregateId.hashCode())
        aggregateId.toString().assert().isEqualTo(unCreatedAggregateId.toString())
    }

    @Test
    fun equalToDiffId() {
        val aggregateId = namedTypedAggregate.aggregateId()
        val unCreatedAggregateId = aggregateId.copy(id = generateGlobalId())
        aggregateId.assert().isNotEqualTo(unCreatedAggregateId)
        aggregateId.hashCode().assert().isNotEqualTo(unCreatedAggregateId.hashCode())
    }

    @Test
    fun compareTo() {
        val aggregateId = namedTypedAggregate.aggregateId(generateGlobalId())
        val aggregateId2 = namedTypedAggregate.aggregateId(generateGlobalId())
        aggregateId2.assert().isGreaterThan(aggregateId)
    }

    @Test
    fun sort() {
        val aggregateId = namedTypedAggregate.aggregateId(generateGlobalId())
        val aggregateId2 = namedTypedAggregate.aggregateId(generateGlobalId())
        val sorted = listOf(aggregateId2, aggregateId).asSequence().sorted().toList()
        sorted[0].assert().isEqualTo(aggregateId)
        sorted[1].assert().isEqualTo(aggregateId2)
    }

    @Test
    fun compareToWhenNotAggregateName() {
        val aggregateId = namedTypedAggregate.aggregateId(generateGlobalId())
        val aggregateId2 = aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
            .aggregateId(generateGlobalId())
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            aggregateId2.compareTo(aggregateId)
        }
    }

    @Test
    fun withStaticAggregate() {
        val aggregateId = aggregateMetadata<MockStaticAggregate, MockStaticAggregate>().aggregateId()
        aggregateId.tenantId.assert().isEqualTo("static-tenant-id")
    }
}

@StaticTenantId("static-tenant-id")
class MockStaticAggregate(private val id: String)
