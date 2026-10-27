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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.APP
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/** The host's callers in development: what the identity headers name (decision D334). */
class DevelopmentCallerResolverTest {
    private fun resolve(vararg headers: Pair<String, String>, defaultTenant: String = Caller.DEFAULT_TENANT): Caller =
        runBlocking {
            val request = MockServerRequest.builder()
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            DevelopmentCallerResolver(defaultTenant).resolve(request)!!
        }

    @Test
    fun `a user with an owner should be an owner-level caller`() {
        resolve(USER to "alice", OWNER to "alice").assert()
            .isEqualTo(Caller.of(scope = Scope(ownerId = "alice"), subject = "alice"))
    }

    @Test
    fun `a user without an owner should be a tenant-level caller, never its own owner`() {
        resolve(USER to "erin", ROLES to " employee, ,auditor ", TENANT to "acme").assert().isEqualTo(
            Caller.of(
                tenantId = "acme",
                subject = "erin",
                attributes = mapOf(DevelopmentCallerResolver.ROLES_ATTRIBUTE to listOf("employee", "auditor")),
            ),
        )
    }

    @Test
    fun `no user should be the anonymous caller of the default tenant, whatever else the headers name`() {
        resolve().assert().isSameAs(Caller.ANONYMOUS)
        resolve(
            USER to " ",
            OWNER to "alice",
            ROLES to "employee",
            TENANT to "acme"
        ).assert().isSameAs(Caller.ANONYMOUS)
        resolve(OWNER to "alice", defaultTenant = "home").assert().isEqualTo(Caller(tenantId = "home"))
    }

    @Test
    fun `the application should be the one the request names, the anonymous caller's too`() {
        resolve(USER to "alice", APP to " console ").attributes.assert()
            .isEqualTo(mapOf(Caller.APP to listOf("console")))
        resolve(APP to "console", OWNER to "alice").assert()
            .isEqualTo(Caller.of(attributes = mapOf(Caller.APP to listOf("console"))))
        resolve(APP to " ").assert().isSameAs(Caller.ANONYMOUS)
    }

    @Test
    fun `a signed-in caller should be required, the anonymous one being no caller`() {
        val signedIn = SignedInCallerResolver(DevelopmentCallerResolver())
        runBlocking { signedIn.resolve(MockServerRequest.builder().build()) }.assert().isNull()
        runBlocking { signedIn.resolve(MockServerRequest.builder().header(USER, "alice").build()) }!!
            .subject.assert().isEqualTo("alice")
    }

    @Test
    fun `a user without a tenant should be of the default tenant`() {
        resolve(USER to "bob", defaultTenant = "home").tenantId.assert().isEqualTo("home")
    }
}
