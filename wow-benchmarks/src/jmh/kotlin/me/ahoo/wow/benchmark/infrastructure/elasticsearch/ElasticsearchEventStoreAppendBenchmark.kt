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
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStoreBatchOptions
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infrastructure.elasticsearch.ElasticsearchBenchmarkFixture
import me.ahoo.wow.serialization.toLinkedHashMap
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
import java.time.Duration

@State(Scope.Benchmark)
open class ElasticsearchEventStoreAppendBenchmark {
    @Param("False", "True")
    lateinit var refresh: String

    private lateinit var fixture: ElasticsearchBenchmarkFixture
    private lateinit var directEventStore: ElasticsearchEventStore
    private lateinit var batchEventStore: ElasticsearchEventStore
    private lateinit var refreshPolicy: Refresh

    @Setup(Level.Iteration)
    fun setup() {
        fixture = ElasticsearchBenchmarkFixture()
        refreshPolicy = Refresh.valueOf(refresh)
        directEventStore = ElasticsearchEventStore(
            elasticsearchClient = fixture.client,
            refreshPolicy = refreshPolicy,
        )
        batchEventStore = ElasticsearchEventStore(
            elasticsearchClient = fixture.client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = APPENDS_PER_INVOCATION,
                maxDelay = Duration.ofMillis(1),
            ),
            refreshPolicy = refreshPolicy,
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        try {
            batchEventStore.close()
        } finally {
            try {
                directEventStore.close()
            } finally {
                fixture.close()
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithSingleCreate(blackhole: Blackhole) {
        append(directEventStore, blackhole)
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithNativeBulkCreate(blackhole: Blackhole) {
        val streams = List(APPENDS_PER_INVOCATION) {
            BenchmarkEvents.singleEventStream()
        }
        val request = BulkRequest.of { bulk ->
            bulk.refresh(refreshPolicy)
                .operations(streams.map(::toCreateOperation))
        }
        val result = checkNotNull(fixture.client.bulk(request).block())
        check(!result.errors()) {
            "Native Elasticsearch Bulk create failed: ${result.items().filter { it.error() != null }}"
        }
        check(result.items().size == APPENDS_PER_INVOCATION)
        blackhole.consume(result)
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendWithCoordinatedBulkCreate(blackhole: Blackhole) {
        append(batchEventStore, blackhole)
    }

    private fun append(eventStore: ElasticsearchEventStore, blackhole: Blackhole) {
        val result = Flux.range(0, APPENDS_PER_INVOCATION)
            .flatMap(
                {
                    eventStore.append(BenchmarkEvents.singleEventStream())
                        .thenReturn(Unit)
                },
                APPENDS_PER_INVOCATION,
                1,
            )
            .count()
            .block()
        check(result == APPENDS_PER_INVOCATION.toLong())
        blackhole.consume(result)
    }

    private fun toCreateOperation(eventStream: DomainEventStream): BulkOperation {
        return BulkOperation.of { operation ->
            operation.create<Map<String, Any?>> { create ->
                create.index(eventStream.aggregateId.toEventStreamIndexName())
                    .id("${eventStream.aggregateId.id}-${eventStream.version}")
                    .routing(eventStream.aggregateId.id)
                    .document(eventStream.toLinkedHashMap())
            }
        }
    }

    companion object {
        const val APPENDS_PER_INVOCATION: Int = 128
    }
}
