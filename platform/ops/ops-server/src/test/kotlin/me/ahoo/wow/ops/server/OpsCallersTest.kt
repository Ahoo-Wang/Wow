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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.DevelopmentMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class OpsCallersTest {
    private fun resolve(vararg headers: Pair<String, String>): Caller? {
        val builder = MockServerRequest.builder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        return runBlocking { OpsCallers().resolve(request) }
    }

    @Test
    fun `an operator should be a tenant-level caller with its roles`() {
        val caller = resolve(OpsCallers.USER to " alice ", OpsCallers.ROLES to "viewer, ,${OpsCallers.OPERATOR}")!!
        caller.subject.assert().isEqualTo("alice")
        caller.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
        caller.scope.assert().isEqualTo(Scope.NONE)
        caller.attributes.assert().isEqualTo(mapOf(OpsCallers.ROLES_ATTRIBUTE to listOf("viewer", OpsCallers.OPERATOR)))
    }

    @Test
    fun `a caller should be in the tenant it names, without roles when it has none`() {
        val caller = resolve(OpsCallers.USER to "olga", OpsCallers.TENANT to "t-1", OpsCallers.ROLES to " ")!!
        caller.tenantId.assert().isEqualTo("t-1")
        caller.attributes.assert().isEmpty()
        resolve(
            OpsCallers.USER to "olga",
            OpsCallers.TENANT to " "
        )!!.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
    }

    @Test
    fun `a request without a user should have no caller`() {
        resolve().assert().isNull()
        resolve(OpsCallers.USER to "  ").assert().isNull()
    }

    @Test
    fun `the header callers should be for development alone (S-9)`() {
        val configuration = OpsConfiguration()
        configuration.opsCallers(DevelopmentMode("profile dev")).assert().isInstanceOf(OpsCallers::class.java)
        val refusal = assertThrows<WowConfigurationException> { configuration.opsCallers(DevelopmentMode.OFF) }
        refusal.key.assert().isEqualTo(OpsConfiguration.TRUST_IDENTITY_HEADERS)
    }
}
