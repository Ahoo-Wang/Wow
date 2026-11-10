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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.policy.GovernedAggregates
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.policy.ReadAuthorizer
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.HostQueryFixtures.ACCOUNT
import me.ahoo.wow.spring.boot.HostQueryFixtures.eventually
import me.ahoo.wow.spring.boot.HostQueryFixtures.openAccount
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** A beta application's row filter left as a `QueryPolicy` bean, which the node no longer applies. */
@Configuration(proxyBeanMethods = false)
class LeftoverQueryPolicy {
    @Bean
    fun hideFromMallory(): QueryPolicy =
        QueryPolicy { caller, _ -> if (caller.subject == "mallory") MatchNoneFilter else MatchAllFilter }
}

/** A read authorizer that lets only the callers with the `reader` role read, wherever they read from. */
@Configuration(proxyBeanMethods = false)
class ReadersOnly {
    @Bean
    fun readerRole(): ReadAuthorizer = ReadAuthorizer { caller, read ->
        if ("reader" in caller.attributes["roles"].orEmpty()) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of("${caller.subject} may not read ${read.type}: it needs the reader role.")
        }
    }
}

/** The access policy of the accounts: the tellers read and change them, the auditors only read them. */
@Configuration(proxyBeanMethods = false)
class TellersReadAccounts {
    @Bean
    fun accountAccess(): AccessPolicy<Account> = accessPolicy {
        read { caller -> allowIf(caller.hasRole("teller") || caller.hasRole("auditor")) }
        write { caller -> allowIf(caller.hasRole("teller")) }
    }
}

/** An access policy of a state no aggregate of the node has. */
@Configuration(proxyBeanMethods = false)
class StrayAccessPolicy {
    @Bean
    fun strayAccess(): AccessPolicy<DecidedCommands> = accessPolicy { readWrite { allowAll() } }
}

/** Who reads and changes the aggregates of a host's query plane: policies, access policies and a read authorizer. */
class HostQueryAccessTest {
    /** A teller of the development profile: a request's headers name its caller. */
    private val tellerHeaders = mapOf(DevelopmentHeaders.USER to "tom", DevelopmentHeaders.ROLES to "teller")

    @Test
    fun `a QueryPolicy bean should refuse the start, naming it and where its rule goes`() {
        val refused = assertThrows<Exception> {
            startHost(sources = listOf(HostTestApp::class.java, LeftoverQueryPolicy::class.java)).close()
        }
        val cause = generateSequence<Throwable>(refused) { it.cause }
            .filterIsInstance<WowConfigurationException>().first()
        cause.key.assert().isEqualTo(QueryPolicy::class.java.name)
        cause.reason.assert().contains("hideFromMallory", "no longer applied")
        // Reported through the host's failure analyzer, as any refused configuration.
        val analysis = checkNotNull(WowConfigurationFailureAnalyzer().analyze(refused))
        analysis.description.assert().contains("hideFromMallory")
        analysis.action.assert().contains("ReadAuthorizer", "AccessPolicy")
    }

