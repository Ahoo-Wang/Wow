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

package me.ahoo.wow.benchmark.infrastructure.snapshot

import co.elastic.clients.elasticsearch.core.CountRequest
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.infrastructure.StorageBatchTuningOptions
import me.ahoo.wow.elasticsearch.ElasticsearchSnapshotIndexInitializer
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStoreBatchOptions
import me.ahoo.wow.eventsourcing.AggregateIdScanner.Companion.FIRST_ID
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandler
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.ThreadParams
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@State(Scope.Benchmark)
open class BatchRegenerateAggregateSnapshotBenchmark {
    @Param("128x1000us")
    lateinit var batchOptions: String

    @Param("1", "2", "4")
    var laneCount: Int = 1

    private lateinit var mongoFixture: MongoBenchmarkFixture
    private lateinit var elasticsearchFixture: ElasticsearchBenchmarkFixture
    private lateinit var eventStore: MongoEventStore
    private lateinit var singleSnapshotStore: ElasticsearchSnapshotStore
    private lateinit var batchSnapshotStore: ElasticsearchSnapshotStore
    private lateinit var singleSnapshotRegenerator: RegenerateSnapshotHandler
    private lateinit var batchSnapshotRegenerator: RegenerateSnapshotHandler
    private lateinit var batchExecutionPolicy: BatchExecutionPolicy
    private lateinit var snapshotIndex: String
    private lateinit var aggregateIds: List<AggregateId>
    private val resourcesClosed = AtomicBoolean()

