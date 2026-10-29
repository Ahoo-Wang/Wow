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

package me.ahoo.wow.http

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.dynamic
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.http.query.QueryStreams
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.contract.ContractDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * The peer reads (decision D259): the internal port's read routes, and `wow-client`'s query API reading them in
 * service mode and the public routes in user mode, over real servers.
 */
class PeerReadsTest {
    private val services = DeclaredServices.resolver(TENANT)
    private val node = QueryNode(streams = QueryStreams(chunkSize = 1, maxHold = 1.milliseconds))
    private val manifest = node.http.manifest

    private fun peerRoutes(permits: CallPermits = CallPermits(emptyMap())) = peerReadRoutes(
        manifest,
        node.http.gateway,
        services,
        node.http.httpQueries,
        node.http.reads,
        permits = permits,
    )

    private val internal = InternalServer(peerRoutes())
    private val public = InternalServer(node.http.routes)

    @AfterEach
    fun close() {
        internal.close()
        public.close()
    }

    /** The node's contract as a peer's api jar carries it, the cart's state the class [Cart]. */
    private val contracts = ContractDirectory(
        Manifest(),
        listOf(
            ManifestFragment(
                "shop",
                manifest.copyWith(
                    contexts = manifest.contexts.map { context ->
                        context.copyWith(
                            aggregates = context.aggregates.map {
                                it.copyWith(state = StateManifest(Manifest.schemaRef(Cart::class.java.name)))
                            },
                        )
                    },
                ),
            ),
        ),
    )

    private fun client(user: Boolean = false) = WowClient.builder()
        .endpoints(
            EndpointResolver.of(
                mapOf(CONTEXT to URI.create("http://${public.address}")),
                mapOf(CONTEXT to URI.create("http://${internal.address}")),
            ),
        )
        .apply { if (user) user(UserCredential.FORWARDED) else service(SERVICE) }
        .contracts(contracts.fragments)
        .build()
        .queries

