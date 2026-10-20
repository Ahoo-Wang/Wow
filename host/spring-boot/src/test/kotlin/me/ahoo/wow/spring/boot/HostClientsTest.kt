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

import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.client.ClientMode
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.client.Port
import me.ahoo.wow.client.ServiceCredential
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.coroutines.CoroutineContext

/**
 * The typed clients of the peers in the host (decisions D257, D259): `wow.clients.*` and the routing beans, against a
 * [bank] host as the peer whose contract the client knows, called over HTTP in either mode.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostClientsTest {
    private val bank = startHost("wow.http.services.ledger-service.tokens[0]" to TOKEN)

    @AfterAll
    fun close() {
        bank.close()
    }

    /** The bank's contract as its api jar would carry it: its schemas named by the classes' binary names. */
    private val contracts: ContractDirectory = run {
        val defs = "#/" + '$' + "defs/"
        val text = javaClass.getResource("/META-INF/wow/manifest/host-test-bank.json")!!.readText()
            .replace(Regex("\"" + Regex.escape(defs) + "(\\w+)\"")) {
                "\"" + defs + "me.ahoo.wow.spring.boot." + it.groupValues[1] + "\""
            }
        val manifest = ManifestTree.read(WowJson.mapper.readValue(text, Map::class.java))
        ContractDirectory(Manifest(), listOf(ManifestFragment("bank-api", manifest)))
    }

    private val endpoints = EndpointResolver.of(
        mapOf(BANK to URI.create("http://127.0.0.1:${bank.publicPort}")),
        mapOf(BANK to URI.create("http://127.0.0.1:${bank.internalPort}")),
    )

    private fun client(mode: ClientMode) = lazy {
        WowClient.builder().endpoints(endpoints).contracts(contracts).apply {
            when (mode) {
                is ClientMode.Service -> service(mode.credential)
                is ClientMode.User -> user(mode.credential)
            }
        }.build()
    }

    private fun peers(mode: WowProperties.Clients.Mode = WowProperties.Clients.Mode.SERVICE) = PeerClients(
        mapOf(BANK to mode),
        contracts,
        client(ClientMode.Service(ServiceCredential.bearer(TOKEN))),
        client(ClientMode.User(UserCredential.FORWARDED)),
        mode,
    )

    private val dave = Caller.of(scope = Scope("dave"), subject = "dave")
    private val account = AggregateType(BANK, "account")

    @Test
    fun `a command to a listed peer should go over HTTP, in either mode, and be readable there`(): Unit = runBlocking {
        val local = mockk<CommandClient>()
        listOf(WowProperties.Clients.Mode.SERVICE, WowProperties.Clients.Mode.USER).forEach { mode ->
            val commands: CommandClient = RoutedCommandClient(local, peers(mode)).of(dave)
            val opened = commands.wait(
                OpenAccount("dave"),
                account.unassigned(Caller.DEFAULT_TENANT),
                SendOptions.DEFAULT
            )
            opened.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val queries = RoutedQueryApi(mockk(relaxed = true), peers(mode), emptySet()).of(dave)
            queries.snapshots<Account>().state(opened.ref.id)!!.state.owner.assert().isEqualTo("dave")
            queries.snapshots(account).state(opened.ref.id).assert().isNotNull()
            commands.stream(Deposit(5), opened.ref, SendOptions.DEFAULT).toList().last().status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            commands.send(
                Deposit(5),
                opened.ref,
                SendOptions.DEFAULT
            ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
    }

    @Test
    fun `a context not listed should stay with the node, and a listed one must be a peer's`(): Unit = runBlocking {
        val local = mockk<CommandClient>(relaxed = true)
        val routed = RoutedCommandClient(local, peers())
        routed.of(dave).send(OpenAccount("x"), AggregateType("ledger", "entry").unassigned("t"), SendOptions.DEFAULT)
        io.mockk.coVerify { local.of(dave) }
        peers().clientOf("ledger").assert().isNotNull()
        val localQueries = mockk<QueryApi>(relaxed = true)
        val pinned = mockk<SubscriptionQueries>(relaxed = true)
        val queries = RoutedQueryApi(localQueries, peers(), setOf(Account::class.java)) { pinned }
        val ledger = AggregateType("ledger", "entry")
        // The node's own state class, a context not listed and trusted queries stay in process.
        queries.of(dave).snapshots<Account>().assert().isSameAs(localQueries.of(dave).snapshots(Account::class.java))
        queries.of(dave).events(ledger).assert().isSameAs(localQueries.of(dave).events(ledger))
        queries.of(dave).snapshots(ledger).assert().isSameAs(localQueries.of(dave).snapshots(ledger))
        queries.of(
            dave
        ).datasets(ledger, Map::class.java).assert().isSameAs(localQueries.of(dave).datasets(ledger, Map::class.java))
        queries.trusted().assert().isSameAs(localQueries.trusted())
        // The listed peer's models go over HTTP, a subscription's as a caller of its commit's tenant.
        queries.of(dave).events(account).javaClass.name.assert().contains("HttpQueryApi")
        queries.of(
            dave
        ).datasets(AggregateType(BANK, "sales"), Map::class.java).javaClass.name.assert().contains("HttpQueryApi")
        val tenant = queries.tenant("t1")
        tenant.tenantId.assert().isEqualTo("t1")
        tenant.trusted().assert().isSameAs(pinned.trusted())
        tenant.snapshots(account).javaClass.name.assert().contains("HttpQueryApi")
        // Without a peer listed, a state class is the node's.
        RoutedQueryApi(
            localQueries,
            PeerClients(
                emptyMap(),
                contracts,
                lazy { error("") },
                lazy { error("") },
                WowProperties.Clients.Mode.SERVICE
            ),
            emptySet()
        )
            .of(dave).snapshots<Account>().assert().isSameAs(localQueries.of(dave).snapshots(Account::class.java))
        assertThrows<IllegalStateException> { RoutedQueryApi(localQueries, peers(), emptySet()).tenant("t1") }
        assertThrows<IllegalArgumentException> {
            PeerClients(
                mapOf("ledger" to WowProperties.Clients.Mode.SERVICE),
                contracts,
                lazy {
                    error("")
                },
                lazy { error("") },
                WowProperties.Clients.Mode.SERVICE
            )
        }.message.assert().contains("[ledger]")
    }

    @Test
    fun `the endpoints should merge the clients' with the sagas' calls`(): Unit = runBlocking {
        val properties = WowProperties().apply {
            calls.contexts["mail"] = WowProperties.Calls.CallTarget().apply { url = URI.create("http://mail:9090") }
            clients.contexts["mail"] = WowProperties.Clients.ClientTarget().apply { url = URI.create("http://mail:8080") }
            clients.contexts["box"] = WowProperties.Clients.ClientTarget().apply {
                internalUrl = URI.create("http://box:9090")
                mode = WowProperties.Clients.Mode.USER
            }
        }
        val resolver = WowAutoConfiguration().wowEndpointResolver(properties)
        resolver.resolve("mail", Port.PUBLIC).assert().isEqualTo(URI.create("http://mail:8080"))
        resolver.resolve("mail", Port.INTERNAL).assert().isEqualTo(URI.create("http://mail:9090"))
        resolver.resolve("box", Port.INTERNAL).assert().isEqualTo(URI.create("http://box:9090"))
        val peers = WowAutoConfiguration().wowPeerClients(
            WowProperties().apply { clients.contexts[BANK] = WowProperties.Clients.ClientTarget() },
            endpoints,
            ServiceCredential.NONE,
            contracts,
            GlobalIdGenerator.UUID_V7,
        )
        peers.contexts.assert().containsExactly(BANK)
        WowAutoConfiguration().wowClient(peers).assert().isSameAs(peers.client)
    }

    @Test
    fun `the public requests' credential headers should be in the coroutine context of their handling`() {
        val filter = WowAutoConfiguration().wowForwardedHeadersFilter(
            WowProperties().apply { clients.forwardedHeaders = mutableListOf("Authorization", " ") },
        )
        fun contextOf(request: MockServerHttpRequest): CoroutineContext? {
            val exchange = MockServerWebExchange.from(request)
            var seen: CoroutineContext? = null
            filter.filter(
                exchange,
                WebFilterChain {
                    seen = it.attributes[CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE] as CoroutineContext?
                    Mono.empty()
                }
            ).block()
            return seen
        }
        contextOf(MockServerHttpRequest.get("/").header("Authorization", "Bearer user").build())!![ForwardedHeaders]!!
            .headers.assert().isEqualTo(mapOf("Authorization" to "Bearer user"))
        contextOf(MockServerHttpRequest.get("/").build())?.get(ForwardedHeaders).assert().isNull()
    }

    private companion object {
        const val TOKEN = "ledger-service-token-0123456789abcdef"
    }
}