    @Setup(Level.Trial)
    fun setupTrial(threadParams: ThreadParams) {
        try {
            require(laneCount > 0) {
                "laneCount must be greater than zero."
            }
            val parsedBatchOptions = StorageBatchTuningOptions.parse(batchOptions)
            mongoFixture = MongoBenchmarkFixture()
            elasticsearchFixture = ElasticsearchBenchmarkFixture()
            eventStore = MongoEventStore(mongoFixture.database)
            snapshotIndex = BenchmarkAggregates.namedAggregate.toSnapshotIndexName()
            aggregateIds = List(threadParams.threadCount * AGGREGATES_PER_INVOCATION) { index ->
                BenchmarkAggregates.namedAggregate.aggregateId(
                    "batch-regenerate-${index.toString().padStart(4, '0')}"
                )
            }
            resetSnapshotIndex()
            seedEventHistory()

            singleSnapshotStore = ElasticsearchSnapshotStore(elasticsearchFixture.client)
            batchSnapshotStore = ElasticsearchSnapshotStore(
                elasticsearchClient = elasticsearchFixture.client,
                batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                    enabled = true,
                    maxSize = parsedBatchOptions.maxSize,
                    maxDelay = parsedBatchOptions.maxDelay,
                    laneCount = laneCount,
                ),
            )
            singleSnapshotRegenerator = createRegenerator(singleSnapshotStore)
            batchSnapshotRegenerator = createRegenerator(batchSnapshotStore)
            batchExecutionPolicy = BatchExecutionPolicy(
                concurrency = AGGREGATES_PER_INVOCATION,
                prefetch = 4,
            )
        } catch (error: Throwable) {
            closeInitializedResources()
            throw error
        }
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        try {
            resetSnapshotIndex()
        } catch (error: Throwable) {
            closeInitializedResources()
            throw error
        }
    }

    @TearDown(Level.Iteration)
    fun verifyIteration() {
        try {
            val result = checkNotNull(
                elasticsearchFixture.client.count(
                    CountRequest.of { it.index(snapshotIndex) },
                ).block(SNAPSHOT_TIMEOUT),
            )
            check(result.count() == aggregateIds.size.toLong()) {
                "Snapshot count mismatch: expected=${aggregateIds.size}, actual=${result.count()}."
            }
            val versions = checkNotNull(
                elasticsearchFixture.client.search({
                    it.index(snapshotIndex).size(aggregateIds.size)
                }, Map::class.java).block(SNAPSHOT_TIMEOUT),
            ).hits().hits().map { hit -> hit.source()?.get(MessageRecords.VERSION) }
            check(versions.size == aggregateIds.size && versions.all { it == EVENTS_PER_AGGREGATE }) {
                "Snapshot version validation failed: expected $EVENTS_PER_AGGREGATE for " +
                    "${aggregateIds.size} snapshots, actual=$versions."
            }
        } catch (error: Throwable) {
            closeInitializedResources()
            throw error
        }
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        closeInitializedResources()
    }

    @Benchmark
    @OperationsPerInvocation(AGGREGATES_PER_INVOCATION)
    fun regenerateWithSingleSnapshotStore(threadParams: ThreadParams, blackhole: Blackhole) {
        try {
            regenerate(threadParams, singleSnapshotRegenerator, blackhole)
        } catch (error: Throwable) {
            closeInitializedResources()
            throw error
        }
    }

    @Benchmark
    @OperationsPerInvocation(AGGREGATES_PER_INVOCATION)
    fun regenerateWithBatchSnapshotStore(threadParams: ThreadParams, blackhole: Blackhole) {
        try {
            regenerate(threadParams, batchSnapshotRegenerator, blackhole)
        } catch (error: Throwable) {
            closeInitializedResources()
            throw error
        }
    }

    private fun regenerate(
        threadParams: ThreadParams,
        handler: RegenerateSnapshotHandler,
        blackhole: Blackhole,
    ) {
        val startIndex = threadParams.threadIndex * AGGREGATES_PER_INVOCATION
        val expectedAggregateIds = aggregateIds.subList(
            startIndex,
            startIndex + AGGREGATES_PER_INVOCATION,
        )
        val afterId = if (startIndex == 0) {
            FIRST_ID
        } else {
            aggregateIds[startIndex - 1].id
        }
        val scannedAggregateIds = checkNotNull(
            eventStore.scanAggregateId(
                namedAggregate = BenchmarkAggregates.namedAggregate,
                afterId = afterId,
                limit = AGGREGATES_PER_INVOCATION,
            ).collectList().block(SNAPSHOT_TIMEOUT),
        )
        check(scannedAggregateIds.map { it.id } == expectedAggregateIds.map { it.id }) {
            "Aggregate scan partition mismatch: expected=${expectedAggregateIds.map { it.id }}, " +
                "actual=${scannedAggregateIds.map { it.id }}."
        }
        val count = batchExecutionPolicy.apply(
            Flux.fromIterable(scannedAggregateIds),
        ) { aggregateId ->
            handler.handle(aggregateId).thenReturn(aggregateId)
        }.count().block(SNAPSHOT_TIMEOUT)
        check(count == AGGREGATES_PER_INVOCATION.toLong()) {
            "Aggregate regeneration count mismatch: expected=$AGGREGATES_PER_INVOCATION, actual=$count."
        }
        blackhole.consume(count)
    }

    private fun createRegenerator(snapshotStore: ElasticsearchSnapshotStore): RegenerateSnapshotHandler {
        return RegenerateSnapshotHandler(
            aggregateMetadata = BenchmarkAggregates.cartMetadata,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            snapshotStore = snapshotStore,
        )
    }

    private fun seedEventHistory() {
        Flux.fromIterable(aggregateIds)
            .concatMap { aggregateId ->
                Flux.fromIterable(BenchmarkEvents.constantSizeEventStreams(aggregateId, EVENTS_PER_AGGREGATE))
                    .concatMap(eventStore::append)
            }
            .then()
            .block(SEED_TIMEOUT)
    }

    private fun resetSnapshotIndex() {
        elasticsearchFixture.client.indices()
            .delete { it.index(snapshotIndex).ignoreUnavailable(true) }
            .block(SNAPSHOT_TIMEOUT)
        ElasticsearchSnapshotIndexInitializer(
            elasticsearchClient = elasticsearchFixture.client,
            namedAggregates = listOf(BenchmarkAggregates.namedAggregate),
        ).ensureAll().block(SNAPSHOT_TIMEOUT)
    }

    private fun closeInitializedResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return
        }
        if (this::batchSnapshotStore.isInitialized) {
            runCatching { batchSnapshotStore.close() }
        }
        if (this::singleSnapshotStore.isInitialized) {
            runCatching { singleSnapshotStore.close() }
        }
        if (this::elasticsearchFixture.isInitialized) {
            if (this::snapshotIndex.isInitialized) {
                runCatching {
                    elasticsearchFixture.client.indices()
                        .delete { it.index(snapshotIndex).ignoreUnavailable(true) }
                        .block(SNAPSHOT_TIMEOUT)
                }
            }
            runCatching { elasticsearchFixture.close() }
        }
        if (this::mongoFixture.isInitialized) {
            runCatching { mongoFixture.close() }
        }
    }

    companion object {
        const val AGGREGATES_PER_INVOCATION: Int = 128
        const val EVENTS_PER_AGGREGATE: Int = 10
        private val SEED_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
