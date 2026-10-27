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

package me.ahoo.wow.viewstore.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.APP
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/**
 * The view store's callers in development: the host's, from the `Wow-*` identity headers, with the application the
 * request names, behind the reserved scopes `Wow-Scope` opens.
 */
class ViewStoreDevelopmentCallersTest {
    private val callers = ReservedScopeCallers(DevelopmentCallerResolver())

    private fun resolve(vararg headers: Pair<String, String>): Caller {
        val request = MockServerRequest.builder().apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return runBlocking { callers.resolve(request)!! }
    }

    @Test
    fun `a user should be an owner-level caller of its tenant, with its roles and application`() {
        val caller = resolve(
            USER to " alice ",
            OWNER to "alice",
            TENANT to "t1",
            ROLES to " a, ,b ",
            APP to "console",
        )
        caller.tenantId.assert().isEqualTo("t1")
        caller.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        caller.subject.assert().isEqualTo("alice")
        caller.crossScope.assert().isFalse()
        caller.attributes.assert().containsEntry(ViewStoreRoles.ROLES_ATTRIBUTE, listOf("a", "b"))
            .containsEntry(Caller.APP, listOf("console"))
    }

    @Test
    fun `blank headers should name nobody, in the default tenant, without roles or application`() {
        resolve(USER to " ", TENANT to " ", ROLES to " , ", APP to " ")
            .assert().isEqualTo(Caller.ANONYMOUS)
        resolve(APP to "console").attributes.assert()
            .isEqualTo(mapOf(Caller.APP to listOf("console")))
    }

    @Test
    fun `only the reserved scopes should be opened to Wow-Scope`() {
        resolve(USER to "bob", OWNER to "bob", WowHeaders.SCOPE to """owner="(shared)"""").let { caller ->
            caller.scope.assert().isEqualTo(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))
            caller.crossScope.assert().isTrue()
            caller.subject.assert().isEqualTo("bob")
        }
        resolve(WowHeaders.SCOPE to """tenant="(platform)", owner="(system)"""").let { caller ->
            caller.scope.assert().isEqualTo(Scope(ownerId = ViewStoreContext.SYSTEM_OWNER_ID))
            caller.crossScope.assert().isTrue()
        }
        listOf(
            """owner="bob"""",
            """owner="(system)"""",
            """tenant="(platform)", owner="(shared)"""",
            "owner="
        ).forEach {
            resolve(USER to "alice", OWNER to "alice", WowHeaders.SCOPE to it).crossScope.assert().describedAs(it)
                .isFalse()
        }
    }
}
