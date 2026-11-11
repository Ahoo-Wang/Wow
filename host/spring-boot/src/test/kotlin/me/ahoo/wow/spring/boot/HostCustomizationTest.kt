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
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spring.boot.store.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
        "wow.http.openapi.enabled" to false,
        "wow.aggregates.[bank.account].id-generator" to "accountIds",
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
        val customizations = host.decided
        host.post("/bank/account/open-account", """{"owner":"erin"}""").status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(7.seconds)
        host.post("/bank/account/open-account", """{"owner":"erin"}""", headers = mapOf("Wow-Wait-Timeout" to "3s"))
            .status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(3.seconds)
        host.post("/bank/account/open-account", """{"owner":"erin"}""", headers = mapOf("Wow-Wait" to "committed"))
            .status.assert().isEqualTo(200)
        customizations.timeouts.last().assert().isEqualTo(7.seconds)
        host.node.gateway.defaultWaitTimeout(BANK).assert().isEqualTo(7.seconds)
        host.node.options.wait.defaultTimeout.assert().isEqualTo(7.seconds)
    }

    @Test
    fun `the application's transformers should transform, and the result should name the input`() {
        val answer = host.post("/bank/account/open-account", "{}")
        answer.status.assert().isEqualTo(200)
        answer.body!!["transformedFrom"]["command"].asString().assert().isEqualTo("bank.account.OpenAccount")
        host.decided.headers.last()[CommandTransformer.TRANSFORMED_FROM].assert().isEqualTo("bank.account.OpenAccount")
    }

    @Test
    fun `the application's authorizer should decide`() {
        host.post("/bank/account/forbidden/open-account", """{"owner":"mallory"}""").status.assert().isEqualTo(403)
    }

    @Test
    fun `the application's global generator and the aggregate's configured one should give the ids`() {
        host.node.gateway.ids.global.assert().isSameAs(host.context.getBean(GlobalIdGenerator::class.java))
        val answer = host.post("/bank/account/open-account", """{"owner":"frank"}""")
        answer.status.assert().isEqualTo(200)
        answer.body!!["ref"]["id"].asString().assert().startsWith("acct-")
        answer.body!!["commandId"].asString().assert().startsWith("g-")
        // No route hands the global ids out (R5.C-15): they are the node's own.
        host.get("/wow/id/global").status.assert().isEqualTo(404)
    }

    @Test
    fun `wow-http-manifest-enabled false should not serve the Manifest`() {
        host.get("/wow/manifest").status.assert().isEqualTo(404)
    }

    @Test
    fun `wow-http-openapi-enabled false should not serve the OpenAPI document`() {
        host.get("/wow/openapi").status.assert().isEqualTo(404)
    }

    @Test
    fun `the application's command authorizer should be the node's`() {
        // With one, the public port leaves the built-in aggregates' system commands to it (UI-50).
        host.node.gateway.authorizes.assert().isTrue()
    }

    @Test
    fun `the default caller resolver should be the anonymous caller of the default tenant`() {
        host.context.getBean(CallerResolver::class.java).assert().isNotSameAs(CallerResolver.ANONYMOUS)
    }

    @Configuration(proxyBeanMethods = false)
    class Customizations {
        /** Fills in the owner of an account opened without one: a transformer that changes only the body. */
        @Bean
        fun defaultOwner(): CommandTransformer = OwnerDefault()

        @Bean
        fun authorizer(): CommandAuthorizer = CommandAuthorizer { _, action: CommandAction ->
            if (action.ref.id == "forbidden") {
                Authorization.Denied("No one opens this account.")
            } else {
                Authorization.Allowed
            }
        }

        @Bean
        fun globalIds(): GlobalIdGenerator {
            val sequence = AtomicInteger()
            return GlobalIdGenerator { "g-${sequence.incrementAndGet()}" }
        }

        /** The generator `wow.aggregates.[bank.account].id-generator` names. */
        @Bean
        fun accountIds(): AggregateIdGenerator {
            val sequence = AtomicInteger()
            return AggregateIdGenerator { "acct-${sequence.incrementAndGet()}" }
        }
    }
}

