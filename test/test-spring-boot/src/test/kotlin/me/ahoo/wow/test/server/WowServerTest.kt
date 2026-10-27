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

package me.ahoo.wow.test.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.core.env.Environment
import java.net.InetSocketAddress
import java.net.Socket

/** An application with no domain module: the host alone. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class EmptyApp

class WowServerTest {
    @Test
    fun `a test server should listen on the loopback interface at ports the system chose, until it is closed`() {
        val server = wowServer<EmptyApp>("dev", properties = NAMED)
        server.use {
            it.publicPort.assert().isPositive()
            it.internalPort.assert().isPositive().isNotEqualTo(it.publicPort)
            accepts(it.publicPort).assert().isTrue()
            accepts(it.internalPort).assert().isTrue()
            val environment = it.bean<Environment>()
            environment.getProperty("wow.cluster.drain-timeout").assert().isEqualTo("1s")
            environment.activeProfiles.assert().containsExactly("dev")
        }
        accepts(server.publicPort).assert().isFalse()
        accepts(server.internalPort).assert().isFalse()
    }

    @Test
    fun `the clients should answer with the status, the headers and the JSON body`() {
        val properties = NAMED + mapOf("wow.wait.max-timeout" to "5s", "wow.subscription.checkpoint-interval" to null)
        wowServer<EmptyApp>("dev", properties = properties)
            .use { server ->
                val unknown = server.http.post("/shop/order/create", """{"total":1}""", mapOf("X-Test" to "1"))
                unknown.status.assert().isEqualTo(404)
                unknown.header("content-type").assert().contains("json")
                unknown.body!!.isObject.assert().isTrue()
                unknown.toString().assert().contains("404")
                server.http.get("/shop/order/1/state").status.assert().isEqualTo(404)
                server.http.request("DELETE", "/shop/order/1").status.assert().isGreaterThanOrEqualTo(400)
                server.internal.get("/").status.assert().isGreaterThanOrEqualTo(400)
                server.internal.assert().isSameAs(server.internal)
                // A property the test gives overrides the defaults; a null one leaves the key out.
                val environment = server.bean<Environment>()
                environment.getProperty("wow.wait.max-timeout").assert().isEqualTo("5s")
                environment.getProperty("wow.subscription.checkpoint-interval").assert().isNull()
            }
    }

    @Test
    fun `a test server should start by its application's class, with no profile`() {
        wowServer(EmptyApp::class.java, properties = NAMED).use { server ->
            server.bean<Environment>().activeProfiles.assert().isEmpty()
            server.http.get("/shop/order/1/state").status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `an answer that is not JSON should have no body`() {
        Answer(200, emptyMap(), "not json").body.assert().isNull()
        Answer(204, emptyMap(), "").body.assert().isNull()
        Answer(200, mapOf("Content-Type" to listOf("text/plain")), "").header("content-type").assert()
            .isEqualTo("text/plain")
        Answer(200, emptyMap(), "").header("missing").assert().isNull()
    }

    private companion object {
        val NAMED = mapOf("spring.application.name" to "shop-service")
    }

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
