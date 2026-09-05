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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.NodeShard
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch.cat.shards.ShardsRecord
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.rest5_client.low_level.ResponseException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchSummaryExecutionIntegrationTest {

    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var nativeClient: ElasticsearchClient

    @BeforeEach
    fun setup() {
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        nativeClient = ElasticsearchClient(client._transport(), client._transportOptions())
    }

    @Test
    fun `summary should preserve alias filter and search routing`() {
        val index = elasticsearch.index("summary")
        val alias = "$index-alias"
        var aliasCreated = false
        try {
            createIndex(index)
            val (routeA, routeB) = routesOnDistinctStartedShards(index)
            client.indices().putAlias { request ->
                request.index(index)
                    .name(alias)
                    .filter { query ->
                        query.term { term -> term.field("visible").value(true) }
                    }.searchRouting(routeA)
            }.block(TIMEOUT)
            aliasCreated = true

            indexDocument(index, "visible-a", routeA, visible = true, amount = 5.0)
            indexDocument(index, "hidden-a", routeA, visible = false, amount = 100.0)
            indexDocument(index, "visible-b", routeB, visible = true, amount = 1000.0)

            ElasticsearchAggregationPager(client, alias).execute(summaryPlan("amount")).test()
                .assertNext {
                    it.path("count").longValue().assert().isEqualTo(1L)
                    it.path("total").doubleValue().assert().isEqualTo(5.0)
                }.verifyComplete()
        } finally {
            try {
                if (aliasCreated) {
                    client.indices().deleteAlias { request -> request.index(index).name(alias) }.block(TIMEOUT)
                }
            } finally {
                deleteIndex(index)
            }
        }
    }

    @Test
    fun `summary should fail when one primary shard is unavailable`() {
        val index = elasticsearch.index("summary")
        try {
            createIndex(index, totalShardsPerNode = 1)
            val shards = waitForPrimaryShards(index) { primaries ->
                primaries.count { it.state() == "STARTED" } == 1 &&
                    primaries.count { it.state() == "UNASSIGNED" } == 1
            }
            val route = ROUTES.first { route -> routedShardOrNull(index, route)?.state()?.jsonValue() == "STARTED" }
            indexDocument(index, "available", route, visible = true, amount = 1.0)

            shards.map(ShardsRecord::state).toSet().assert().isEqualTo(setOf("STARTED", "UNASSIGNED"))
            shards.map(ShardsRecord::shard).toSet().size.assert().isEqualTo(2)
            ElasticsearchAggregationPager(client, index).execute(summaryPlan("amount")).test()
                .expectErrorMatches { error ->
                    val matched = error is ResponseException &&
                        error.message?.contains("search_phase_execution_exception") == true &&
                        error.message?.contains("Search rejected due to missing shards") == true
                    if (matched) {
                        println(
                            "unavailable-shard-error=search_phase_execution_exception: " +
                                "Search rejected due to missing shards",
                        )
                    }
                    matched
                }.verify(TIMEOUT)
        } finally {
            deleteIndex(index)
        }
    }

    @Test
    fun `summary should fail when a shard runtime script fails`() {
        val index = elasticsearch.index("summary")
        try {
            createIndex(index)
            val (routeA, routeB) = routesOnDistinctStartedShards(index)
            indexDocument(index, "success", routeA, visible = true, fail = false, amount = 1.0)
            indexDocument(index, "failure", routeB, visible = true, fail = true, amount = 1.0)

            val runtime = RuntimeField.of { field ->
                field.type(RuntimeFieldType.Double).script { script ->
                    script.source { source ->
                        source.scriptString(
                            "if (doc['fail'].value) { " +
                                "throw new IllegalArgumentException('summary-probe'); } emit(1.0);",
                        )
                    }
                }
            }
            val plan = summaryPlan("probe_value").let { compiled ->
                compiled.copy(runtimeMappings = compiled.runtimeMappings + ("probe_value" to runtime))
            }

            ElasticsearchAggregationPager(client, index).execute(plan).test()
                .expectErrorMatches { error ->
                    (error as? ElasticsearchException)?.also {
                        println("runtime-script-error=${it.error()}")
                    }?.let {
                        it.error().type() == "search_phase_execution_exception" &&
                            it.error().toString().contains("summary-probe")
                    } == true
                }.verify(TIMEOUT)
        } finally {
            deleteIndex(index)
        }
    }

    private fun createIndex(index: String, totalShardsPerNode: Int? = null) {
        client.indices().create { request ->
            request.index(index)
                .settings { settings ->
                    settings.numberOfShards("2").numberOfReplicas("0").apply {
                        totalShardsPerNode?.let {
                            otherSettings("routing.allocation.total_shards_per_node", JsonData.of(it))
                        }
                    }
                }.mappings { mapping ->
                    mapping.properties("visible") { it.boolean_ { boolean -> boolean } }
                        .properties("fail") { it.boolean_ { boolean -> boolean } }
                        .properties("amount") { it.double_ { number -> number } }
                }.apply {
                    if (totalShardsPerNode != null) {
                        waitForActiveShards { it.count(0) }
                    }
                }
        }.block(TIMEOUT)
    }

    private fun routesOnDistinctStartedShards(index: String): Pair<String, String> {
        waitForPrimaryShards(index) { primaries ->
            primaries.size == 2 && primaries.all { it.state() == "STARTED" }
        }
        val routesByShard = ROUTES.associateBy { route -> routedShard(index, route).shard() }
        routesByShard.size.assert().isEqualTo(2)
        return routesByShard.values.toList().let { it[0] to it[1] }
    }

    private fun routedShard(index: String, route: String): NodeShard =
        requireNotNull(routedShardOrNull(index, route))

    private fun routedShardOrNull(index: String, route: String): NodeShard? =
        nativeClient.searchShards { request -> request.index(index).routing(route) }
            .shards().single().singleOrNull()

    private fun waitForPrimaryShards(
        index: String,
        ready: (List<ShardsRecord>) -> Boolean,
    ): List<ShardsRecord> {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        var primaries = emptyList<ShardsRecord>()
        while (System.nanoTime() < deadline) {
            primaries = nativeClient.cat().shards { request -> request.index(index) }
                .shards().filter { it.prirep() == "p" }
            if (ready(primaries)) {
                println(
                    "elasticsearch=${nativeClient.info().version().number()} index=$index primaries=" +
                        primaries.joinToString { "${it.shard()}:${it.state()}" },
                )
                return primaries
            }
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        error("Timed out waiting for primary shards: $primaries")
    }

    private fun indexDocument(
        index: String,
        id: String,
        routing: String,
        visible: Boolean,
        fail: Boolean = false,
        amount: Double,
    ) {
        client.index { request ->
            request.index(index)
                .id(id)
                .routing(routing)
                .document(
                    mapOf("deleted" to false, "visible" to visible, "fail" to fail, "amount" to amount),
                ).refresh(Refresh.True)
        }.block(TIMEOUT)
    }

    private fun deleteIndex(index: String) {
        client.indices().delete { request -> request.index(index).ignoreUnavailable(true) }.block(TIMEOUT)
    }

    private fun summaryPlan(field: String): ElasticsearchAggregationPlan =
        ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("count")
                sum(field, "total")
            },
            QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
        )

    companion object {
        private val TIMEOUT = Duration.ofSeconds(15)
        private val POLL_INTERVAL = Duration.ofMillis(100)
        private val ROUTES = (0..31).map(Int::toString)
    }
}
