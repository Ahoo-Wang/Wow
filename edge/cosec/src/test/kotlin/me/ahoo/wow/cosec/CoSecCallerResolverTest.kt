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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.testkit.webflux.CoSecTestBindings
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest

/** [CoSecCallerResolver] over the security contexts CoSec's pipeline permits requests in (decision D367). */
@OptIn(ExperimentalCoSecApi::class) // CoSec's test bindings are experimental in 8.0.
class CoSecCallerResolverTest {
    private val cosec = CoSecPipeline()
    private val resolver = CoSecCallerResolver(
        CoSecCallerOptions(defaultTenant = "home", crossScopeRoles = setOf("admin")),
    )

    private fun callerOf(
        token: String?,
        headers: Map<String, String> = emptyMap(),
        resolver: CoSecCallerResolver = this.resolver,
        cosec: CoSecPipeline = this.cosec,
    ): Caller? = runBlocking { resolver.callerOf(cosec.security(token, headers)) }

    @Test
    fun `the resolver's name should be the one the host's springdoc bridge knows it by`() {
        // wow-spring-boot declares CoSec's bearer token for this resolver, by its name (WowOpenApiSecurity, D233).
        CoSecCallerResolver::class.java.name.assert().isEqualTo("me.ahoo.wow.cosec.CoSecCallerResolver")
    }

    @Test
    fun `an authenticated principal is the caller in its tenant, as its own owner and subject`() {
        callerOf("alice;acme").assert().isEqualTo(Caller("acme", Scope("alice"), subject = "alice"))
    }

    @Test
    fun `CoSec's default tenant is the host's default tenant`() {
        callerOf("alice")!!.tenantId.assert().isEqualTo("home")
    }

    @Test
    fun `an anonymous principal is no caller`() {
        callerOf(null).assert().isNull()
    }

    @Test
    fun `a security context whose principal no credential proved is no caller, whatever its id`() {
        resolver.callerOf(unproven("alice", roles = listOf("admin"))).assert().isNull()
    }

    @Test
    fun `an authenticated principal whose id is blank is no caller, a tenant-level role or not`() {
        // A blank id proves no identity: answered as an unproven principal is (#6645).
        callerOf(" ;acme").assert().isNull()
        callerOf("\t;acme;admin").assert().isNull()
        val tenantLevel = CoSecCallerResolver(CoSecCallerOptions(tenantRoles = setOf("employee")))
        callerOf(" ;acme;employee", resolver = tenantLevel).assert().isNull()
        // An id with white space around it is still an id: nothing is trimmed.
        callerOf(" a ;acme").assert().isEqualTo(Caller("acme", Scope(" a "), subject = " a "))
    }

    @Test
    fun `the space is the one CoSec validated, none in its default space, and Wow-Space is not read`() {
        callerOf("alice;acme;editor@s1,viewer@s2", mapOf(CoSecParameters.SPACE_ID to "s2"))!!.scope
            .assert().isEqualTo(Scope("alice", "s2"))
        callerOf("alice;acme;editor@s1", mapOf(WowHeaders.SPACE to "s1"))!!.scope.assert().isEqualTo(Scope("alice"))
    }

    @Test
    fun `the roles are the principal's in its scope`() {
        callerOf("alice;acme;editor@s1,viewer", mapOf(CoSecParameters.SPACE_ID to "s1"))!!
            .attributes[CoSecCallerResolver.ROLES].assert().isEqualTo(listOf("editor"))
        callerOf("alice;acme;editor@s1,viewer")!!.attributes[CoSecCallerResolver.ROLES].assert()
            .isEqualTo(listOf("viewer"))
    }

    @Test
    fun `the application is the app of CoSec's scope, the credential's or the host's`() {
        callerOf("alice;acme;;console")!!.app.assert().isEqualTo("console")
        callerOf("alice;acme")!!.app.assert().isNull()
        // A client declares no app of its own: the header is not read.
        callerOf("alice;acme", mapOf("CoSec-App-Id" to "console"))!!.app.assert().isNull()
        val host = CoSecPipeline(host = "shop-service")
        callerOf("alice;acme;;shop-service", cosec = host)!!.app.assert().isEqualTo("shop-service")
    }

    @Test
    fun `a configured role lets the principal act across scopes`() {
        callerOf("root;acme;admin")!!.crossScope.assert().isTrue()
        callerOf("alice;acme;editor")!!.crossScope.assert().isFalse()
        // A role in another space is not the principal's in this request's scope.
        callerOf("root;acme;admin@s1")!!.crossScope.assert().isFalse()
    }

    @Test
    fun `no principal is a service identity, a service names itself on the internal port (D300)`() {
        callerOf("basket;acme;service")!!.service.assert().isFalse()
    }

    @Test
    fun `a configured role makes the principal a tenant-level caller, without an owner`() {
        val tenantLevel = CoSecCallerResolver(CoSecCallerOptions(tenantRoles = setOf("employee")))
        callerOf("emma;acme;employee", resolver = tenantLevel)!!.scope.assert().isEqualTo(Scope.NONE)
        // A role in another space is not the principal's in this request's scope.
        callerOf("emma;acme;employee@s1", resolver = tenantLevel)!!.scope.assert().isEqualTo(Scope("emma"))
        callerOf("bob;acme;buyer", resolver = tenantLevel)!!.scope.assert().isEqualTo(Scope("bob"))
    }

    @Test
    fun `roles and the principal's attributes are the caller's attributes, each by its own name`() {
        callerOf("alice;acme;b,a;;dept=sales,name=Alice,abac.region=eu")!!.attributes.assert().isEqualTo(
            mapOf(
                CoSecCallerResolver.ROLES to listOf("a", "b"),
                "dept" to listOf("sales"),
                "name" to listOf("Alice"),
                "abac.region" to listOf("eu"),
            ),
        )
        callerOf("bob")!!.attributes.assert().isEmpty()
    }

    @Test
    fun `an attribute of the principal named as one CoSec gives the caller is not a claim`() {
        callerOf("alice;acme;editor;;roles=admin,app=other,dept=, =x")!!.attributes.assert().isEqualTo(
            mapOf(CoSecCallerResolver.ROLES to listOf("editor")),
        )
    }

    @Test
    fun `an attribute value is a list, an array or a comma-separated string`() {
        CoSecCallerResolver.valuesOf(listOf(" a ", null, "a", "")).assert().isEqualTo(listOf("a"))
        CoSecCallerResolver.valuesOf(arrayOf<Any>("x", 1)).assert().isEqualTo(listOf("x", "1"))
        CoSecCallerResolver.valuesOf("1, 2,,2").assert().isEqualTo(listOf("1", "2"))
        CoSecCallerResolver.valuesOf(null).assert().isEmpty()
    }

    @Test
    fun `a request reads the security context CoSec's filter put on the exchange`() = runTest {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/shop/cart/c-1/add-item"))
        val request = ServerRequest.create(exchange, ServerCodecConfigurer.create().readers)
        resolver.resolve(request).assert().isNull()
        // Where CoSec's filter puts it.
        CoSecTestBindings.bind(exchange, cosec.security("alice;acme"))
        resolver.resolve(request)!!.subject.assert().isEqualTo("alice")
    }

    @Test
    fun `options are not blank`() {
        assertThrows<IllegalArgumentException> { CoSecCallerOptions(defaultTenant = " ") }
    }
}
