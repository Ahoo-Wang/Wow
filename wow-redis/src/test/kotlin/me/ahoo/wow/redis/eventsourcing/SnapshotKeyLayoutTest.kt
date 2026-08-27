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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class SnapshotKeyLayoutTest {

    @Test
    fun `should convert snapshot key`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("id", "tenantId")
        val actual = SnapshotKeyLayout.key(aggregateId)
        actual.assert().isEqualTo("v2:snapshot:{dGNr.bW9ja19hZ2dyZWdhdGU.aWQ.dGVuYW50SWQ}")
    }

    @Test
    fun `should keep delimiter-like snapshot identities injective`() {
        val namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate
        val first = namedAggregate.aggregateId("a@ac", "t")
        val second = namedAggregate.aggregateId("a", "ac@t")

        SnapshotKeyLayout.key(first).assert()
            .isNotEqualTo(SnapshotKeyLayout.key(second))
    }

    @Test
    fun `should keep delimiter-like aggregate scopes injective`() {
        val first = MaterializedNamedAggregate("a.b", "c").aggregateId("id")
        val second = MaterializedNamedAggregate("a", "b.c").aggregateId("id")

        SnapshotKeyLayout.key(first).assert()
            .isNotEqualTo(SnapshotKeyLayout.key(second))
    }

    @Test
    fun `should encode special snapshot identity`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val aggregateId = namedAggregate.aggregateId("order@{42}:雪", "tenant@east}")
        val key = SnapshotKeyLayout.key(aggregateId)

        key.assert().isEqualTo(
            "v2:snapshot:{b3JkZXItc2VydmljZQ.b3JkZXI.b3JkZXJAezQyfTrpm6o.dGVuYW50QGVhc3R9}"
        )
    }
}
