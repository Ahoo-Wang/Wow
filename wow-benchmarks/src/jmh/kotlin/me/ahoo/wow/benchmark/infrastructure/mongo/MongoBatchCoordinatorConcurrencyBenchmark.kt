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

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.infrastructure.StorageBatchTuningOptions
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoEventStoreBatchOptions
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

/**
 * Measures the production keyed-lane coordinator path without changing its default
 * lane count.
 *
 * Inputs in this workload are independent event streams routed by aggregate key.
 * Repeated-key ordering is covered by functional tests rather than this throughput
 * workload.
 */
@State(Scope.Benchmark)
open class MongoBatchCoordinatorConcurrencyBenchmark {
    @Param("192x250us")
    lateinit var batchOptions: String

    @Param("1", "2", "4")
    var coordinatorLanes: Int = 1

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var documentCollection: MongoCollection<Document>
    private lateinit var eventStore: MongoEventStore
    private val expectedWrites = AtomicLong()

    @Setup(Level.Trial)
    fun setupTrial() {
        require(coordinatorLanes > 0) {
            "coordinatorLanes must be greater than zero."
        }
        val parsedBatchOptions = StorageBatchTuningOptions.parse(batchOptions)
        fixture = MongoBenchmarkFixture()
        documentCollection = fixture.database.getCollection(
            BenchmarkAggregates.namedAggregate.toEventStreamCollectionName()
        )
        eventStore = MongoEventStore(
            database = fixture.database,
            batchOptions = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = parsedBatchOptions.maxSize,
                maxDelay = parsedBatchOptions.maxDelay,
                laneCount = coordinatorLanes,
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
            "Mongo coordinator concurrency write count mismatch: " +
                "expected=${expectedWrites.get()}, actual=$actualWrites."
        }
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        try {
            eventStore.close()
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithCoordinatorLanes(blackhole: Blackhole) {
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
    }
}
