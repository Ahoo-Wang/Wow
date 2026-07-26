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

@State(Scope.Benchmark)
open class MongoEventStoreBatchTuningState {
    @Param("128x1000us")
    lateinit var batchOptions: String

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var documentCollection: MongoCollection<Document>
    private lateinit var eventStore: MongoEventStore

    @Setup(Level.Trial)
    fun setupTrial() {
        val tuningOptions = StorageBatchTuningOptions.parse(batchOptions)
        fixture = MongoBenchmarkFixture()
        documentCollection = fixture.database.getCollection(
            BenchmarkAggregates.namedAggregate.toEventStreamCollectionName()
        )
        eventStore = MongoEventStore(
            database = fixture.database,
            batchOptions = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = tuningOptions.maxSize,
                maxDelay = tuningOptions.maxDelay,
            ),
        )
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        checkNotNull(
            documentCollection.deleteMany(Document()).toMono().block()
        ).wasAcknowledged().let(::check)
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        try {
            eventStore.close()
        } finally {
            fixture.close()
        }
    }

    fun append(count: Int): Long {
        return checkNotNull(
            Flux.range(0, count)
                .flatMap(
                    {
                        eventStore.append(BenchmarkEvents.singleEventStream())
                            .thenReturn(Unit)
                    },
                    count,
                    1,
                )
                .count()
                .block(APPEND_TIMEOUT)
        ).also { appended ->
            check(appended == count.toLong())
        }
    }

    private companion object {
        val APPEND_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

open class MongoEventStoreBatchTuningBenchmark {
    @Benchmark
    @OperationsPerInvocation(ISOLATED_APPENDS)
    fun appendIsolated(
        state: MongoEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(ISOLATED_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(BURST_APPENDS)
    fun appendBurst32(
        state: MongoEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(BURST_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(REPRESENTATIVE_APPENDS)
    fun appendRepresentative128(
        state: MongoEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(REPRESENTATIVE_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(SATURATED_APPENDS)
    fun appendSaturated512(
        state: MongoEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(SATURATED_APPENDS))
    }

    companion object {
        const val ISOLATED_APPENDS: Int = 1
        const val BURST_APPENDS: Int = 32
        const val REPRESENTATIVE_APPENDS: Int = 128
        const val SATURATED_APPENDS: Int = 512
    }
}
