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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MigrationMapTest {
    @Test
    fun `the example's map reads`() {
        val map = NineXExample.map
        map.aggregates.map { it.from }.assert().containsExactly("example-service.order", "example-service.cart")
        val order = map.aggregates.first()
        order.source().assert().isEqualTo(NineXAggregate("example-service", "order"))
        order.events.first().assert().isEqualTo(
            EventMapping("order_created", "0.0.1", "OrderCreated", 1, upgrade = "upgradeOrderCreatedFrom1"),
        )
        map.headers.assert().isEmpty()
        order.state.assert().isEqualTo(StateMapping())
    }

    @Test
    fun `format 1 maps the state fields of an aggregate when told`() {
        val map = MigrationMap.read(
            """{"format":1,"aggregates":[{"from":"s.order","to":"c.order",
               "state":{"fields":{"orderStatus":"status"},"ignore":["items.totalPrice"]}}]}""",
        )
        map.aggregates.single().state.assert()
            .isEqualTo(StateMapping(mapOf("orderStatus" to "status"), listOf("items.totalPrice")))
        assertThrows<Exception> {
            MigrationMap.read("""{"format":1,"aggregates":[{"from":"s.order","to":"c.order","state":{"rename":{}}}]}""")
        }
    }

    @Test
    fun `another format, or a property format 1 does not define, is refused`() {
        assertThrows<Exception> { MigrationMap.read("""{"format":2,"aggregates":[]}""") }
            .message!!.assert().contains("format 2")
        assertThrows<Exception> { MigrationMap.read("""{"format":1,"aggregates":[],"extra":true}""") }
    }

    @Test
    fun `headers keep request facts only, under a name`() {
        MigrationMap(1, emptyList(), mapOf("app_id" to "app")).headers.assert().containsEntry("app_id", "app")
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("trace_id" to "trace")) }
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("command_wait_stage" to "x")) }
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("app_id" to " ")) }
    }

    @Test
    fun `aggregate names have both parts`() {
        AggregateMapping("a.b.order", "ctx.order").source().assert().isEqualTo(NineXAggregate("a.b", "order"))
        assertThrows<IllegalArgumentException> { AggregateMapping("order", "ctx.order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.", "ctx.order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", "order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", ".order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", "ctx.order", defaultCommand = " ") }
    }

    @Test
    fun `an event mapping names its target or drops it`() {
        EventMapping("retired", drop = true).to.assert().isNull()
        assertThrows<IllegalArgumentException> { EventMapping(" ", to = "A") }
        assertThrows<IllegalArgumentException> { EventMapping("a") }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = "A", drop = true) }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = " ") }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = "A", toRevision = 0) }
        assertThrows<IllegalArgumentException> { EventMapping("a", drop = true, fields = mapOf("x" to "y")) }
    }

    @Test
    fun `a command mapping names both commands`() {
        assertThrows<IllegalArgumentException> { CommandMapping(" ", "A") }
        assertThrows<IllegalArgumentException> { CommandMapping("a", " ") }
    }
}
