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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.AbacQueryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI

/** The dataset query routes (decision D145): `/wow/dataset` and `/wow/dataset/{context}/{dataset}/query`. */
class DatasetRoutesTest {
    private val storage = InMemoryDatasetStorage()
    private val sales = AggregateType(CONTEXT, "sales")
    private val regions = AggregateType("platform", "regions")
    private val datasets = QueryDatasets(listOf(salesDataset(), regionsDataset()))
    private val node = datasetNode(datasets)

    init {
        storage.put(
            "sales",
            listOf(
                row("s-1", TENANT, "alice", "east", 10),
                row("s-2", TENANT, "alice", "west", 5),
                row("s-3", TENANT, "bob", "east", 7),
                row("s-4", "other", "alice", "east", 100),
                row("s-5", TENANT, "alice", "east", 3),
            ),
        )
        storage.put("regions", listOf(WowJson.mapper.createObjectNode().put("code", "east")))
    }

    private fun salesDataset(title: String = "Sales") = QueryDataset.of(sales) {
        tenant("tenant_id")
        owner("owner_id")
        identity("id")
        tag("region", "region")
        title(title)
        storage(InMemoryDatasetStorage.binding("sales"))
        listOf("id", "tenant_id", "owner_id", "region").forEach { name ->
            field(name) {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        field("amount") {
            types(QueryValueType.INTEGER)
            nullable(false)
        }
    }

    private fun regionsDataset() = QueryDataset.of(regions) {
        platform()
        identity("code")
        storage(InMemoryDatasetStorage.binding("regions"))
        field("code") {
            types(QueryValueType.STRING)
            nullable(false)
        }
    }

    @Test
    fun `a peer should read a dataset through the internal read routes as its root context`(): Unit = runBlocking {
        val services = DeclaredServices.resolver(TENANT)
        val routes = peerReadRoutes(node.manifest, node.gateway, services, node.httpQueries)
        InternalServer(routes).use { server ->
            val queries = WowClient.builder()
                .endpoints(EndpointResolver.of(emptyMap(), mapOf(CONTEXT to URI.create("http://${server.address}"))))
                .service(SERVICE)
                .build()
                .queries
            val alice = Caller.of(tenantId = TENANT, scope = Scope("alice"), subject = "alice")
            queries.of(
                alice
            ).datasets(DatasetName.of(CONTEXT, "sales"), Map::class.java).list(ListQuery(MatchAllFilter)).toList()
                .map { it["id"] }.assert().containsExactlyInAnyOrder("s-1", "s-2", "s-5")
            val described = WebTestClient.bindToRouterFunction(routes).build().get().uri(SALES)
                .header(WowHeaders.SERVICE, SERVICE)
                .header(
                    WowHeaders.CALL,
                    CallHeaders.format(CallContext.of("c", TENANT, Scope("alice"), Causation("c", "c"))),
                )
                .answer()
            described.status.assert().isEqualTo(200)
        }
    }

    private fun row(id: String, tenant: String, owner: String, region: String, amount: Long) =
        WowJson.mapper.createObjectNode()
            .put("id", id)
            .put("tenant_id", tenant)
            .put("owner_id", owner)
            .put("region", region)
            .put("amount", amount)

    private fun datasetNode(
        datasets: QueryDatasets,
        visibility: DatasetVisibility = DatasetVisibility.DEFAULT,
        abac: AbacQueryOptions = AbacQueryOptions.DEFAULT,
    ): HttpNode {
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetFactory = storage, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val gateways = DatasetQueryGateways.of(
            datasets,
            catalog,
            storage,
            policies = listOf(ScopeQueryPolicy(), AbacQueryPolicy(abac)),
        )
        return HttpNode(manifest = queryManifest()) {
            HttpQueries(datasets = gateways, datasetVisibility = visibility)
        }
    }

    private fun HttpNode.query(
        body: String,
        user: String? = "alice",
        path: String = SALES,
        headers: Map<String, String> = emptyMap(),
    ): Answer = post(path, body, user, headers)

    private fun ids(answer: Answer): List<String> {
        answer.status.assert().isEqualTo(200)
        val rows = if (answer.body!!.isArray) answer.body!! else answer.body!!["list"]
        return rows.values().map { it["id"].asString() }
    }

    @Test
    fun `a list should answer the caller's tenant and owner only`() {
        val all = """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"id","direction":"ASC"}]}"""
        val listed = node.query(all)
        listed.contentType.assert().isEqualTo("application/json")
        ids(listed).assert().containsExactly("s-1", "s-2", "s-5")
        listed.body!![0]["amount"].asLong().assert().isEqualTo(10)
        ids(node.query(all, user = "bob")).assert().containsExactly("s-3")
        // A tenant alone, across scopes: every owner of that tenant, never another tenant's.
        val tenant = mapOf(WowHeaders.SCOPE to "tenant=\"acme\"", "X-Admin" to "yes")
        ids(node.query(all, headers = tenant)).assert().containsExactly("s-1", "s-2", "s-3", "s-5")
        ids(
            node.query(
                """{"kind":"list","filter":{"op":"EQ","field":"tenant_id","value":"other"}}""",
                headers = tenant,
            ),
        ).assert().isEmpty()
    }

    @Test
    fun `an aggregation should group the caller's records`() {
        val answer = node.query(
            """{"kind":"aggregate","filter":{"op":"MATCH_ALL"},"groupBy":[{"type":"TERMS","field":"region","alias":"region"}],"metrics":[{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"amount"},"alias":"total"},{"type":"COUNT","alias":"sales"}]}""",
        )
        answer.status.assert().isEqualTo(200)
        val groups = answer.body!!.values().associate {
            it["region"].asString() to (it["total"].asLong() to it["sales"].asLong())
        }
        groups.assert().isEqualTo(mapOf("east" to (13L to 2L), "west" to (5L to 1L)))
    }

    @Test
    fun `paged, cursor, count and single should answer their shapes`() {
        val paged = node.query(
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"sort":[{"field":"amount","direction":"DESC"}],"pagination":{"index":1,"size":2}}""",
        )
        ids(paged).assert().containsExactly("s-1", "s-2")
        paged.body!!["total"].asLong().assert().isEqualTo(3)
        val cursor = node.query(
            """{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"id","direction":"ASC"}],"size":2}""",
        )
        ids(cursor).assert().containsExactly("s-1", "s-2")
        cursor.body!!["nextCursor"].isString.assert().isTrue()
        val count = node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
        count.body!!["count"].asLong().assert().isEqualTo(3)
        count.body!!["exact"].asBoolean().assert().isTrue()
        node.query("""{"kind":"single","filter":{"op":"ID","value":"s-2"}}""").body!!["region"].asString()
            .assert().isEqualTo("west")
        // Another owner's record is not found.
        node.query("""{"kind":"single","filter":{"op":"ID","value":"s-3"}}""")
            .assertQueryProblem(404, FrameworkErrors.QUERY_NOT_FOUND)
    }

    @Test
    fun `the descriptor should be answered with its version as ETag, and 304 when it is named`() {
        val described = node.get(SALES, "alice")
        described.status.assert().isEqualTo(200)
        described.body!!["model"].asString().assert().isEqualTo("DATASET")
        described.body!!["fields"].values().map { it["path"].asString() }.assert()
            .contains("id", "tenant_id", "owner_id", "region", "amount")
        val tag = described.headers.eTag
        tag.assert().isEqualTo("\"${described.body!!["version"].asString()}\"")
        node.get(SALES, "alice", mapOf(HttpHeaders.IF_NONE_MATCH to "W/$tag")).status.assert().isEqualTo(304)
        node.get(SALES, "alice", mapOf(HttpHeaders.ACCEPT to MediaType.TEXT_EVENT_STREAM_VALUE)).status.assert()
            .isEqualTo(406)
    }

    @Test
    fun `the list should name the datasets the caller sees`() {
        val listed = node.get("/wow/dataset", "alice")
        listed.status.assert().isEqualTo(200)
        // The platform-level dataset is the platform tenant's only.
        listed.body!!.values().map { it["name"].asString() }.assert().containsExactly("shop.sales")
        val entry = listed.body!![0]
        entry["context"].asString().assert().isEqualTo("shop")
        entry["dataset"].asString().assert().isEqualTo("sales")
        entry["title"].asString().assert().isEqualTo("Sales")
        entry["path"].asString().assert().isEqualTo(SALES)
        val platform = node.get(
            "/wow/dataset",
            "alice",
            mapOf(WowHeaders.SCOPE to "tenant=\"(0)\"", "X-Admin" to "yes"),
        )
        platform.body!!.values().map { it["name"].asString() }.assert().containsExactly(
            "platform.regions",
            "shop.sales"
        )
        node.get("/wow/dataset", "alice", mapOf(WowHeaders.SCOPE to "tenant=\"(0)\""))
            .assertQueryProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        node.get("/wow/dataset", null).status.assert().isEqualTo(401)
    }

    @Test
    fun `a dataset the node does not serve, or the caller does not see, should be a 404`() {
        val notFound = FrameworkErrors.QUERY_DATASET_NOT_FOUND
        node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", path = "/wow/dataset/shop/none/query")
            .assertQueryProblem(404, notFound)
        node.get("/wow/dataset/shop/none/query", "alice").assertQueryProblem(404, notFound)
        node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", path = REGIONS).assertQueryProblem(404, notFound)
        node.get(REGIONS, "alice").assertQueryProblem(404, notFound)
        // A path whose context or name is no aggregate type's names no dataset either.
        node.get("/wow/dataset/shop/-/query", "alice").assertQueryProblem(404, notFound)
        val platform = mapOf(WowHeaders.SCOPE to "tenant=\"(0)\"", "X-Admin" to "yes")
        node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", path = REGIONS, headers = platform)
            .body!!["count"].asLong().assert().isEqualTo(1)
    }

    @Test
    fun `a dataset that joins, changes or leaves should be served as it is then`() {
        val count = """{"kind":"count","filter":{"op":"MATCH_ALL"}}"""
        val more = AggregateType(CONTEXT, "more")
        val path = "/wow/dataset/shop/more/query"
        val tenant = mapOf(WowHeaders.SCOPE to "tenant=\"acme\"", "X-Admin" to "yes")
        node.query(count, path = path, headers = tenant).status.assert().isEqualTo(404)
        datasets.add(
            QueryDataset.of(more) {
                tenant("tenant_id")
                storage(InMemoryDatasetStorage.binding("sales"))
                field("tenant_id") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            },
        )
        node.query(count, path = path, headers = tenant).body!!["count"].asLong().assert().isEqualTo(4)
        node.get("/wow/dataset", "alice", tenant).body!!.values().map { it["name"].asString() }.assert()
            .containsExactly("shop.more", "shop.sales")
        // A caller whose principal sets an owner does not see a dataset without an owner column.
        node.get("/wow/dataset", "alice").body!!.values().map { it["name"].asString() }.assert()
            .containsExactly("shop.sales")
        node.query(count, path = path).status.assert().isEqualTo(404)
        datasets.replace(salesDataset(title = "Revenue"))
        node.get("/wow/dataset", "alice").body!![0]["title"].asString().assert().isEqualTo("Revenue")
        datasets.remove(more).assert().isTrue()
        node.query(count, path = path, headers = tenant).status.assert().isEqualTo(404)
    }

    @Test
    fun `a streamed list, a malformed body and an unauthenticated request should answer as on a snapshot model`() {
        val result = node.client.post().uri(SALES)
            .header("X-User", "alice")
            .header("Content-Type", "application/json")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("""{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"id","direction":"ASC"}]}""")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        result.responseHeaders.contentType.toString().assert().startsWith("text/event-stream")
        result.responseBody.orEmpty().split("\n\n").count { it.contains("event:row") }.assert().isEqualTo(3)
        node.query("""{"filter":{"op":"MATCH_ALL"}}""").assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
        node.query("""{"kind":"list","filter":{"op":"EQ","field":"missing","value":1}}""")
            .assertQueryProblem(400, FrameworkErrors.QUERY_INVALID)
        node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""", user = null).status.assert().isEqualTo(401)
        node.get(SALES, null).status.assert().isEqualTo(401)
    }

    @Test
    fun `required ABAC tags should hide a tagged dataset from a caller without one`() {
        val abac = AbacQueryOptions(enabled = true, requirePrincipalTags = true)
        val visibility = DatasetVisibility.of(abac)
        val strict = datasetNode(QueryDatasets(listOf(salesDataset())), visibility, abac)
        strict.get("/wow/dataset", "alice").body!!.values().assert().isEmpty()
        strict.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""").status.assert().isEqualTo(404)
        val tagged = QueryCaller(
            Caller(tenantId = TENANT, attributes = mapOf("abac.region" to listOf("east"))),
            entry = QueryEntry.HTTP,
        )
        visibility.visible(salesDataset(), tagged).assert().isTrue()
        visibility.visible(
            regionsDataset(),
            QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.HTTP)
        ).assert().isFalse()
        DatasetVisibility.of(AbacQueryOptions(enabled = true)).assert().isSameAs(DatasetVisibility.DEFAULT)
        // A cross-tenant caller sees the platform-level dataset when it reads the platform tenant (decision D254 ⑦).
        val operator = Caller(tenantId = TENANT, crossScope = true)
        DatasetVisibility.DEFAULT.visible(
            regionsDataset(),
            QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant.ALL),
        ).assert().isTrue()
        DatasetVisibility.DEFAULT.visible(
            regionsDataset(),
            QueryCaller(operator, QueryEntry.HTTP, crossTenant = CrossTenant(setOf(TENANT))),
        ).assert().isFalse()
    }

    @Test
    fun `no dataset gateways should serve no dataset route`() {
        val plain = HttpNode(manifest = queryManifest()) { HttpQueries() }
        plain.get("/wow/dataset", "alice").status.assert().isEqualTo(404)
        plain.get(SALES, "alice").status.assert().isEqualTo(404)
    }

    private companion object {
        const val SALES = "/wow/dataset/shop/sales/query"
        const val SERVICE = "basket-service"
        const val REGIONS = "/wow/dataset/platform/regions/query"
    }
}
