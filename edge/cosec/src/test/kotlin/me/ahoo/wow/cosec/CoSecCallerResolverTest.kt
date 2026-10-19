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

package me.ahoo.wow.cosec

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest

class CoSecCallerResolverTest {
    private val resolver = CoSecCallerResolver(
        CoSecCallerOptions(
            defaultTenant = "home",
            crossScopeRoles = setOf("admin"),
            crossScopePolicies = setOf("wow-cross-scope"),
        ),
    )

    @Test
    fun `the resolver's name should be the one the host's springdoc bridge knows it by`() {
        // wow-spring-boot declares CoSec's bearer token for this resolver, by its name (WowOpenApiSecurity, D233).
        CoSecCallerResolver::class.java.name.assert().isEqualTo("me.ahoo.wow.cosec.CoSecCallerResolver")
    }

    @Test
    fun `an authenticated principal is the caller in its tenant, as its own owner and subject`() {
        val caller = resolver.callerOf(securityContext("alice", tenant = "acme"))!!
        caller.assert().isEqualTo(Caller("acme", Scope("alice"), subject = "alice"))
    }

    @Test
    fun `CoSec's default tenant is the host's default tenant`() {
        resolver.callerOf(securityContext("alice"))!!.tenantId.assert().isEqualTo("home")
        CoSecCallerResolver().callerOf(securityContext("alice"))!!.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
    }

