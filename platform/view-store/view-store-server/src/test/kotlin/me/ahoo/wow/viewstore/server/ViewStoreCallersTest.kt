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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.DevelopmentMode
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/** The callers the view store's headers name, and the reserved scopes `Wow-Scope` opens. */
class ViewStoreCallersTest {
    private val callers = ViewStoreCallers()

    private fun resolve(vararg headers: Pair<String, String>): Caller {
        val request = MockServerRequest.builder().apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return runBlocking { callers.resolve(request) }
    }

    @Test
    fun `a user should be an owner-level caller of its tenant, with its roles and application`() {
        val caller = resolve(
            ViewStoreCallers.USER to " alice ",
            ViewStoreCallers.TENANT to "t1",
            ViewStoreCallers.ROLES to " a, ,b ",
            ViewStoreContext.APP_ID_HEADER to "console",
        )
        caller.tenantId.assert().isEqualTo("t1")
        caller.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        caller.subject.assert().isEqualTo("alice")
        caller.crossScope.assert().isFalse()
        caller.attributes.assert().containsEntry(ViewStoreCallers.ROLES_ATTRIBUTE, listOf("a", "b"))
            .containsEntry(Caller.APP, listOf("console"))
    }

    @Test
    fun `blank headers should name nobody, in the default tenant, without roles or application`() {
        val caller = resolve(
            ViewStoreCallers.USER to " ",
            ViewStoreCallers.TENANT to " ",
            ViewStoreCallers.ROLES to " , ",
            ViewStoreContext.APP_ID_HEADER to " ",
        )
        caller.assert().isEqualTo(Caller.ANONYMOUS)
    }

    @Test
    fun `only the reserved scopes should be opened to Wow-Scope`() {
        resolve(ViewStoreCallers.USER to "bob", WowHeaders.SCOPE to """owner="(shared)"""").let { caller ->
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
            resolve(ViewStoreCallers.USER to "alice", WowHeaders.SCOPE to it).crossScope.assert().describedAs(it)
                .isFalse()
        }
    }

    @Test
    fun `the header callers should be for development alone (S-9)`() {
        val configuration = ViewStoreConfiguration()
        configuration.viewStoreCallers(
            DevelopmentMode("profile dev")
        ).assert().isInstanceOf(ViewStoreCallers::class.java)
        val refusal = assertThrows<WowConfigurationException> { configuration.viewStoreCallers(DevelopmentMode.OFF) }
        refusal.key.assert().isEqualTo(ViewStoreConfiguration.TRUST_IDENTITY_HEADERS)
    }
}
