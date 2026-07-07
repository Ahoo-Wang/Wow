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

package me.ahoo.wow.redis.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateIdIndexKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateTenantIndexKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toKeyPrefix
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventStreamKeyConverterTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")
    private val bucket = "id".hashCode().mod(128)
    private val hashTag = "tck.mock_aggregate:es:$bucket"

    @Test
    fun `should convert to key prefix`() {
        val actual = aggregateId.toKeyPrefix()
        actual.assert().isEqualTo("{$hashTag}:")
    }

    @Test
    fun `should convert to aggregate id key`() {
        val actual = aggregateId.toKey()
        actual.assert().isEqualTo("{id@tenantId}")
    }

    @Test
    fun `should convert event stream key`() {
        val actual = EventStreamKeyConverter.convert(aggregateId)
        actual.assert().isEqualTo("{$hashTag}:id@tenantId")
    }

    @Test
    fun `should convert aggregate id index key`() {
        val actual = aggregateId.toAggregateIdIndexKey()
        actual.assert().isEqualTo("{$hashTag}:ids")
    }

    @Test
    fun `should convert aggregate tenant index key`() {
        val actual = aggregateId.toAggregateTenantIndexKey()
        actual.assert().isEqualTo("{$hashTag}:tenants")
    }

    @Test
    fun `should convert key to aggregate id`() {
        val actual = EventStreamKeyConverter.toAggregateId(
            aggregateId,
            "{$hashTag}:id@tenantId",
        )

        actual.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should reject invalid aggregate id key`() {
        val error = assertThrows<IllegalArgumentException> {
            EventStreamKeyConverter.toAggregateId(
                aggregateId,
                "{$hashTag}:id",
            )
        }

        error.message.assert().isEqualTo("Invalid key:{$hashTag}:id")
    }
}
