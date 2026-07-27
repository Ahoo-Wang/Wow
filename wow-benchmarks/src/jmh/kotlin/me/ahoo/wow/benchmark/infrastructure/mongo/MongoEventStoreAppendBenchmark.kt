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

import com.mongodb.client.model.InsertManyOptions
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.infrastructure.StorageBatchTuningOptions
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoEventStoreBatchOptions
import me.ahoo.wow.mongo.toDocument
import org.bson.Document
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
open class MongoEventStoreAppendBenchmark {
    @Param("128x1000us")
    lateinit var batchOptions: String

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var documentCollection: MongoCollection<Document>
    private lateinit var directEventStore: MongoEventStore
    private lateinit var batchEventStore: MongoEventStore
    private val expectedWrites = AtomicLong()

    @Setup(Level.Trial)
    fun setupTrial() {
        val parsedBatchOptions = StorageBatchTuningOptions.parse(batchOptions)
        fixture = MongoBenchmarkFixture()
        documentCollection = fixture.database.getCollection(
            BenchmarkAggregates.namedAggregate.toEventStreamCollectionName()
        )
        directEventStore = MongoEventStore(fixture.database)
        batchEventStore = MongoEventStore(
            database = fixture.database,
            batchOptions = MongoEventStoreBatchOptions(
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
            documentCollection.deleteMany(Document()).toMono().block(APPEND_TIMEOUT)
        ).wasAcknowledged().let(::check)
    }

    @TearDown(Level.Iteration)
    fun verifyIterationWrites() {
        val actualWrites = checkNotNull(
            documentCollection.countDocuments().toMono().block(APPEND_TIMEOUT)
        )
        check(actualWrites == expectedWrites.get()) {
            "Mongo append write count mismatch: expected=${expectedWrites.get()}, actual=$actualWrites."
        }
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        try {
            batchEventStore.close()
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithInsertOne(blackhole: Blackhole) {
        append(directEventStore, blackhole)
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithNativeInsertMany(blackhole: Blackhole) {
        val documents = List(APPENDS_PER_INVOCATION) {
            BenchmarkEvents.singleEventStream().toDocument()
        }
        val result = documentCollection
            .insertMany(documents, UNORDERED_INSERT)
            .toMono()
            .block(APPEND_TIMEOUT)
        checkNotNull(result)
        check(result.wasAcknowledged())
        check(result.insertedIds.size == APPENDS_PER_INVOCATION)
        expectedWrites.addAndGet(result.insertedIds.size.toLong())
        blackhole.consume(result)
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithInsertManyBatch(blackhole: Blackhole) {
        append(batchEventStore, blackhole)
    }

    private fun append(eventStore: MongoEventStore, blackhole: Blackhole) {
        val result = Flux.range(0, APPENDS_PER_INVOCATION)
            .flatMap(
                {
                    eventStore.append(BenchmarkEvents.singleEventStream())
                        .thenReturn(Unit)
                },
                APPENDS_PER_INVOCATION,
                1,
            ).count()
            .block(APPEND_TIMEOUT)
        check(result == APPENDS_PER_INVOCATION.toLong())
        expectedWrites.addAndGet(checkNotNull(result))
        blackhole.consume(result)
    }

    companion object {
        const val APPENDS_PER_INVOCATION: Int = 128
        private val APPEND_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val UNORDERED_INSERT = InsertManyOptions().ordered(false)
    }
}
