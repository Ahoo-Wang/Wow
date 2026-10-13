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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

class ClickHouseRowsTest {
    @Test
    fun `a record should map to one row of the base table`() {
        val row = ClickHouseRows.rowOf(SinkFixtures.recordOf())
        row.propertyNames().toList().assert()
            .containsExactlyElementsOf(ClickHouseSinkSchema.BASE_COLUMNS.map { it.name })
        row.get("id").asString().assert().isEqualTo("shop.order/tenant-1/order-1@1")
        row.get("tenant_id").asString().assert().isEqualTo("tenant-1")
        row.get("aggregate_id").asString().assert().isEqualTo("order-1")
        row.get("version").asInt().assert().isEqualTo(1)
        row.get("command_id").asString().assert().isEqualTo("command-1")
        row.get("request_id").asString().assert().isEqualTo("request-1")
        row.get("command_type").asString().assert().isEqualTo("shop.order.PlaceOrder")
        // 1_760_000_000_124 ms after the epoch, in UTC.
        row.get("commit_time").asString().assert().isEqualTo("2025-10-09 08:53:20.124")
        row.get("owner_id").asString().assert().isEqualTo("owner-1")
        row.get("space_id").asString().assert().isEmpty()
        row.get("deleted").asBoolean().assert().isFalse()
        WowJson.mapper.writeValueAsString(row.get("tags")).assert().isEqualTo("""{"color":["red","blue"]}""")
        row.get("correlation_id").asString().assert().isEqualTo("correlation-1")
        row.get("causation_id").asString().assert().isEqualTo("upstream@1")
        WowJson.mapper.writeValueAsString(row.get("headers")).assert().isEqualTo("""{"app":"web"}""")
        row.get("operator_id").asString().assert().isEqualTo("alice")
        row.get("operator_tenant_id").asString().assert().isEqualTo("tenant-0")
        row.get("operator_cross_scope").asBoolean().assert().isTrue()
        row.get("state_fingerprint").asString().assert().isEqualTo("fingerprint-1")
    }

    @Test
    fun `numbers should keep their exact text in the events, the state and the state's numbers`() {
        val row = ClickHouseRows.rowOf(SinkFixtures.recordOf())
        WowJson.mapper.writeValueAsString(row.get("events")).assert().isEqualTo(
            """[{"name":"order_placed","revision":1,"payload":"{\"total\":12.30,\"lines\":[{\"price\":0.10}]}"},""" +
                """{"name":"order_noted","revision":2,"payload":"{\"note\":\"fast\"}"}]""",
        )
        row.get("state").asString().assert().isEqualTo(SinkFixtures.stateOf(1))
        WowJson.mapper.writeValueAsString(row.get("state_numbers")).assert().isEqualTo(
            """{"quantity":"1","total":"12.30","price":"1500","rate":"0.5","version":"1",""" +
                """"big":"123456789012345678901234567.123456789"}""",
        )
        ClickHouseRows.rowOf(SinkFixtures.recordOf(state = """{"tiny":1E-7,"huge":1E+3}"""))
            .get("state_numbers").let { WowJson.mapper.writeValueAsString(it) }
            .assert().isEqualTo("""{"tiny":"0.0000001","huge":"1000"}""")
    }

    @Test
    fun `a record without an operator should leave the operator columns empty`() {
        val row = ClickHouseRows.rowOf(SinkFixtures.recordOf(operator = null))
        row.get("operator_id").asString().assert().isEmpty()
        row.get("operator_tenant_id").asString().assert().isEmpty()
        row.get("operator_cross_scope").asBoolean().assert().isFalse()
    }

    @Test
    fun `a record without its state should leave the state columns empty`() {
        val row = ClickHouseRows.rowOf(SinkFixtures.recordOf(state = null))
        row.get("state").asString().assert().isEmpty()
        row.get("state_fingerprint").asString().assert().isEmpty()
        row.get("state_numbers").isEmpty.assert().isTrue()
    }

    @Test
    fun `the body should hold one row per line`() {
        val body = ClickHouseRows.bodyOf(listOf(SinkFixtures.recordOf(version = 1), SinkFixtures.recordOf(version = 2)))
            .toString(Charsets.UTF_8)
        val lines = body.removeSuffix("\n").split("\n")
        lines.size.assert().isEqualTo(2)
        WowJson.mapper.readTree(lines[1]).get("id").asString().assert().isEqualTo("shop.order/tenant-1/order-1@2")
    }
}