    private val alice = Caller.of(tenantId = TENANT, scope = Scope("alice"), subject = "alice")
    private val all = MatchAllFilter
    private val byId = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))

    private fun saveCarts() {
        node.save("c-1", items = 1)
        node.save("c-2", items = 2)
        node.save("c-3", items = 3, owner = "bob")
        node.save("c-4", items = 4)
        node.save("c-9", items = 9, tenant = "other")
    }

    @Test
    fun `a service should read as its root context, in its tenant and scope`(): Unit = runBlocking {
        saveCarts()
        val carts = client().of(alice).snapshots<Cart>()
        carts.list(ListQuery(all)).toList().map { it.ref.id }.assert().containsExactlyInAnyOrder("c-1", "c-2", "c-4")
        carts.list(ListQuery(all)).toList().first().state.items.assert().isPositive()
        val bob = client().of(Caller.of(tenantId = TENANT, scope = Scope("bob"), subject = "bob")).snapshots<Cart>()
        bob.list(ListQuery(all)).toList().map { it.ref.id }.assert().containsExactly("c-3")
    }

    @Test
    fun `every kind of query should answer its shape`(): Unit = runBlocking {
        saveCarts()
        val carts = client().of(alice).snapshots(cartType)
        carts.single(SingleQuery(all, sort = listOf(Sort(QueryField("state.items"), Sort.Direction.DESC))))!!
            .ref.id.assert().isEqualTo("c-4")
        carts.single(SingleQuery(AggregateIdFilter("missing"))).assert().isNull()
        val page = carts.paged(PagedQuery(all))
        page.total.assert().isEqualTo(3)
        (page.list.first().state as ObjectNode)["items"].asInt().assert().isPositive()
        val first = carts.cursor(CursorQuery(all, size = 2))
        first.list.assert().hasSize(2)
        carts.cursor(CursorQuery(all, size = 2, cursor = first.nextCursor)).list.assert().hasSize(1)
        carts.count(CountQuery(all)).count.assert().isEqualTo(3)
        carts.dynamic<Map<String, Any>>().list(ListQuery(all)).toList().assert().hasSize(3)
        val groups = carts.aggregate(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("n"))),
            Map::class.java,
        ).toList()
        groups.single()["n"].toString().assert().isEqualTo("3")
    }

    @Test
    fun `a streamed list should resume after each end the peer makes resumable`(): Unit = runBlocking {
        saveCarts()
        val streamed = client().of(alice).snapshots<Cart>().stream(ListQuery(all, sort = byId)).toList()
        streamed.map { it.row.ref.id }.assert().containsExactly("c-1", "c-2", "c-4")
        streamed.all { it.position != null }.assert().isTrue()
        // From a position, on: the rows after it.
        client().of(alice).snapshots<Cart>()
            .stream(ListQuery(all, sort = byId), streamed.first().position)
            .toList().map { it.row.ref.id }.assert().containsExactly("c-2", "c-4")
    }

    @Test
    fun `a state read should answer the latest state, null when missing or out of reach`(): Unit = runBlocking {
        node.http.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        val carts = client().of(alice).snapshots<Cart>()
        val state = carts.state("c-1")!!
        state.version.assert().isEqualTo(1)
        state.state.items.assert().isEqualTo(1)
        state.scope.assert().isEqualTo(Scope("alice"))
        carts.state(cartType.ref(TENANT, "c-1"))!!.ref.id.assert().isEqualTo("c-1")
        carts.state("missing").assert().isNull()
        carts.state(cartType.ref("other", "c-1")).assert().isNull()
        client().of(Caller.of(tenantId = TENANT, scope = Scope("bob"), subject = "bob")).snapshots<Cart>()
            .state("c-1").assert().isNull()
    }

    @Test
    fun `events should be read with their bodies as trees`(): Unit = runBlocking {
        node.http.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        val streams = client().of(alice).events(cartType).list(ListQuery(all)).toList()
        val event = streams.single().events.single()
        event.name.assert().endsWith("ItemAdded")
        (event.body as ObjectNode)["sku"].asString().assert().isEqualTo("apple")
    }

    @Test
    fun `a user should read the public routes with the forwarded credential`(): Unit = runBlocking {
        saveCarts()
        val ids = withContext(ForwardedHeaders(mapOf("X-User" to "bob"))) {
            client(user = true).of(alice).snapshots<Cart>().list(ListQuery(all)).toList().map { it.ref.id }
        }
        ids.assert().containsExactly("c-3")
    }

    @Test
    fun `a read without a service identity, without Wow-Call or not permitted should be refused`() {
        saveCarts()
        val routes = org.springframework.test.web.reactive.server.WebTestClient
            .bindToRouterFunction(
                peerRoutes(CallPermits(emptyMap(), mapOf("basket-service" to listOf("shop.cart.events"))))
            )
            .build()
        val root = CallHeaders.format(
            CallContext.of("c", TENANT, Scope("alice"), me.ahoo.wow.api.decide.Causation("c", "c")),
        )
        val body = """{"kind":"list","filter":{"op":"match-all"}}"""
        fun post(path: String, headers: Map<String, String>) = routes.post().uri(path)
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .header("Content-Type", "application/json").bodyValue(body).answer()
        val service = mapOf(WowHeaders.SERVICE to SERVICE)
        post("/shop/cart/snapshot/query", service + (WowHeaders.CALL to root)).problemCode().assert()
            .isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        post("/shop/cart/events/query", service + (WowHeaders.CALL to root)).status.assert().isEqualTo(200)
        post("/shop/cart/events/query", service).problemCode().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        post("/shop/cart/events/query", mapOf("X-User" to "alice", WowHeaders.CALL to root)).problemCode().assert()
            .isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        post("/shop/cart/events/query", service + (WowHeaders.CALL to "!")).problemCode().assert()
            .isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        post("/shop/cart/events/query", service + (WowHeaders.CALL to root) + (WowHeaders.SCOPE to "owner=\"bob\""))
            .problemCode().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        routes.get().uri("/shop/cart/events/query").headers {
            it.set(WowHeaders.SERVICE, SERVICE)
            it.set(WowHeaders.CALL, root)
        }
            .answer().status.assert().isEqualTo(200)
        // A model this node does not serve is its 404, as on the public port.
        val unserved = QueryNode(snapshots = { null })
        val unservedRoutes = org.springframework.test.web.reactive.server.WebTestClient.bindToRouterFunction(
            peerReadRoutes(manifest, unserved.http.gateway, services, unserved.http.httpQueries),
        ).build()
        unservedRoutes.post().uri("/shop/cart/snapshot/query")
            .headers {
                it.set(WowHeaders.SERVICE, SERVICE)
                it.set(WowHeaders.CALL, root)
            }
            .header("Content-Type", "application/json").bodyValue(body).answer().status.assert().isEqualTo(404)
        unservedRoutes.get().uri("/shop/cart/snapshot/query")
            .headers {
                it.set(WowHeaders.SERVICE, SERVICE)
                it.set(WowHeaders.CALL, root)
            }
            .answer().status.assert().isEqualTo(404)
        routes.get().uri("/shop/cart/c-1/state").headers {
            it.set(WowHeaders.SERVICE, SERVICE)
            it.set(WowHeaders.CALL, root)
        }
            .answer().problemCode().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `the Manifest should be read by any service with Wow-Call, whatever models it may read`() {
        val routes = org.springframework.test.web.reactive.server.WebTestClient
            .bindToRouterFunction(
                peerRoutes(CallPermits(emptyMap(), mapOf("basket-service" to listOf("shop.cart.events"))))
            )
            .build()
        val root = CallHeaders.format(
            CallContext.of("c", TENANT, Scope("alice"), me.ahoo.wow.api.decide.Causation("c", "c")),
        )
        val answer = routes.get().uri("/wow/manifest").headers {
            it.set(WowHeaders.SERVICE, SERVICE)
            it.set(WowHeaders.CALL, root)
        }.answer()
        answer.status.assert().isEqualTo(200)
        answer.body!!["contexts"][0]["name"].asString().assert().isEqualTo("shop")
        routes.get().uri("/wow/manifest").header("X-User", "alice").header(WowHeaders.CALL, root).answer()
            .problemCode().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a peer read whose admission fails unexpectedly should be a 500 that says nothing of it`() {
        val failing = peerReadRoutes(
            manifest,
            node.http.gateway,
            { error("secret jdbc:postgresql://db-internal:5432") },
            node.http.httpQueries,
        )
        val answer = org.springframework.test.web.reactive.server.WebTestClient.bindToRouterFunction(failing).build()
            .get().uri("/shop/cart/c-1/state").answer()
        answer.status.assert().isEqualTo(500)
        answer.body.toString().assert().doesNotContain("jdbc")
    }

    @Test
    fun `a refused service-mode read should be a problem, and a client should have no trusted queries`() {
        val client = WowClient.builder()
            .endpoints(EndpointResolver.of(emptyMap(), mapOf(CONTEXT to URI.create("http://${internal.address}"))))
            .contracts(contracts.fragments)
            .build()
            .queries
        val problem = runCatching { runBlocking { client.of(alice).snapshots<Cart>().list(ListQuery(all)).toList() } }
            .exceptionOrNull() as WowProblemException
        problem.status.assert().isEqualTo(403)
        // Trusted code reads its own node: a client's queries are a caller's alone, with no trusted() to call.
        client.assert().isNotInstanceOf(NodeQueryApi::class.java)
    }

    private fun Answer.problemCode(): String? = body?.get("code")?.asString()

    private companion object {
        const val SERVICE = "basket-service"
    }
}
