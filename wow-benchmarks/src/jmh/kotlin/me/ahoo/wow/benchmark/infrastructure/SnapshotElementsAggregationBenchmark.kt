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

package me.ahoo.wow.benchmark.infrastructure

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class SnapshotElementsAggregationBenchmark {
    @Param("mongo", "elasticsearch")
    lateinit var backend: String

    @Param("root", "single-low", "single-high", "three-low", "three-high", "single-metric", "three-metric")
    lateinit var scenario: String

    private lateinit var snapshotStore: SnapshotStore
    private lateinit var queryService: SnapshotQueryService<SnapshotElementsBenchmarkState>
    private lateinit var query: AggregationQuery
    private var closeFixture: (() -> Unit)? = null

    @Setup(Level.Trial)
    fun setup() {
        val metadata = aggregateMetadata<SnapshotElementsBenchmarkAggregate, SnapshotElementsBenchmarkState>()
        when (backend) {
            "mongo" -> {
                val fixture = MongoBenchmarkFixture()
                snapshotStore = MongoSnapshotStore(fixture.database)
                queryService = MongoSnapshotQueryServiceFactory(fixture.database).create(metadata)
                closeFixture = fixture::close
            }

            "elasticsearch" -> {
                val fixture = ElasticsearchBenchmarkFixture()
                fixture.client.indices().create(
                    CreateIndexRequest.of { request ->
                        request.index(metadata.toSnapshotIndexName())
                            .mappings { mapping ->
                                mapping.properties("state") { state ->
                                    state.`object` { root ->
                                        root.properties("items") { items -> items.nested { it } }
                                            .properties("groups") { groups ->
                                                groups.nested { first ->
                                                    first.properties("children") { children ->
                                                        children.nested { second ->
                                                            second.properties("leaves") { leaves -> leaves.nested { it } }
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                    },
                ).block()
                snapshotStore = ElasticsearchSnapshotStore(fixture.client)
                queryService = ElasticsearchSnapshotQueryServiceFactory(fixture.client).create(metadata)
                closeFixture = {
                    fixture.client.indices().delete(
                        DeleteIndexRequest.of { request -> request.index(metadata.toSnapshotIndexName()) },
                    ).onErrorResume { Mono.empty() }.block()
                    fixture.close()
                }
            }

            else -> error("Unsupported backend: $backend")
        }
        Flux.range(0, SNAPSHOT_COUNT)
            .flatMap({ index -> snapshotStore.save(index.toSnapshot()) }, SETUP_CONCURRENCY)
            .blockLast()
        query = scenario.toQuery()
    }

    @Benchmark
    fun aggregate(blackhole: Blackhole) {
        queryService.aggregate(query).doOnNext(blackhole::consume).blockLast()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        snapshotStore.close()
        closeFixture?.invoke()
    }

    private fun Int.toSnapshot(): SimpleSnapshot<SnapshotElementsBenchmarkState> {
        val metadata = aggregateMetadata<SnapshotElementsBenchmarkAggregate, SnapshotElementsBenchmarkState>()
        val stateAggregate = ConstructorStateAggregateFactory.create(metadata.state, metadata.aggregateId(toString()))
        stateAggregate.state.rootGroup = this % LOW_CARDINALITY
        stateAggregate.state.rootValue = toDouble()
        stateAggregate.state.items = List(LEAVES_PER_SNAPSHOT) { leaf ->
            BenchmarkItem(
                key = "$this-$leaf",
                group = leaf % LOW_CARDINALITY,
                amount = leaf.toDouble(),
            )
        }
        stateAggregate.state.groups = List(FIRST_LEVEL_SIZE) { first ->
            BenchmarkFirstLevel(
                children = List(SECOND_LEVEL_SIZE) { second ->
                    BenchmarkSecondLevel(
                        leaves = List(THIRD_LEVEL_SIZE) { leaf ->
                            val leafIndex = (first * SECOND_LEVEL_SIZE + second) * THIRD_LEVEL_SIZE + leaf
                            BenchmarkLeaf(
                                key = "$this-$leafIndex",
                                group = leafIndex % LOW_CARDINALITY,
                                amount = leafIndex.toDouble(),
                            )
                        },
                    )
                },
            )
        }
        return SimpleSnapshot(stateAggregate)
    }

    private fun String.toQuery(): AggregationQuery = when (this) {
        "root" -> grouped("state.rootGroup", emptyList())
        "single-low" -> grouped("state.items.group", listOf(AggregationElement("state.items")))
        "single-high" -> grouped("state.items.key", listOf(AggregationElement("state.items")))
        "three-low" -> grouped("state.groups.children.leaves.group", THREE_LEVEL_ELEMENTS)
        "three-high" -> grouped("state.groups.children.leaves.key", THREE_LEVEL_ELEMENTS)
        "single-metric" -> metricTopN("state.items.key", "state.items.amount", listOf(AggregationElement("state.items")))
        "three-metric" -> metricTopN(
            "state.groups.children.leaves.key",
            "state.groups.children.leaves.amount",
            THREE_LEVEL_ELEMENTS,
        )

        else -> error("Unsupported scenario: $this")
    }

    private fun grouped(field: String, elements: List<AggregationElement>) = AggregationQuery(
        elements = elements,
        groupBy = listOf(AggregationGroup.Terms(field, "group")),
        metrics = listOf(AggregationMetric.Count("count")),
        limit = AggregationQuery.MAX_LIMIT,
    )

    private fun metricTopN(field: String, amount: String, elements: List<AggregationElement>) = AggregationQuery(
        elements = elements,
        groupBy = listOf(AggregationGroup.Terms(field, "group")),
        metrics = listOf(
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Field(amount),
                "amount",
            ),
        ),
        sort = listOf(Sort("amount", Sort.Direction.DESC)),
        limit = TOP_N,
    )

    private companion object {
        const val SNAPSHOT_COUNT = 10_000
        const val LEAVES_PER_SNAPSHOT = 100
        const val FIRST_LEVEL_SIZE = 5
        const val SECOND_LEVEL_SIZE = 5
        const val THIRD_LEVEL_SIZE = 4
        const val LOW_CARDINALITY = 10
        const val TOP_N = 100
        const val SETUP_CONCURRENCY = 64
        val THREE_LEVEL_ELEMENTS = listOf(
            AggregationElement("state.groups"),
            AggregationElement("state.groups.children"),
            AggregationElement("state.groups.children.leaves"),
        )
    }
}
