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
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.config.DevelopmentMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.client.ReactorResourceFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** An application with no domain module: the host alone. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class EmptyApp

/** An application anonymous on purpose: the resolver a test server outside development needs (decision D301). */
@Configuration(proxyBeanMethods = false)
class AnonymousCallers {
    @Bean
    fun anonymousCallerResolver(): CallerResolver = CallerResolver.ANONYMOUS
}

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
    fun `closing a test server should leave another one serving`() {
        wowServer<EmptyApp>("dev", properties = NAMED).use { first ->
            val second = wowServer<EmptyApp>("dev", properties = NAMED)
            // Each server's public port runs on loops of its own, none on reactor-netty's global ones.
            for (server in listOf(first, second)) {
                server.bean<ReactorResourceFactory>().isUseGlobalResources.assert().isFalse()
            }
            second.close()
            first.http.get("/shop/order/1/state").status.assert().isEqualTo(404)
            first.internal.get("/").status.assert().isGreaterThanOrEqualTo(400)
        }
    }

    @Test
    fun `a test's clock should be the node's`() {
        val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        wowServer<EmptyApp>("dev", properties = NAMED, clock = clock).use { server ->
            server.bean<Clock>().assert().isSameAs(clock)
            server.bean<ServiceNode>().clock.assert().isSameAs(clock)
        }
        wowServer<EmptyApp>("dev", properties = NAMED).use { server ->
            server.bean<ServiceNode>().clock.assert().isEqualTo(Clock.systemUTC())
        }
    }

    @Test
    fun `the clients should answer with the status, the headers and the JSON body`() {
        val properties = NAMED + mapOf("wow.wait.max-timeout" to "12s", "wow.subscription.checkpoint-interval" to null)
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
                environment.getProperty("wow.wait.max-timeout").assert().isEqualTo("12s")
                environment.getProperty("wow.subscription.checkpoint-interval").assert().isNull()
            }
    }

    @Test
    fun `a test server should start by its application's class, in the profiles it is given`() {
        wowServer(EmptyApp::class.java, listOf("dev"), NAMED).use { server ->
            server.bean<Environment>().activeProfiles.assert().containsExactly("dev")
            server.http.get("/shop/order/1/state").status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `a test server started with no development profile should not be a development node`() {
        // A deployment of the bank's own configuration is no development node (#6972): its loopback ports state
        // nothing, so it refuses what a deployment refuses, here the public port without a CallerResolver.
        val failure = assertThrows<Exception> { wowServer<BankServer>().close() }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()
            .assert().describedAs("a deployment's refusal: $failure").isNotNull()
    }

    @Test
    fun `a test server given what a deployment needs should start as one`() {
        wowServer<BankServer>(
            properties = mapOf("wow.backend" to "memory"),
            sources = listOf(AnonymousCallers::class.java),
        ).use { server -> server.bean<DevelopmentMode>().active.assert().isFalse() }
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
        /**
         * A name of the test's own, serving the teller alone ([TellerModels]), which no build names: the quickstart's
         * bank, also on the class path, is left out, as its build names its service bank-service.
         */
        val NAMED = mapOf("spring.application.name" to "shop-service", "wow.contexts.include" to TELLER)
    }

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
