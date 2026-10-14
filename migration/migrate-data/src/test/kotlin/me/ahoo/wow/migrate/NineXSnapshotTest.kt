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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NineXSnapshotTest {
    @Test
    fun `the example's snapshots read, holding the captured 9x states`() {
        val order = NineXExample.snapshot("order-snapshot.json")
        order.aggregate.assert().isEqualTo(NineXAggregate("example-service", "order"))
        order.aggregateId.assert().isEqualTo(NineXAggregateId("0VY3GwaK4OmV00E", "(0)"))
        order.version.assert().isEqualTo(4)
        order.ownerId.assert().isEqualTo("customer-1")
        order.spaceId.assert().isEmpty()
        order.deleted.assert().isFalse()
        order.tags.assert().isEmpty()
        order.state.assert().isEqualTo(WowJson.mapper.readTree(NineXExample.resource("order-state.json")))
        NineXExample.snapshot("cart-snapshot.json").state.assert()
            .isEqualTo(WowJson.mapper.readTree(NineXExample.resource("cart-state.json")))
    }

    @Test
    fun `a MongoDB document has its aggregate id as _id, and tags as lists`() {
        val snapshot = NineXSnapshot.parse(
            """{"_id":"o-1","contextName":"c","aggregateName":"a","tenantId":"t","version":2,"state":{},
               "tags":{"region":["east","west"]},"deleted":true}""",
        )
        snapshot.aggregateId.assert().isEqualTo(NineXAggregateId("o-1", "t"))
        snapshot.tags.assert().containsEntry("region", listOf("east", "west"))
        snapshot.deleted.assert().isTrue()
        snapshot.ownerId.assert().isEmpty()
    }

    @Test
    fun `a snapshot without what it needs is refused, naming the field`() {
        val whole = """"contextName":"c","aggregateName":"a","tenantId":"t","version":2,"state":{}"""
        assertThrows<MigrationException> { NineXSnapshot.parse("{$whole}") }.message!!.assert()
            .contains("no aggregateId")
        assertThrows<MigrationException> {
            NineXSnapshot.parse("""{"_id":"o-1",${whole.replace("\"tenantId\":\"t\",", "")}}""")
        }.message!!.assert().contains("o-1 has no tenantId")
        assertThrows<MigrationException> {
            NineXSnapshot.parse("""{"_id":"o-1",${whole.replace("\"version\":2,", "")}}""")
        }.message!!.assert().contains("no version")
        assertThrows<MigrationException> {
            NineXSnapshot.parse("""{"_id":"o-1",${whole.replace("{}", "null")}}""")
        }.message!!.assert().contains("no state")
        assertThrows<IllegalArgumentException> { NineXSnapshot.parse("[]") }
    }
}
