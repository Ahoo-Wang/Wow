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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.query.admission.QueryTargetException
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test

/** The read side's scope authorization, decided once in `:query` for every edge (review R5.C-1). */
class QueryTargetsTest {
    private val cart = AggregateType("shop", "cart")
    private val operator = Caller(
        tenantId = TENANT,
        scope = Scope("alice", "s-1"),
        subject = "alice",
        crossScope = true,
        attributes = mapOf("abac.region" to listOf("eu")),
    )
    private val user = operator.copyWith(crossScope = false)
    private val fixed = TenantPolicy.fixed(mapOf(cart to "platform"))

    @Test
    fun `a caller naming nothing reads in its own tenant and scope, on the given entry`() {
        val mapped = QueryTargets().of(cart, user)
        mapped.entry.assert().isEqualTo(QueryEntry.HTTP)
        mapped.tenantId.assert().isEqualTo(TENANT)
        mapped.principal.assert().isEqualTo(user.scope)
        mapped.subject.assert().isEqualTo("alice")
        mapped.attributes.assert().isEqualTo(user.attributes)
        mapped.target.assert().isNull()
        QueryTargets().of(cart, user, entry = QueryEntry.IN_PROCESS).entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
    }

    @Test
    fun `a cross-scope caller reads in the scope it names`() {
        val target = TargetScope.of("other", Scope("bob"))
        val across = QueryTargets().of(cart, operator, target)
        across.tenantId.assert().isEqualTo("other")
        across.principal.assert().isEqualTo(Scope("bob"))
        across.target.assert().isEqualTo(target)
        across.caller!!.crossScope.assert().isTrue()
    }

    @Test
    fun `a caller that may not act across scopes names no other scope and no tenants`() {
        refusal { QueryTargets().of(cart, user, TargetScope.of("other")) }
            .errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
        refusal { QueryTargets(crossTenant = true).of(cart, user, reads = CrossTenant.ALL) }
            .errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
        refusal { QueryTargets().admit(user, TargetScope.of(scope = Scope("bob"))) }
            .errorCode.assert().isEqualTo(QueryErrors.FORBIDDEN)
        QueryTargets().admit(user)
        QueryTargets().admit(operator, TargetScope.of("other"))
    }

    @Test
    fun `a cross-tenant read is refused while cross-tenant queries are off`() {
        refusal { QueryTargets().of(cart, operator, reads = CrossTenant.ALL) }
            .errorMsg.assert().contains("wow.query.allow-cross-tenant")
    }

    @Test
    fun `a cross-tenant read reads its tenants, in the scope it names`() {
        val all = QueryTargets(crossTenant = true).of(cart, operator, TargetScope.of(), CrossTenant.ALL)
        all.crossTenant.assert().isEqualTo(CrossTenant.ALL)
        all.tenantId.assert().isNull()
        all.principal.assert().isEqualTo(Scope.NONE)
    }

    @Test
    fun `a fixed tenant narrows a cross-tenant read, and refuses one that leaves it out`() {
        QueryTargets(fixed, crossTenant = true).of(cart, operator, reads = CrossTenant.ALL)
            .crossTenant.assert().isEqualTo(CrossTenant(setOf("platform")))
        QueryTargets(fixed, crossTenant = true).of(cart, operator, reads = CrossTenant(setOf("platform", "t2")))
            .crossTenant.assert().isEqualTo(CrossTenant(setOf("platform")))
        refusal {
            QueryTargets(fixed, crossTenant = true).of(cart, operator, reads = CrossTenant(setOf("t2")))
        }.errorCode.assert().isEqualTo(QueryErrors.MALFORMED)
    }

    @Test
    fun `a fixed tenant holds whoever reads, and refuses a target naming another`() {
        QueryTargets(fixed).of(cart, user).tenantId.assert().isEqualTo("platform")
        QueryTargets(fixed).of(cart, operator, TargetScope.of("platform")).tenantId.assert().isEqualTo("platform")
        QueryTargets(fixed).of(AggregateType("shop", "order"), user).tenantId.assert().isEqualTo(TENANT)
        refusal { QueryTargets(fixed).of(cart, operator, TargetScope.of("other")) }
            .errorCode.assert().isEqualTo(QueryErrors.MALFORMED)
    }

    @Test
    fun `a caller reaches its own tenant, and every one when it may act across scopes`() {
        TenantPolicy.reaches(user, TENANT).assert().isTrue()
        TenantPolicy.reaches(user, "other").assert().isFalse()
        TenantPolicy.reaches(operator, "other").assert().isTrue()
    }

    private fun refusal(read: () -> Any?): QueryTargetException =
        runCatching(read).exceptionOrNull() as QueryTargetException

    private companion object {
        const val TENANT = "t-1"
    }
}
