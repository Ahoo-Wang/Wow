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
import org.junit.jupiter.api.Test

/**
 * The default request facts of the public port (09 §17 G-10) as a host serves them: `remote_ip` is the server's remote
 * address, which a client's `X-Forwarded-For` does not set, unless the host trusts a proxy with Spring Boot's
 * `server.forward-headers-strategy`.
 */
class HostRequestFactsTest {
    private val facts = mapOf("User-Agent" to "bank-app/1.0", "X-Forwarded-For" to "203.0.113.9")

    private fun Host.opened(): Map<String, String> {
        post("/bank/account/open-account", """{"owner":"gina"}""", facts).status.assert().isEqualTo(200)
        return decided.headers.last()
    }

    @Test
    fun `a command records the user agent and the connection's address, never a forwarded one`() {
        startHost().use { host ->
            val headers = host.opened()
            headers["user_agent"].assert().isEqualTo("bank-app/1.0")
            headers["remote_ip"].assert().isEqualTo("127.0.0.1")
        }
    }

    @Test
    fun `a host that trusts its proxy records the client the proxy forwarded for`() {
        startHost("server.forward-headers-strategy" to "framework").use { host ->
            host.opened()["remote_ip"].assert().isEqualTo("203.0.113.9")
        }
    }

    @Test
    fun `a host with client facts off records neither (D267)`() {
        startHost("wow.http.client-facts" to false).use { host ->
            val headers = host.opened()
            headers.containsKey("user_agent").assert().isFalse()
            headers.containsKey("remote_ip").assert().isFalse()
        }
    }
}
