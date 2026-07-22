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

import io.lettuce.core.cluster.SlotHash
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toKeyPrefix
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventStreamKeyLayoutTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")
    private val bucket = "id".hashCode().mod(128)
    private val hashTag = "v2:es:dGNr.bW9ja19hZ2dyZWdhdGU:$bucket"

    @Test
    fun `should convert to key prefix`() {
        val actual = aggregateId.toKeyPrefix()
        actual.assert().isEqualTo("{$hashTag}:")
    }

    @Test
    fun `should convert event stream key`() {
        val actual = EventStreamKeyLayout.key(aggregateId)
        actual.assert().isEqualTo("{$hashTag}:aWQ.dGVuYW50SWQ")
    }

    @Test
    fun `should convert aggregate id index key`() {
        val actual = aggregateId.toAggregateIdIndexKey()
        actual.assert().isEqualTo("{$hashTag}:ids")
    }

    @Test
    fun `should convert aggregate id index member`() {
        val actual = EventStreamKeyLayout.toAggregateIdIndexMember(aggregateId)
        actual.assert().isEqualTo("00690064.dGVuYW50SWQ")
    }

    @Test
    fun `should convert aggregate id index member lower bound`() {
        val actual = EventStreamKeyLayout.toAggregateIdIndexMemberLowerBound("id")
        actual.assert().isEqualTo("00690064/")
    }

    @Test
    fun `should convert aggregate id from aggregate id index member`() {
        val actual = EventStreamKeyLayout.toAggregateIdFromIndexMember(
            aggregateId.namedAggregate,
            "00690064.dGVuYW50SWQ",
        )

        actual.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should convert aggregate id from aggregate id index member with empty tenant id`() {
        val actual = EventStreamKeyLayout.toAggregateIdFromIndexMember(
            aggregateId.namedAggregate,
            "00690064.",
        )

        actual.assert().isEqualTo(aggregateId.namedAggregate.aggregateId("id", ""))
    }

    @Test
    fun `should keep delimiter-like identities injective`() {
        val namedAggregate = aggregateId.namedAggregate
        val first = namedAggregate.aggregateId("a@ac", "t")
        val second = namedAggregate.aggregateId("a", "ac@t")

        EventStreamKeyLayout.key(first).assert()
            .isNotEqualTo(EventStreamKeyLayout.key(second))
        EventStreamKeyLayout.toAggregateIdIndexMember(first).assert()
            .isNotEqualTo(EventStreamKeyLayout.toAggregateIdIndexMember(second))
    }

    @Test
    fun `should keep delimiter-like aggregate scopes injective`() {
        val first = MaterializedNamedAggregate("a.b", "c").aggregateId("id")
        val second = MaterializedNamedAggregate("a", "b.c").aggregateId("id")

        EventStreamKeyLayout.key(first).assert()
            .isNotEqualTo(EventStreamKeyLayout.key(second))
    }

    @Test
    fun `should encode special identity with bucket aligned keys`() {
        val specialAggregateId = MaterializedNamedAggregate("order-service", "order")
            .aggregateId("order@{42}:雪", "tenant@east}")
        val bucket = specialAggregateId.id.hashCode().mod(128)
        val eventKey = EventStreamKeyLayout.key(specialAggregateId)
        val indexKey = specialAggregateId.toAggregateIdIndexKey()
        val requestIdKey = "$eventKey:req_idx"

        bucket.assert().isEqualTo(6)
        eventKey.assert().isEqualTo(
            "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:6}:b3JkZXJAezQyfTrpm6o.dGVuYW50QGVhc3R9"
        )
        EventStreamKeyLayout.toAggregateIdIndexMember(specialAggregateId).assert()
            .isEqualTo("006f00720064006500720040007b00340032007d003a96ea.dGVuYW50QGVhc3R9")
        listOf(eventKey, requestIdKey, indexKey)
            .map(SlotHash::getSlot)
            .distinct()
            .assert().hasSize(1)
    }

    @Test
    fun `should preserve aggregate id order in index members`() {
        val namedAggregate = aggregateId.namedAggregate
        val ids = listOf("`", "a", "a\u0000", "aa", "z", "é", "中", "😀", "\uE000")
        val expected = ids.sorted()
        val actual = ids
            .map { namedAggregate.aggregateId(it, "tenant") }
            .map(EventStreamKeyLayout::toAggregateIdIndexMember)
            .sorted()
            .map { EventStreamKeyLayout.toAggregateIdFromIndexMember(namedAggregate, it).id }

        actual.assert().containsExactly(*expected.toTypedArray())
    }

    @Test
    fun `should place prefix extensions after aggregate id lower bound`() {
        val namedAggregate = aggregateId.namedAggregate
        val lowerBound = EventStreamKeyLayout.toAggregateIdIndexMemberLowerBound("a")

        EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("a", "tenant"))
            .compareTo(lowerBound).assert().isLessThan(0)
        EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("a\u0000", "tenant"))
            .compareTo(lowerBound).assert().isGreaterThan(0)
        EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("`", "tenant"))
            .compareTo(lowerBound).assert().isLessThan(0)
        EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("b", "tenant"))
            .compareTo(lowerBound).assert().isGreaterThan(0)
    }

    @Test
    fun `should reject malformed aggregate id index members`() {
        listOf(
            "0069",
            "xyz.dGVuYW50",
            "gggg.",
            "d800.",
            "dc00.",
            "0069.A",
            "0069._w",
            "0069.AB",
            "0069.*",
            "006.dGVuYW50",
        ).forEach { member ->
            assertThrows<IllegalArgumentException> {
                EventStreamKeyLayout.toAggregateIdFromIndexMember(aggregateId.namedAggregate, member)
            }
        }
    }

    @Test
    fun `should reject malformed unicode identity`() {
        listOf("\uD800", "\uDC00").forEach { malformedId ->
            assertThrows<IllegalArgumentException> {
                EventStreamKeyLayout.key(
                    aggregateId.namedAggregate.aggregateId(malformedId, "tenant")
                )
            }
        }
    }

    @Test
    fun `should reject empty aggregate id`() {
        assertThrows<IllegalArgumentException> {
            EventStreamKeyLayout.key(aggregateId.namedAggregate.aggregateId("", "tenant"))
        }
    }
}
