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

import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toKeyPrefix
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class EventStreamKeyConverterTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")

    @Test
    fun toKeyPrefix() {
        val actual = aggregateId.toKeyPrefix()
        assertThat(actual, equalTo("tck.mock_aggregate:es:"))
    }

    @Test
    fun toAggregateIdKey() {
        val actual = aggregateId.toKey()
        assertThat(actual, equalTo("{id@tenantId}"))
    }

    @Test
    fun converter() {
        val actual = EventStreamKeyConverter.convert(aggregateId)
        assertThat(actual, equalTo("tck.mock_aggregate:es:{id@tenantId}"))
    }
}
