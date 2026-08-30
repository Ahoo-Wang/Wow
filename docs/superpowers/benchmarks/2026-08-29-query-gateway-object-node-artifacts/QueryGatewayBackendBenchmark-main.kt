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

package me.ahoo.wow.benchmark.query

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents.replaceAggregateIdToPrimaryKey
import me.ahoo.wow.mongo.SnapshotSchemaInitializer
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.DataMasking
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.serialization.toLinkedHashMap
import org.bson.Document
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import reactor.kotlin.core.publisher.toMono
import java.time.Duration
import java.util.Random
import java.util.concurrent.TimeUnit

private const val DATASET_SIZE = 1_000
private const val DATASET_SEED = 20_260_829L
private const val MASK_FIELD = "maskProbe"
private const val MASK_VALUE = "***"
private const val ELASTICSEARCH_BATCH_SIZE = 100

@AggregateRoot
class QueryBenchmarkAggregate(private val state: QueryBenchmarkState)

data class QueryBenchmarkState(
    val id: String,
    val group: Int,
    val payload: String,
    var maskProbe: String? = null,
) : DataMasking<QueryBenchmarkState> {
    constructor(id: String) : this(id, 0, "")

    override fun mask(): QueryBenchmarkState = apply { maskProbe = MASK_VALUE }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class QueryGatewayBackendBenchmark {
    @Param("mongo", "elasticsearch")
    lateinit var storage: String

    @Param("single", "list100", "list1000", "paged100")
    lateinit var operation: String

    @Param("dynamic", "typed")
    lateinit var result: String

    @Param("none", "inPlace")
    lateinit var masking: String

    private val namedAggregate: NamedAggregate = MaterializedNamedAggregate("benchmark-query", "query_benchmark")
    private lateinit var gateway: SnapshotQueryGateway
    private lateinit var singleQuery: ISingleQuery
    private lateinit var list100Query: IListQuery
    private lateinit var list1000Query: IListQuery
    private lateinit var paged100Query: IPagedQuery
    private var mongoFixture: MongoBenchmarkFixture? = null
    private var elasticsearchFixture: ElasticsearchBenchmarkFixture? = null

    @Setup(Level.Trial)
    fun setup() {
        require(storage == "mongo" || storage == "elasticsearch") { "Unsupported storage: $storage" }
        require(operation in setOf("single", "list100", "list1000", "paged100")) {
            "Unsupported operation: $operation"
        }
        require(result == "dynamic" || result == "typed") { "Unsupported result: $result" }
        require(masking == "none" || masking == "inPlace") { "Unsupported masking: $masking" }

        val queryService = when (storage) {
            "mongo" -> setupMongo()
            "elasticsearch" -> setupElasticsearch()
            else -> error("Unsupported storage: $storage")
        }
        gateway = createGateway(queryService)
        singleQuery = SingleQuery(IdFilter(aggregateId(0)))
        list100Query = ListQuery(MatchAllFilter, limit = 100)
        list1000Query = ListQuery(MatchAllFilter, limit = 1_000)
        paged100Query = PagedQuery(MatchAllFilter, pagination = Pagination(size = 100))

        val probe = executeConfiguredQuery()
        check(recordCount(probe) == expectedRecordCount()) {
            "Unexpected result count for $storage/$operation/$result/$masking: ${recordCount(probe)}"
        }
        check(maskProbe(probe) == (MASK_VALUE.takeIf { masking == "inPlace" })) {
            "Unexpected mask result for $storage/$operation/$result/$masking: ${maskProbe(probe)}"
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        mongoFixture?.close()
        elasticsearchFixture?.let { fixture ->
            try {
                fixture.client.indices().delete {
                    it.index(namedAggregate.toSnapshotIndexName()).ignoreUnavailable(true)
                }.block(QUERY_TIMEOUT)
            } finally {
                fixture.close()
            }
        }
    }

    @Benchmark
    fun query(blackhole: Blackhole) {
        blackhole.consume(executeConfiguredQuery())
    }

    private fun createGateway(queryService: SnapshotQueryService<QueryBenchmarkState>): SnapshotQueryGateway {
        val factory = object : SnapshotQueryServiceFactory {
            @Suppress("UNCHECKED_CAST")
            override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> =
                queryService as SnapshotQueryService<S>
        }
        val filters = buildList<SnapshotQueryFilter> {
            if (masking == "inPlace") {
                val registry = StateDataMaskerRegistry().apply {
                    register(
                        object : StateDynamicDocumentMasker {
                            override val namedAggregate: NamedAggregate = this@QueryGatewayBackendBenchmark.namedAggregate

                            override fun mask(dynamicDocument: DynamicDocument): DynamicDocument =
                                dynamicDocument.apply { getNestedDocument("state")[MASK_FIELD] = MASK_VALUE }
                        },
                    )
                }
                add(MaskingSnapshotQueryFilter(registry))
            }
            add(TailSnapshotQueryFilter<QueryBenchmarkState>(factory))
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(filters)
            .filterCondition(SnapshotQueryGateway::class)
            .build()
        return DefaultSnapshotQueryGateway(chain)
    }

    private fun setupMongo(): SnapshotQueryService<QueryBenchmarkState> {
        val fixture = MongoBenchmarkFixture().also { mongoFixture = it }
        SnapshotSchemaInitializer(fixture.database).initSchema(namedAggregate)
        val collection = fixture.database.getCollection(namedAggregate.toSnapshotCollectionName())
        val documents = snapshots().map { snapshot ->
            Document(snapshot.toLinkedHashMap()).replaceAggregateIdToPrimaryKey()
        }
        val insert = checkNotNull(collection.insertMany(documents).toMono().block(QUERY_TIMEOUT))
        check(insert.wasAcknowledged())
        return MongoSnapshotQueryService(namedAggregate, collection)
    }

    private fun setupElasticsearch(): SnapshotQueryService<QueryBenchmarkState> {
        val fixture = ElasticsearchBenchmarkFixture().also { elasticsearchFixture = it }
        val indexName = namedAggregate.toSnapshotIndexName()
        fixture.client.indices().delete { it.index(indexName).ignoreUnavailable(true) }.block(QUERY_TIMEOUT)
        val request = BulkRequest.of { bulk ->
            bulk.refresh(Refresh.True).operations(
                snapshots().map { snapshot ->
                    BulkOperation.of { operation ->
                        operation.index<Map<String, Any>> { index ->
                            index.index(indexName)
                                .id(snapshot.aggregateId)
                                .document(snapshot.toLinkedHashMap())
                        }
                    }
                },
            )
        }
        val response = checkNotNull(fixture.client.bulk(request).block(QUERY_TIMEOUT))
        check(!response.errors()) { "Elasticsearch seed failed: ${response.items().filter { it.error() != null }}" }
        return ElasticsearchSnapshotQueryService(
            namedAggregate = namedAggregate,
            elasticsearchClient = fixture.client,
            queryBatchSize = ELASTICSEARCH_BATCH_SIZE,
        )
    }

    private fun snapshots(): List<MaterializedSnapshot<QueryBenchmarkState>> {
        val random = Random(DATASET_SEED)
        return List(DATASET_SIZE) { index ->
            val aggregateId = aggregateId(index)
            MaterializedSnapshot(
                contextName = namedAggregate.contextName,
                aggregateName = namedAggregate.aggregateName,
                tenantId = "benchmark",
                aggregateId = aggregateId,
                version = 1,
                eventId = "event-$aggregateId",
                firstOperator = "benchmark",
                operator = "benchmark",
                firstEventTime = 1_725_000_000_000L + index,
                eventTime = 1_725_000_000_000L + index,
                state = QueryBenchmarkState(
                    id = aggregateId,
                    group = index % 16,
                    payload = buildString(128) {
                        repeat(128) { append(('a'.code + random.nextInt(26)).toChar()) }
                    },
                ),
                snapshotTime = 1_725_000_001_000L + index,
                deleted = false,
            )
        }
    }

    private fun executeConfiguredQuery(): Any = when (operation) {
        "single" -> if (result == "dynamic") {
            checkNotNull(gateway.dynamicSingle(namedAggregate, singleQuery).block(QUERY_TIMEOUT))
        } else {
            checkNotNull(gateway.single(namedAggregate, singleQuery).block(QUERY_TIMEOUT))
        }

        "list100" -> executeList(list100Query)
        "list1000" -> executeList(list1000Query)
        "paged100" -> if (result == "dynamic") {
            checkNotNull(gateway.dynamicPaged(namedAggregate, paged100Query).block(QUERY_TIMEOUT))
        } else {
            checkNotNull(gateway.paged(namedAggregate, paged100Query).block(QUERY_TIMEOUT))
        }

        else -> error("Unsupported operation: $operation")
    }

    private fun executeList(query: IListQuery): List<*> = if (result == "dynamic") {
        checkNotNull(gateway.dynamicList(namedAggregate, query).collectList().block(QUERY_TIMEOUT))
    } else {
        checkNotNull(gateway.list(namedAggregate, query).collectList().block(QUERY_TIMEOUT))
    }

    private fun expectedRecordCount(): Int = when (operation) {
        "single" -> 1
        "list100", "paged100" -> 100
        "list1000" -> 1_000
        else -> error("Unsupported operation: $operation")
    }

    private fun recordCount(queryResult: Any): Int = when (queryResult) {
        is List<*> -> queryResult.size
        is PagedList<*> -> queryResult.list.size
        else -> 1
    }

    private fun maskProbe(queryResult: Any): String? {
        val record = when (queryResult) {
            is List<*> -> queryResult.first()
            is PagedList<*> -> queryResult.list.first()
            else -> queryResult
        }
        return when (record) {
            is DynamicDocument -> record.getNestedDocument("state")[MASK_FIELD] as String?
            is MaterializedSnapshot<*> -> (record.state as QueryBenchmarkState).maskProbe
            else -> error("Unexpected query result: ${record?.javaClass?.name}")
        }
    }

    private fun aggregateId(index: Int): String = "query-benchmark-%04d".format(index)

    companion object {
        private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
