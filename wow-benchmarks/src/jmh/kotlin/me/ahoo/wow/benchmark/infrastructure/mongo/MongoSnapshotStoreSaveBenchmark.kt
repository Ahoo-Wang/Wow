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

package me.ahoo.wow.benchmark.infrastructure.mongo

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.mql.MqlValues
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.infrastructure.StorageBatchTuningOptions
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.MongoSnapshotStoreBatchOptions
import me.ahoo.wow.mongo.SnapshotSchemaInitializer
import me.ahoo.wow.mongo.toDocument
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@State(Scope.Benchmark)
open class MongoSnapshotStoreSaveBenchmark {
    @Param("128x1000us")
    lateinit var batchOptions: String

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var snapshotCollection: MongoCollection<Document>
    private lateinit var directSnapshotStore: MongoSnapshotStore
    private lateinit var batchSnapshotStore: MongoSnapshotStore
    private val expectedWrites = AtomicLong()

    @Setup(Level.Trial)
    fun setupTrial() {
        val parsedBatchOptions = StorageBatchTuningOptions.parse(batchOptions)
        fixture = MongoBenchmarkFixture()
        SnapshotSchemaInitializer(fixture.database).initSchema(BenchmarkAggregates.cartMetadata)
        snapshotCollection = fixture.database.getCollection(
            BenchmarkAggregates.namedAggregate.toSnapshotCollectionName()
        )
        directSnapshotStore = MongoSnapshotStore(fixture.database)
        batchSnapshotStore = MongoSnapshotStore(
            database = fixture.database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = parsedBatchOptions.maxSize,
                maxDelay = parsedBatchOptions.maxDelay,
            ),
        )
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        expectedWrites.set(0)
        checkNotNull(
            snapshotCollection.deleteMany(Document()).toMono().block(SAVE_TIMEOUT)
        ).wasAcknowledged().let(::check)
    }

    @TearDown(Level.Iteration)
    fun verifyIterationWrites() {
        val actualWrites = checkNotNull(
            snapshotCollection.countDocuments().toMono().block(SAVE_TIMEOUT)
        )
        check(actualWrites == expectedWrites.get()) {
            "Mongo snapshot write count mismatch: expected=${expectedWrites.get()}, actual=$actualWrites."
        }
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        try {
            batchSnapshotStore.close()
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(SAVES_PER_INVOCATION)
    fun saveWithUpdateOne(blackhole: Blackhole) {
        save(directSnapshotStore, blackhole)
    }

    @Benchmark
    @OperationsPerInvocation(SAVES_PER_INVOCATION)
    fun saveWithNativeBulkWrite(blackhole: Blackhole) {
        val models = List(SAVES_PER_INVOCATION) {
            val document = snapshot().toDocument()
            UpdateOneModel<Document>(
                Filters.eq(Documents.ID_FIELD, document.getString(Documents.ID_FIELD)),
                versionGuardedSnapshotReplacement(document),
                VERSION_GUARDED_UPDATE_OPTIONS,
            )
        }
        val result = snapshotCollection
            .bulkWrite(models, UNORDERED_BULK_WRITE_OPTIONS)
            .toMono()
            .block(SAVE_TIMEOUT)
        checkNotNull(result)
        check(result.wasAcknowledged())
        check(result.matchedCount + result.upserts.size == SAVES_PER_INVOCATION)
        expectedWrites.addAndGet(SAVES_PER_INVOCATION.toLong())
        blackhole.consume(result)
    }

    @Benchmark
    @OperationsPerInvocation(SAVES_PER_INVOCATION)
    fun saveWithCoordinatedBatch(blackhole: Blackhole) {
        save(batchSnapshotStore, blackhole)
    }

    private fun save(snapshotStore: MongoSnapshotStore, blackhole: Blackhole) {
        val result = Flux.range(0, SAVES_PER_INVOCATION)
            .flatMap(
                {
                    snapshotStore.save(snapshot())
                        .thenReturn(Unit)
                },
                SAVES_PER_INVOCATION,
                1,
            ).count()
            .block(SAVE_TIMEOUT)
        check(result == SAVES_PER_INVOCATION.toLong())
        expectedWrites.addAndGet(checkNotNull(result))
        blackhole.consume(result)
    }

    private fun snapshot(): Snapshot<CartState> {
        val aggregateId = BenchmarkAggregates.aggregateId()
        val aggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            aggregateId,
        )
        return SimpleSnapshot(
            delegate = BenchmarkEvents.singleEventStream(aggregateId).toStateEvent(aggregate),
            snapshotTime = 1,
        )
    }

    private fun versionGuardedSnapshotReplacement(
        snapshotDocument: Document,
    ): List<Bson> {
        val snapshotVersion = snapshotDocument[MessageRecords.VERSION]
        check(snapshotVersion is Int)
        val candidateVersion = MqlValues.of(snapshotVersion)
        val candidate = MqlValues.of(snapshotDocument)
        val stored = MqlValues.current()
        val normalizedStoredVersion = stored.getField(MessageRecords.VERSION)
            .isIntegerOr(candidateVersion)
        val replacement = normalizedStoredVersion.lte(candidateVersion)
            .cond(candidate, stored)
        return listOf(Aggregates.replaceWith(replacement))
    }

    companion object {
        const val SAVES_PER_INVOCATION: Int = 128
        private val SAVE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val VERSION_GUARDED_UPDATE_OPTIONS = UpdateOptions().upsert(true)
        private val UNORDERED_BULK_WRITE_OPTIONS = BulkWriteOptions().ordered(false)
    }
}
