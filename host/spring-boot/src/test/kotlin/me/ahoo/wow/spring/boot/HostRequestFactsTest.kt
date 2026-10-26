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
 * The default request facts of the public port (09 §17 G-10) as a host serves them: `remote_ip` is the client the
 * gateway forwarded for, since the host makes `server.forward-headers-strategy` `framework` by default (decision
 * D303), unless the application says `none`; each fact is capped (review S-10).
 */
class HostRequestFactsTest {
    private val facts = mapOf("User-Agent" to "bank-app/1.0", "X-Forwarded-For" to "203.0.113.9")

    private fun Host.opened(headers: Map<String, String> = facts): Map<String, String> {
        post("/bank/account/open-account", """{"owner":"gina"}""", headers).status.assert().isEqualTo(200)
        return decided.headers.last()
    }

    @Test
    fun `a command records the user agent and the client the gateway forwarded for`() {
        startHost().use { host ->
            host.context.environment.getProperty("server.forward-headers-strategy").assert().isEqualTo("framework")
            val headers = host.opened()
            headers["user_agent"].assert().isEqualTo("bank-app/1.0")
            headers["remote_ip"].assert().isEqualTo("203.0.113.9")
        }
    }

    @Test
    fun `a host that says none records the connection's address`() {
        startHost("server.forward-headers-strategy" to "none").use { host ->
            host.opened()["remote_ip"].assert().isEqualTo("127.0.0.1")
        }
    }

    @Test
    fun `a fact longer than the cap is cut to it (S-10)`() {
        startHost().use { host ->
            val agent = "a".repeat(MAX_FACT_LENGTH + 100)
            host.opened(mapOf("User-Agent" to agent))["user_agent"].assert().isEqualTo(agent.take(MAX_FACT_LENGTH))
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

/** The cap of a request fact, `RequestHeaders.MAX_FACT_LENGTH` of the edge. */
private const val MAX_FACT_LENGTH = 512
