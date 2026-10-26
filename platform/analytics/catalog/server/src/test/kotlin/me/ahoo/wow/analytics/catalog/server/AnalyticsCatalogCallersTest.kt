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

package me.ahoo.wow.analytics.catalog.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class AnalyticsCatalogCallersTest {
    private fun resolve(vararg headers: Pair<String, String>): Caller? {
        val builder = MockServerRequest.builder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return runBlocking { AnalyticsCatalogCallers().resolve(builder.build()) }
    }

    @Test
    fun `a caller should be of the platform's tenant with its roles`() {
        val caller = resolve(
            AnalyticsCatalogCallers.USER to " eve ",
            AnalyticsCatalogCallers.ROLES to "x, ,${AnalyticsCatalogCallers.EDITOR}",
        )!!
        caller.subject.assert().isEqualTo("eve")
        caller.tenantId.assert().isEqualTo(AnalyticsCatalogContext.TENANT_ID)
        caller.scope.assert().isEqualTo(Scope.NONE)
        AnalyticsCatalogCallers.rolesOf(caller).assert().containsExactly("x", AnalyticsCatalogCallers.EDITOR)
    }

    @Test
    fun `a caller should be in the tenant it names, without roles when it has none`() {
        val caller = resolve(AnalyticsCatalogCallers.USER to "o", AnalyticsCatalogCallers.TENANT to "t-1")!!
        caller.tenantId.assert().isEqualTo("t-1")
        caller.attributes.assert().isEmpty()
        resolve(AnalyticsCatalogCallers.USER to "o", AnalyticsCatalogCallers.TENANT to " ")!!
            .tenantId.assert().isEqualTo(AnalyticsCatalogContext.TENANT_ID)
    }

    @Test
    fun `a request without a user should have no caller`() {
        resolve().assert().isNull()
        resolve(AnalyticsCatalogCallers.USER to "  ").assert().isNull()
    }

    @Test
    fun `the header callers should be for development alone (S-9)`() {
        val configuration = AnalyticsCatalogConfiguration()
        configuration.analyticsCatalogCallers(
            DevelopmentMode("profile dev")
        ).assert().isInstanceOf(AnalyticsCatalogCallers::class.java)
        val refusal = assertThrows<WowConfigurationException> {
            configuration.analyticsCatalogCallers(
                DevelopmentMode.OFF
            )
        }
        refusal.key.assert().isEqualTo(AnalyticsCatalogConfiguration.TRUST_IDENTITY_HEADERS)
    }
}
