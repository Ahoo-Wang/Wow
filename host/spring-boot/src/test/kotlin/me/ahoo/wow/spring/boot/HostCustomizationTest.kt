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

import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.cosid.snowflake.MillisecondSnowflakeId
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandRewriter
import me.ahoo.wow.runtime.admission.IdGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The application's own beans and settings take the place of the host's defaults. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostCustomizationTest {
    private val host = startHost(
        "wow.tenant.default" to "acme",
        "wow.wait.default-timeout" to "7s",
        "wow.node.id" to "node-a",
        "wow.service" to "custom-bank",
        "wow.http.manifest.enabled" to false,
        sources = listOf(HostTestApp::class.java, Customizations::class.java),
    )

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `wow-service and wow-node-id should name the node`() {
        host.node.service.assert().isEqualTo("custom-bank")
        host.node.node.assert().isEqualTo("node-a")
        host.node.manifest.service!!.name.assert().isEqualTo("custom-bank")
    }

    @Test
    fun `a request without a caller resolver should act in the default tenant`() {
        val answer = host.post("/bank/account/open-account", """{"owner":"dave"}""")
        answer.status.assert().isEqualTo(200)
        answer.body!!["ref"]["tenantId"].asString().assert().isEqualTo("acme")
    }

    @Test
    fun `a command naming no timeout should wait the default timeout`() {
        val customizations = host.context.getBean(Customizations::class.java)
        host.post("/bank/account/open-account", """{"owner":"erin"}""").status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(7.seconds)
        host.post("/bank/account/open-account", """{"owner":"erin"}""", headers = mapOf("Wow-Wait-Timeout" to "3s"))
            .status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(3.seconds)
        host.post("/bank/account/open-account", """{"owner":"erin"}""", headers = mapOf("Wow-Wait" to "committed"))
            .status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(7.seconds)
        host.node.gateway.defaultWaitTimeout(BANK).assert().isEqualTo(7.seconds)
        host.node.options.defaultWaitTimeout.assert().isEqualTo(7.seconds)
    }

    @Test
    fun `the application's authorizer should decide`() {
        host.post("/bank/account/forbidden/open-account", """{"owner":"mallory"}""").status.assert().isEqualTo(403)
    }

    @Test
    fun `CosId's shared generator should name new aggregates`() {
        host.context.getBean(IdGenerator::class.java).assert().isInstanceOf(CosIdGenerator::class.java)
        val answer = host.post("/bank/account/open-account", """{"owner":"frank"}""")
        val id = answer.body!!["ref"]["id"].asString()
        id.contains('-').assert().isFalse()
    }

    @Test
    fun `wow-http-manifest-enabled false should not serve the Manifest`() {
        host.get("/wow/manifest").status.assert().isEqualTo(404)
    }

    @Test
    fun `the default caller resolver should be the anonymous caller of the default tenant`() {
        host.context.getBean(CallerResolver::class.java).assert().isNotSameAs(CallerResolver.ANONYMOUS)
    }

    @Configuration(proxyBeanMethods = false)
    class Customizations {
        val timeouts: MutableList<Duration> = CopyOnWriteArrayList()

        @Bean
        fun recordingRewriter(): CommandRewriter = CommandRewriter { command: Command<*> ->
            timeouts += command.wait.timeout
            command
        }

        @Bean
        fun authorizer(): CommandAuthorizer = CommandAuthorizer { _, action: CommandAction ->
            if (action.ref.id == "forbidden") {
                Authorization.Denied("No one opens this account.")
            } else {
                Authorization.Allowed
            }
        }

        @Bean
        fun idGeneratorProvider(): IdGeneratorProvider = DefaultIdGeneratorProvider().apply {
            share = MillisecondSnowflakeId(1)
        }
    }
}

/** The host refuses a configuration it cannot serve, before anything starts. */
class HostRefusalTest {
    private fun refusal(vararg properties: Pair<String, Any>): String {
        val failure = runCatching { startHost(*properties).close() }.exceptionOrNull()
        checkNotNull(failure) { "The host started." }
        return generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `a fixed tenant of an aggregate type the node does not serve should be refused`() {
        refusal("wow.aggregates.[bank.ledger].tenant" to "t0").assert().contains("bank.ledger")
    }

    @Test
    fun `partitioned routing should be refused on the memory backend`() {
        refusal("wow.cluster.router" to "partitioned").assert().contains("memory backend")
    }

    @Test
    fun `a backend without its bean should be refused`() {
        refusal("wow.backend" to "postgres").assert().contains("DataSource bean")
        refusal("wow.backend" to "mongo").assert().contains("MongoClient bean")
    }

    @Test
    fun `a service without a name should be refused`() {
        refusal("spring.application.name" to "").assert().contains("Name the service")
    }
}