    @Test
    fun `an anonymous principal is no caller`() {
        resolver.callerOf(SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS, SimpleTenant.DEFAULT)).assert()
            .isNull()
        resolver.callerOf(securityContext("(0)")).assert().isNull()
    }

    @Test
    fun `the principal acts in its only space, in none when it has several, unless it selects one`() {
        val none = resolver.callerOf(securityContext("alice", roles = setOf("user")))!!
        none.scope.spaceId.assert().isNull()
        val one = resolver.callerOf(securityContext("alice", roles = setOf("user", "editor@s1")))!!
        one.scope.assert().isEqualTo(Scope("alice", "s1"))
        val several = securityContext("alice", roles = setOf("editor@s1", "viewer@s2", "user@"))
        resolver.callerOf(several)!!.scope.spaceId.assert().isNull()
        resolver.callerOf(several, " s2 ")!!.scope.spaceId.assert().isEqualTo("s2")
        CoSecCallerResolver.spacesOf(several.principal).assert().containsExactly("s1", "s2")
    }

    @Test
    fun `a space the principal does not belong to is not selected`() {
        val one = securityContext("alice", roles = setOf("editor@s1"))
        resolver.callerOf(one, "s9")!!.scope.spaceId.assert().isEqualTo("s1")
        resolver.callerOf(securityContext("alice"), "s9")!!.scope.spaceId.assert().isNull()
        resolver.callerOf(one, "")!!.scope.spaceId.assert().isEqualTo("s1")
    }

    @Test
    fun `a configured role or policy lets the principal act across scopes`() {
        resolver.callerOf(securityContext("root", roles = setOf("admin")))!!.crossScope.assert().isTrue()
        resolver.callerOf(securityContext("root", policies = setOf("wow-cross-scope")))!!.crossScope.assert().isTrue()
        resolver.callerOf(securityContext("bob", roles = setOf("admin@s1", "user")))!!.crossScope.assert().isFalse()
        CoSecCallerResolver().callerOf(securityContext("root", roles = setOf("admin")))!!.crossScope.assert().isFalse()
    }

    @Test
    fun `only a principal of a configured service role or policy is a service identity`() {
        val services = CoSecCallerResolver(
            CoSecCallerOptions(serviceRoles = setOf("service"), servicePolicies = setOf("wow-service")),
        )
        services.callerOf(securityContext("basket", roles = setOf("service")))!!.service.assert().isTrue()
        services.callerOf(securityContext("basket", policies = setOf("wow-service")))!!.service.assert().isTrue()
        services.callerOf(securityContext("mallory", roles = setOf("service@s1")))!!.service.assert().isFalse()
        resolver.callerOf(securityContext("basket", roles = setOf("service")))!!.service.assert().isFalse()
    }

    @Test
    fun `a configured role or policy makes the principal a tenant-level caller, without an owner`() {
        val levels = CoSecCallerResolver(
            CoSecCallerOptions(tenantRoles = setOf("employee"), tenantPolicies = setOf("wow-tenant")),
        )
        val employee = levels.callerOf(securityContext("erin", roles = setOf("employee", "editor@s1")))!!
        employee.scope.assert().isEqualTo(Scope(spaceId = "s1"))
        employee.subject.assert().isEqualTo("erin")
        levels.callerOf(securityContext("erin", policies = setOf("wow-tenant")))!!.scope.ownerId.assert().isNull()
        levels.callerOf(securityContext("bob", roles = setOf("employee@s1")))!!.scope.ownerId.assert().isEqualTo("bob")
        resolver.callerOf(securityContext("erin", roles = setOf("employee")))!!.scope.ownerId.assert().isEqualTo("erin")
    }

    @Test
    fun `roles, policies and abac attributes are the caller's attributes`() {
        val context = securityContext(
            "alice",
            roles = setOf("user", "editor@s1"),
            policies = setOf("p2", "p1"),
            attributes = mapOf(
                "abac.dept" to " sales , ops ,sales",
                "abac.region" to listOf("eu", null, " "),
                "abac.level" to arrayOf("1", "2"),
                "abac.number" to 7,
                "abac." to "nameless",
                "abac.empty" to "",
                "email" to "alice@example.com",
            ),
        )
        resolver.callerOf(context)!!.attributes.assert().isEqualTo(
            mapOf(
                CoSecCallerResolver.ROLES to listOf("editor@s1", "user"),
                CoSecCallerResolver.POLICIES to listOf("p1", "p2"),
                "abac.dept" to listOf("sales", "ops"),
                "abac.region" to listOf("eu"),
                "abac.level" to listOf("1", "2"),
                "abac.number" to listOf("7"),
            ),
        )
        resolver.callerOf(securityContext("bob"))!!.attributes.assert().isEmpty()
    }

    @Test
    fun `a request reads the security context CoSec's filter put on the exchange and its Wow-Space`() = runTest {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/shop/cart/c-1/add-item").header(WowHeaders.SPACE, "s2"),
        )
        resolver.resolve(request(exchange)).assert().isNull()
        exchange.setSecurityContext(securityContext("alice", "acme", setOf("a@s1", "b@s2")))
        resolver.resolve(request(exchange))!!.scope.assert().isEqualTo(Scope("alice", "s2"))
    }

    @Test
    fun `the application is the CoSec-App-Id CoSec authorized the request in by one of the application's roles`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/view-store/view/v-1/edit").header(AppIdCapable.APP_ID_KEY, " console "),
        )
        val byAppRole = securityContext("alice").authorized(coSecRequest(exchange), appRolePermission)
        val caller = resolver.callerOf(byAppRole)!!
        caller.app.assert().isEqualTo("console")
        caller.attributes.assert().isEqualTo(mapOf(Caller.APP to listOf("console")))
    }

    @Test
    fun `no application when CoSec allowed the request otherwise, denied it, did not authorize it or none is named`() {
        val named = MockServerWebExchange.from(
            MockServerHttpRequest.post("/view-store/view/v-1/edit").header(AppIdCapable.APP_ID_KEY, "console"),
        )
        val unnamed = MockServerWebExchange.from(MockServerHttpRequest.post("/view-store/view/v-1/edit"))
        val denied = RoleVerifyContext(
            appRolePermission.roleId,
            appRolePermission.permission,
            VerifyResult.EXPLICIT_DENY,
        )
        listOf(
            securityContext("root").authorized(coSecRequest(named), RootVerifyContext),
            securityContext("alice").authorized(coSecRequest(named), denied),
            securityContext("alice").authorized(coSecRequest(unnamed), appRolePermission),
            // Injected from a token without CoSec's authorization, as the inject filter and the internal port do.
            securityContext("alice"),
        ).forEach { context -> resolver.callerOf(context)!!.app.assert().isNull() }
    }

    @Test
    fun `a host that trusts injected applications takes the one of a request CoSec did not authorize here`() {
        val trusting = CoSecCallerResolver(CoSecCallerOptions(trustInjectedApp = true))
        val named = MockServerWebExchange.from(
            MockServerHttpRequest.post("/view-store/view/v-1/edit").header(AppIdCapable.APP_ID_KEY, " console "),
        )
        val blank = MockServerWebExchange.from(
            MockServerHttpRequest.post("/view-store/view/v-1/edit").header(AppIdCapable.APP_ID_KEY, " "),
        )
        val injected = securityContext("alice").also { it.setRequest(coSecRequest(named)) }
        trusting.callerOf(injected)!!.app.assert().isEqualTo("console")
        resolver.callerOf(injected)!!.app.assert().isNull()
        trusting.callerOf(securityContext("alice").also { it.setRequest(coSecRequest(blank)) })!!.app.assert().isNull()
        // No request on the context, as on the internal port.
        trusting.callerOf(securityContext("alice"))!!.app.assert().isNull()
        // A request CoSec authorized here keeps needing a permission of the application's roles.
        trusting.callerOf(securityContext("root").authorized(coSecRequest(named), RootVerifyContext))!!.app.assert()
            .isNull()
        trusting.callerOf(securityContext("bob").authorized(coSecRequest(named), appRolePermission))!!.app.assert()
            .isEqualTo("console")
    }

    @Test
    fun `options are not blank`() {
        assertThrows<IllegalArgumentException> { CoSecCallerOptions(defaultTenant = " ") }
        assertThrows<IllegalArgumentException> { CoSecCallerOptions(attributePrefix = "") }
    }

    private fun request(exchange: MockServerWebExchange): ServerRequest =
        ServerRequest.create(exchange, ServerCodecConfigurer.create().readers)
}
