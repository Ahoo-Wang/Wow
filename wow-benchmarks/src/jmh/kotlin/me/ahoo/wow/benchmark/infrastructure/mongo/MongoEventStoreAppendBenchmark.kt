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

import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoEventStoreBatchOptions
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Flux
import java.time.Duration

@State(Scope.Benchmark)
open class MongoEventStoreAppendBenchmark {
    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var directEventStore: MongoEventStore
    private lateinit var batchEventStore: MongoEventStore

    @Setup(Level.Iteration)
    fun setup() {
        fixture = MongoBenchmarkFixture()
        directEventStore = MongoEventStore(fixture.database)
        batchEventStore = MongoEventStore(
            database = fixture.database,
            batchOptions = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = APPENDS_PER_INVOCATION,
                maxDelay = Duration.ofMillis(1),
            ),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
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
            .block()
        check(result == APPENDS_PER_INVOCATION.toLong())
        blackhole.consume(result)
    }

    companion object {
        const val APPENDS_PER_INVOCATION: Int = 128
    }
}
