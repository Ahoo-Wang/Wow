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

import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.command.MockCommandAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class AggregateIdTest {
    private val namedTypedAggregate = MOCK_AGGREGATE_METADATA

    @Test
    fun equalTo() {
        val aggregateId = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        val unCreatedAggregateId = aggregateId.copy()
        assertThat(aggregateId, equalTo(unCreatedAggregateId))
        assertThat(aggregateId.hashCode(), equalTo(unCreatedAggregateId.hashCode()))
        assertThat(aggregateId.toString(), equalTo(unCreatedAggregateId.toString()))
    }

    @Test
    fun equalToDiffId() {
        val aggregateId = namedTypedAggregate.aggregateId()
        val unCreatedAggregateId = aggregateId.copy(id = GlobalIdGenerator.generateAsString())
        assertThat(aggregateId, not(unCreatedAggregateId))
        assertThat(aggregateId.hashCode(), not(unCreatedAggregateId.hashCode()))
    }

    @Test
    fun compareTo() {
        val aggregateId = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        val aggregateId2 = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        assertThat(aggregateId2, greaterThan(aggregateId))
    }

    @Test
    fun sort() {
        val aggregateId = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        val aggregateId2 = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        val sorted = listOf(aggregateId2, aggregateId).asSequence().sorted().toList()
        assertThat(sorted[0], equalTo(aggregateId))
        assertThat(sorted[1], equalTo(aggregateId2))
    }

    @Test
    fun compareToWhenNotAggregateName() {
        val aggregateId = namedTypedAggregate.aggregateId(GlobalIdGenerator.generateAsString())
        val aggregateId2 = aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
            .aggregateId(GlobalIdGenerator.generateAsString())
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            aggregateId2.compareTo(aggregateId)
        }
    }

    @Test
    fun withStaticAggregate() {
        val aggregateId = aggregateMetadata<MockStaticAggregate, MockStaticAggregate>().aggregateId()
        assertThat(aggregateId.tenantId, equalTo("static-tenant-id"))
    }
}

@StaticTenantId("static-tenant-id")
class MockStaticAggregate(private val id: String)
