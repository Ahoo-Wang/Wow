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

package me.ahoo.wow.benchmark.infrastructure.elasticsearch

import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.infrastructure.StorageBatchTuningOptions
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStoreBatchOptions
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture
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

@State(Scope.Benchmark)
open class ElasticsearchEventStoreBatchTuningState {
    @Param("False", "True")
    lateinit var refresh: String

    @Param("128x1000us")
    lateinit var batchOptions: String

    private lateinit var fixture: ElasticsearchBenchmarkFixture
    private lateinit var eventStore: ElasticsearchEventStore

    @Setup(Level.Iteration)
    fun setup() {
        val tuningOptions = StorageBatchTuningOptions.parse(batchOptions)
        fixture = ElasticsearchBenchmarkFixture()
        eventStore = ElasticsearchEventStore(
            elasticsearchClient = fixture.client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = tuningOptions.maxSize,
                maxDelay = tuningOptions.maxDelay,
            ),
            refreshPolicy = Refresh.valueOf(refresh),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
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
                .block()
        ).also { appended ->
            check(appended == count.toLong())
        }
    }
}

open class ElasticsearchEventStoreBatchTuningBenchmark {
    @Benchmark
    @OperationsPerInvocation(ISOLATED_APPENDS)
    fun appendIsolated(
        state: ElasticsearchEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(ISOLATED_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(BURST_APPENDS)
    fun appendBurst32(
        state: ElasticsearchEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(BURST_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(REPRESENTATIVE_APPENDS)
    fun appendRepresentative128(
        state: ElasticsearchEventStoreBatchTuningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.append(REPRESENTATIVE_APPENDS))
    }

    @Benchmark
    @OperationsPerInvocation(SATURATED_APPENDS)
    fun appendSaturated512(
        state: ElasticsearchEventStoreBatchTuningState,
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
