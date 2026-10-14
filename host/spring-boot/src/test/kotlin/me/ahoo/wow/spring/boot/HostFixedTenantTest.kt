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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A host whose aggregate type has a fixed tenant (decision D116) and whose internal port the system chooses
 * (`wow.internal.port=0`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostFixedTenantTest {
    private val host = startHost("wow.aggregates.[bank.account].tenant" to "t0")

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `the node should advertise the internal port the system chose`() {
        val port = host.context.getBean(InternalServer::class.java).boundPort
        port.assert().isNotEqualTo(0)
        host.node.address.assert().isEqualTo("127.0.0.1:$port")
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        host.node.isReady.assert().isTrue()
    }

    @Test
    fun `commands and state reads should be in the fixed tenant whoever the caller is`() {
        host.node.gateway.tenants.fixedTenant(AggregateType(BANK, "account")).assert().isEqualTo("t0")
        val opened = host.post("/bank/account/open-account", """{"owner":"gina"}""")
        opened.status.assert().isEqualTo(200)
        opened.body!!["ref"]["tenantId"].asString().assert().isEqualTo("t0")
        val id = opened.body["ref"]["id"].asString()

        val state = host.get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        state.body!!["ref"]["tenantId"].asString().assert().isEqualTo("t0")
        state.body["state"]["owner"].asString().assert().isEqualTo("gina")

        // The cross-scope rules are unchanged: an anonymous caller may not name another scope.
        host.get("/bank/account/$id/state", mapOf(WowHeaders.SCOPE to "tenant=t0")).body!!["code"].asString()
            .assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }
}