/**
 * A transformer that takes the service's command client, as a 9.x rewriter that sent commands did: the client sends
 * through the node's gateway, which the node is assembled with the transformer, so the host builds the client on its
 * first use, not as a dependency of the node.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostSendingTransformerTest {
    private val host = startHost(sources = listOf(HostTestApp::class.java, SendingTransformer::class.java))

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `a transformer that takes the command client should start with the host and send through it`() {
        val id = host.post("/bank/account/open-account", """{"owner":"alice"}""").body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":5}""").status.assert().isEqualTo(200)
        val audits = host.context.getBean(AuditingDeposit::class.java).opened
        audits.map { it.status }.assert().containsExactly(CommandResult.Status.SUCCEEDED)
        audits.single().ref.id.assert().isNotEqualTo(id)
    }

    @Configuration(proxyBeanMethods = false)
    class SendingTransformer {
        @Bean
        fun auditingDeposit(commands: CommandClient): AuditingDeposit = AuditingDeposit(commands)
    }
}

/** Opens an audit account through [commands] before each deposit goes on unchanged. */
class AuditingDeposit(private val commands: CommandClient) : CommandTransformer {
    override val input: CommandType = CommandType("bank.account.Deposit", Deposit::class.java)

    /** What opening each audit account answered. */
    val opened: MutableList<CommandResult> = CopyOnWriteArrayList()

    override suspend fun transform(input: CommandInput): CommandTarget {
        opened += commands.wait(OpenAccount("audit"))
        return CommandTarget(input.type, input.aggregateId, input.body, input.headers)
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
        refusal("wow.routing" to "partitioned").assert().contains("memory backend")
    }

    @Test
    fun `auto without a durable backend should be refused outside a development node`() {
        // Review R3.5-1: the node started in memory with a warning, and lost its commands on a restart.
        refusal("wow.internal.host" to "0.0.0.0").assert()
            .contains("No durable backend").contains("name the memory backend")
        startHost(
            "wow.internal.host" to "0.0.0.0",
            "wow.backend" to "memory",
            // A node outside development needs a caller resolver (decision D301).
            sources = listOf(HostTestApp::class.java, AnonymousCallers::class.java),
        ).use { it.context.getBean(HostBackends::class.java).type.assert().isEqualTo("memory") }
        SpringApplicationBuilder(HostTestApp::class.java)
            .properties(hostProperties + mapOf("wow.internal.host" to "0.0.0.0"))
            .profiles("dev")
            .run()
            .use { it.getBean(HostBackends::class.java).type.assert().isEqualTo("memory") }
    }

    @Test
    fun `a backend without its bean should be refused`() {
        refusal("wow.backend" to "postgres").assert().contains("DataSource bean")
        refusal("wow.backend" to "mongo").assert().contains("MongoClient bean")
    }

    @Test
    fun `two transformers of one command type should be refused`() {
        val failure = runCatching {
            startHost(sources = listOf(HostTestApp::class.java, TwoTransformers::class.java)).close()
        }.exceptionOrNull()
        checkNotNull(failure) { "The host started." }
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            .assert().contains("More than one transformer takes bank.account.OpenAccount")
    }

    @Configuration(proxyBeanMethods = false)
    class TwoTransformers {
        @Bean
        fun first(): CommandTransformer = OwnerDefault()

        @Bean
        fun second(): CommandTransformer = OwnerDefault()
    }

    @Test
    fun `an id generator nothing gives, or of a type the node does not serve, should be refused`() {
        refusal("wow.aggregates.[bank.account].id-generator" to "missing").assert()
            .contains("wow.aggregates.[bank.account].id-generator names missing")
        refusal("wow.aggregates.[bank.ledger].id-generator" to "missing").assert()
            .contains("wow.aggregates.[bank.ledger].id-generator names no aggregate type")
    }

    @Test
    fun `a service without a name should be refused`() {
        refusal("spring.application.name" to "").assert().contains("The service has no name")
    }
}

/** Opens an account of the owner `anonymous` when its request names none. */
class OwnerDefault : CommandTransformer {
    override val input: CommandType = CommandType("bank.account.OpenAccount", OpenAccount::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as OpenAccount
        val owner = body.owner.ifEmpty { "anonymous" }
        return CommandTarget(input.type, input.aggregateId, body.copy(owner = owner), input.headers)
    }
}
