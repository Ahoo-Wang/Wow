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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test

/** `wow.query.cross-tenant.enabled` (decision D254 ⑦) and the query tenant of a cross-tenant caller. */
class HostCrossTenantQueryTest {
    @Test
    fun `cross-tenant queries are off by default and the key turns them on`() {
        QueryProperties().crossTenant.enabled.assert().isFalse()
        val host = startHost("wow.query.cross-tenant.enabled" to true)
        try {
            host.context.getBean(HttpQueries::class.java).crossTenant.assert().isTrue()
        } finally {
            host.close()
        }
    }

    @Test
    fun `a cross-tenant caller's query of a fixed-tenant type stays in that tenant`() {
        val fixed = AggregateType("shop", "catalog")
        val targets = QueryTargets(TenantPolicy.fixed(mapOf(fixed to "platform")), crossTenant = true)
        val caller = Caller(tenantId = "t1", crossScope = true)
        targets.of(
            fixed,
            caller,
            reads = CrossTenant.ALL
        ).crossTenant.assert().isEqualTo(CrossTenant(setOf("platform")))
        targets.of(AggregateType("shop", "cart"), caller, reads = CrossTenant.ALL).run {
            crossTenant.assert().isEqualTo(CrossTenant.ALL)
            tenantId.assert().isNull()
        }
        targets.of(AggregateType("shop", "cart"), caller).tenantId.assert().isEqualTo("t1")
    }
}
