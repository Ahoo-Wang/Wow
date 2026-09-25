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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class EventStreamSearchesTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())

    private fun Query.termValues(): Map<String, String> = bool().filter().filter { it.isTerm }
        .associate { it.term().field() to it.term().value()._toJsonString() }

    private fun Query.rangeOf(field: String) = bool().filter().single { it.isRange }.range().untyped().also {
        it.field().assert().isEqualTo(field)
    }

    @Test
    fun `version range scopes the aggregate and bounds the version`() {
        val query = EventStreamSearches.versionRange(aggregateId, 2, 5)

        query.termValues().assert().containsExactlyInAnyOrderEntriesOf(
            mapOf(MessageRecords.TENANT_ID to aggregateId.tenantId, MessageRecords.AGGREGATE_ID to aggregateId.id),
        )
        query.rangeOf(MessageRecords.VERSION).apply {
            requireNotNull(gte()).to(Int::class.java, WowJsonpMapper).assert().isEqualTo(2)
            requireNotNull(lte()).to(Int::class.java, WowJsonpMapper).assert().isEqualTo(5)
        }
    }

    @Test
    fun `time range bounds the create time`() {
        EventStreamSearches.timeRange(aggregateId, 10L, 20L).rangeOf(MessageRecords.CREATE_TIME).apply {
            requireNotNull(gte()).to(Long::class.java, WowJsonpMapper).assert().isEqualTo(10L)
            requireNotNull(lte()).to(Long::class.java, WowJsonpMapper).assert().isEqualTo(20L)
        }
    }

    @Test
    fun `all streams carry only the aggregate scope`() {
        EventStreamSearches.all(aggregateId).bool().filter().assert().hasSize(2)
    }

    @Test
    fun `initial streams after an id`() {
        val query = EventStreamSearches.initialStreamsAfter("after")

        val after = requireNotNull(query.rangeOf(MessageRecords.AGGREGATE_ID).gt())
        after.to(String::class.java, WowJsonpMapper).assert().isEqualTo("after")
        query.termValues()[MessageRecords.VERSION].assert().isEqualTo(Version.INITIAL_VERSION.toString())
    }

    @Test
    fun `version order breaks ties by stream id`() {
        listOf(false to SortOrder.Asc, true to SortOrder.Desc).forEach { (descending, order) ->
            val sort = EventStreamSearches.versionOrder(descending).map { it.field() }
            sort.map { it.field() }.assert().containsExactly(MessageRecords.VERSION, MessageRecords.ID)
            sort.map { it.order() }.assert().containsOnly(order)
        }
        EventStreamSearches.aggregateIdOrder.single().field().field().assert().isEqualTo(MessageRecords.AGGREGATE_ID)
    }
}
