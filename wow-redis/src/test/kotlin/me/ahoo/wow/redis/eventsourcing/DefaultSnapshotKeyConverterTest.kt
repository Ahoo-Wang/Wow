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
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class DefaultSnapshotKeyConverterTest {

    @Test
    fun convert() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")
        val actual = DefaultSnapshotKeyConverter.convert(aggregateId)
        actual.assert().isEqualTo("tck.mock_aggregate:snapshot:{id@tenantId}")
    }

    @Test
    fun toAggregateId() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")
        val actual = DefaultSnapshotKeyConverter.toAggregateId(
            MOCK_AGGREGATE_METADATA,
            "tck.mock_aggregate:snapshot:{id@tenantId}"
        )
        actual.assert().isEqualTo(aggregateId)
    }
}
