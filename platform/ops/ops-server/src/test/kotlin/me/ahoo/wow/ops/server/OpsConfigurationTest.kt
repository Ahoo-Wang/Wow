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

package me.ahoo.wow.ops.server

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.spring.boot.WowProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import reactor.core.publisher.Mono
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

@OptIn(InternalWowApi::class) // The host's settings, as the configuration reads them.
class OpsConfigurationTest {
    private val resume = CommandAction(
        CommandType("ops.parked.Resume", Resume::class.java),
        AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, "p-1"),
        Scope.NONE,
        Creation.NONE,
    )

    /** A caller without the operator's role, whom only CoSec's policies may let act. */
    private val granted = Caller.of(subject = "grace")

    private fun authorize(
        policies: CoSecAuthorization?,
        authorizerEnabled: Boolean,
        caller: Caller = granted,
        defaultTenant: String? = null,
    ): Authorization {
        val beans = StaticListableBeanFactory()
        policies?.let { beans.addBean("cosecAuthorization", it) }
        val wow = WowProperties().apply {
            cosec.authorizer.enabled = authorizerEnabled
            defaultTenant?.let { tenant.default = it }
        }
        val authorizer = OpsConfiguration().opsAuthorizer(beans.getBeanProvider(CoSecAuthorization::class.java), wow)
        return runBlocking { authorizer.authorize(caller, resume) }
    }

    @Test
    fun `CoSec's policies should decide who is an operator when its authorizer is on`() {
        authorize({ _, _ -> Mono.just(AuthorizeResult.ALLOW) }, authorizerEnabled = true).assert()
            .isEqualTo(Authorization.Allowed)
        authorize({ _, _ -> Mono.just(AuthorizeResult.IMPLICIT_DENY) }, authorizerEnabled = true).assert()
            .isInstanceOf(Authorization.Denied::class.java)
    }

    @Test
    fun `the operator's role should decide without CoSec's authorization or with its authorizer off`() {
        authorize({ _, _ -> Mono.just(AuthorizeResult.ALLOW) }, authorizerEnabled = false).assert()
            .isInstanceOf(Authorization.Denied::class.java)
        authorize(null, authorizerEnabled = true).assert().isInstanceOf(Authorization.Denied::class.java)
    }

    @Test
    fun `CoSec's policies should see Wow's default tenant, as configured, as CoSec's default one`() {
        val tenants = mutableListOf<String>()
        val policies = CoSecAuthorization { _, context ->
            tenants += context.tenant.tenantId
            Mono.just(AuthorizeResult.ALLOW)
        }
        authorize(policies, authorizerEnabled = true, caller = Caller.of(tenantId = "platform", subject = "grace"))
        authorize(policies, true, Caller.of(tenantId = "platform", subject = "grace"), defaultTenant = "platform")
        authorize(policies, true, Caller.of(subject = "grace"), defaultTenant = " ")
        tenants.assert().containsExactly("platform", Tenant.DEFAULT_TENANT_ID, Tenant.DEFAULT_TENANT_ID)
    }
}