    @Test
    fun `an access policy bean should govern its aggregate's reads, and one of no aggregate refuse the start`() {
        // The development profile: a request's headers name its caller.
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, TellersReadAccounts::class.java),
        ).use { host ->
            val id = host.openAccount("mona", tellerHeaders)
            val engine = host.context.getBean(QueryEngine::class.java)
            engine.access.governs(ACCOUNT).assert().isTrue()
            // What a Sink records in wow_catalog of each aggregate: whether its policy decides its rows.
            val governed = host.context.getBean(GovernedAggregates::class.java)
            governed.governs(ACCOUNT).assert().isTrue()
            governed.governs(AggregateType(BANK, "ledger")).assert().isFalse()
            fun accounts(caller: Caller) = runBlocking {
                engine.snapshots(ACCOUNT)!!.list(ListQuery(MatchAllFilter, limit = 10), QueryCaller(caller)).toList()
            }
            val tom = Caller.of(subject = "tom", attributes = mapOf("roles" to listOf("teller")))
            eventually { accounts(tom).size == 1 }
            // Not by its owner: the policy alone decides.
            accounts(Caller.of(subject = "mona", scope = Scope("mona"))).assert().isEmpty()
            // A state read alike: a teller who is an owner of none of them reads it, no one else.
            val teller = mapOf(
                DevelopmentHeaders.USER to "tom",
                DevelopmentHeaders.OWNER to "tom",
                DevelopmentHeaders.ROLES to "teller",
            )
            host.get("/bank/account/$id/state", teller).status.assert().isEqualTo(200)
            host.get("/bank/account/$id/state").status.assert().isEqualTo(404)
            // Its events alike, by a query that names no aggregate: each stream records the account's attributes.
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            host.post("/bank/account/events/query", all, teller).body!!.values()
                .map { it["aggregateId"].asString() }.toSet().assert().containsExactly(id)
            host.post("/bank/account/events/query", all).body!!.size().assert().isZero()
        }
        val refused = assertThrows<Exception> {
            startHost(sources = listOf(HostTestApp::class.java, StrayAccessPolicy::class.java)).close()
        }
        generateSequence<Throwable>(refused) { it.cause }.mapNotNull { it.message }
            .any { "is of no aggregate of this node" in it }.assert().isTrue()
        // A node of some of the service's contexts leaves such a policy to the node that hosts its aggregate.
        startHost(
            "wow.contexts.include" to BANK,
            sources = listOf(HostTestApp::class.java, StrayAccessPolicy::class.java),
        ).use { host -> host.context.getBean(QueryEngine::class.java).access.governed.assert().isEmpty() }
    }

    @Test
    fun `an access policy bean should decide who changes its aggregate, over HTTP and in process`() {
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, TellersReadAccounts::class.java),
        ).use { host ->
            val id = host.openAccount("mona", tellerHeaders)
            val deposit = "/bank/account/$id/deposit"
            host.post(deposit, """{"amount":5}""", tellerHeaders).status.assert().isEqualTo(200)
            // An auditor reads the account but may not change it; any other caller does not learn it exists.
            val auditor = mapOf(DevelopmentHeaders.USER to "amy", DevelopmentHeaders.ROLES to "auditor")
            host.post(deposit, """{"amount":5}""", auditor).let {
                it.status.assert().isEqualTo(403)
                it.body!!["code"].asString().assert().isEqualTo("wow.aggregate.forbidden")
            }
            host.post(deposit, """{"amount":5}""", mapOf(DevelopmentHeaders.USER to "eve")).let {
                it.status.assert().isEqualTo(404)
                it.body!!["code"].asString().assert().isEqualTo("wow.aggregate.not-found")
            }
            // Opening one is a change too: the auditor may not create an account it could not change.
            host.post("/bank/account/open-account", """{"owner":"amy"}""", auditor).status.assert().isEqualTo(403)
            val ref = ACCOUNT.ref(Caller.DEFAULT_TENANT, id)
            val commands = host.context.getBean(CommandClient::class.java)
            runBlocking {
                // In process, the default caller is anonymous and holds no claim: refused as any other.
                assertThrows<CommandRejectedException> { commands.wait(CommandRequest.to(ref, Deposit(1))) }
                    .code.assert().isEqualTo("wow.aggregate.not-found")
                // The service's own code sends as the service, which access policies trust.
                commands.of(Caller.ofService("host-test")).wait(CommandRequest.to(ref, Deposit(1))).status.assert()
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
            }
        }
    }

    @Test
    fun `an owner transfer should move the aggregate's whole history to its new owner's events queries`() {
        startHost("spring.profiles.active" to "dev").use { host ->
            val tom = Caller.of(scope = Scope("tom"), subject = "tom")
            val commands = host.context.getBean(CommandClient::class.java).of(tom)
            val ref = runBlocking {
                val opened = commands.wait(CommandRequest.of(OpenAccount("tom")))
                commands.wait(CommandRequest.to(opened.ref, Deposit(5))).status.assert()
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
                opened.ref
            }
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            fun versions(owner: String) = host.post(
                "/bank/account/events/query",
                all,
                mapOf(DevelopmentHeaders.USER to owner, DevelopmentHeaders.OWNER to owner),
            ).body!!.values().map { it["version"].asInt() }
            versions("tom").assert().containsExactly(1, 2)
            versions("ann").assert().isEmpty()
            runBlocking { commands.wait(CommandRequest.to(ref, TransferOwner.of("ann"))) }.status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            // The built-in stamping stamps the earlier streams with the owner of now.
            eventually { versions("ann") == listOf(1, 2, 3) }
            versions("tom").assert().isEmpty()
        }
    }

    @Test
    fun `without a query policy no snapshot policy gateway is built`() {
        startHost(
            "wow.query.memory.enabled" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.visibility(ACCOUNT).assert().isNull()
            val events = host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"match-all"}}""")
            events.status.assert().isEqualTo(200)
            events.body!!.values().map { it["aggregateId"].asString() }.assert().containsExactly(id)
        }
    }

    @Test
    fun `a read authorizer bean should refuse a caller every read before anything is read, over HTTP and in process`() {
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, ReadersOnly::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val reader = mapOf(DevelopmentHeaders.USER to "rita", DevelopmentHeaders.ROLES to "reader")
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            eventually { host.post("/bank/account/snapshot/query", all, reader).body!!.size() == 1 }
            host.get("/bank/account/$id/state", reader).status.assert().isEqualTo(200)
            // Refused alike whatever is asked: a query, a state that exists or not, a trace, the events.
            val stranger = mapOf(DevelopmentHeaders.USER to "sam")
            listOf(
                host.post("/bank/account/snapshot/query", all, stranger),
                host.post("/bank/account/events/query", all, stranger),
                host.get("/bank/account/$id/state", stranger),
                host.get("/bank/account/no-such-account/state", stranger),
                host.get("/bank/account/$id/state/trace", stranger),
            ).forEach { answer ->
                answer.status.assert().isEqualTo(403)
                answer.body!!["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            }
            val api = host.context.getBean(NodeQueryApi::class.java)
            runBlocking {
                runCatching { api.of(Caller.of(subject = "sam")).snapshots<Account>().state(id) }
                    .exceptionOrNull().assert().isInstanceOf(WowProblemException::class.java)
                    .hasMessageContaining("needs the reader role")
                // Trusted code reads with no caller, and is not asked.
                api.trusted().snapshots(ACCOUNT).list(ListQuery(MatchAllFilter, limit = 5)).toList().assert().hasSize(1)
            }
        }
    }
}
