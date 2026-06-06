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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

internal class RouteIdSpecTest {

    @Test
    fun `should build id with all segments`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendTenant(true)
            .appendOwner(true)
            .resourceName("snapshot")
            .operation("load")
            .build()
        id.assert().isEqualTo("order.tenant.owner.snapshot.load")
    }

    @Test
    fun `should build id with prefix only`() {
        val id = RouteIdSpec()
            .prefix("order")
            .build()
        id.assert().isEqualTo("order")
    }

    @Test
    fun `should build id with tenant appended`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendTenant(true)
            .build()
        id.assert().isEqualTo("order.tenant")
    }

    @Test
    fun `should build id with owner appended`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendOwner(true)
            .build()
        id.assert().isEqualTo("order.owner")
    }

    @Test
    fun `should build id with resource name and operation`() {
        val id = RouteIdSpec()
            .prefix("order")
            .resourceName("event")
            .operation("load")
            .build()
        id.assert().isEqualTo("order.event.load")
    }

    @Test
    fun `should build empty id when no segments set`() {
        val id = RouteIdSpec().build()
        id.assert().isEqualTo("")
    }

    @Test
    fun `should set prefix from named aggregate`() {
        val namedAggregate = MaterializedNamedAggregate("context", "aggregate")
        val id = RouteIdSpec()
            .aggregate(namedAggregate)
            .operation("send")
            .build()
        id.assert().isEqualTo("context.aggregate.send")
    }
}
