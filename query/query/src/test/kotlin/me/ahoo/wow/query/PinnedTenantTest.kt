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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.admission.QueryAdmission
import org.junit.jupiter.api.Test

/** Which admitted filters pin one tenant: only a tenant filter, alone or ANDed, of one value; nothing else. */
class PinnedTenantTest {
    private val schema = gatewaySchema()

    private fun pinned(filter: FilterExpression): String? = QueryAdmission.Trusted.list(
        ListQuery(filter),
        schema
    ).pinnedTenant

    @Test
    fun `a tenant filter alone or among ANDs pins its tenant`() {
        pinned(TenantIdFilter("t")).assert().isEqualTo("t")
        pinned(AndFilter(listOf(EqualFilter(QueryField("ownerId"), "o"), TenantIdFilter("t")))).assert().isEqualTo("t")
        pinned(AndFilter(listOf(AndFilter(listOf(TenantIdFilter("t"))), TenantIdFilter("t")))).assert().isEqualTo("t")
        QueryAdmission.Trusted.count(CountQuery(TenantIdFilter("t")), schema).pinnedTenant.assert().isEqualTo("t")
    }

    @Test
    fun `any other shape pins none`() {
        pinned(MatchAllFilter).assert().isNull()
        pinned(OrFilter(listOf(TenantIdFilter("t"), TenantIdFilter("u")))).assert().isNull()
        pinned(OrFilter(listOf(TenantIdFilter("t"), EqualFilter(QueryField("ownerId"), "o")))).assert().isNull()
        pinned(NorFilter(listOf(TenantIdFilter("t")))).assert().isNull()
        pinned(InFilter(QueryField("tenantId"), listOf("t"))).assert().isNull()
        pinned(EqualFilter(QueryField("tenantId"), "t")).assert().isNull()
        // Two tenants ANDed match nothing; neither is pinned.
        pinned(AndFilter(listOf(TenantIdFilter("t"), TenantIdFilter("u")))).assert().isNull()
    }
}
