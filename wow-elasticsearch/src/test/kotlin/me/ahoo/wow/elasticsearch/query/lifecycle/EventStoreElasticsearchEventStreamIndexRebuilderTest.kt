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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class EventStoreElasticsearchEventStreamIndexRebuilderTest {
    @Test
    fun `should rebuild every ordered event stream while the write fence remains drained`() {
        val first = eventStream("001")
        val second = eventStream("002")
        val third = eventStream("003")
        val scans = mutableListOf<Pair<String, Int>>()
        val writes = mutableListOf<Pair<ElasticsearchPhysicalIndex, String>>()
        val checkpoints = ArrayDeque(listOf(42L, 42L))
        val source = ElasticsearchAuthoritativeEventStreamSource { target, afterId, limit ->
            target.assert().isEqualTo(TARGET)
            scans += afterId to limit
            Flux.fromIterable(
                when (afterId) {
                    AggregateIdScanner.FIRST_ID -> listOf(first, second)
                    "002" -> listOf(third)
                    else -> emptyList()
                },
            )
        }
        val writer = ElasticsearchPhysicalEventStreamWriter { index, stream ->
            writes += index to "${stream.aggregateId.id}:${stream.version}"
            Mono.empty()
        }
        val barrier = ElasticsearchEventStreamMigrationBarrier { command, manifest ->
            command.assert().isEqualTo(COMMAND)
            manifest.assert().isEqualTo(MANIFEST)
            Mono.just(checkpoints.removeFirst())
        }

        val receipt = rebuilder(source, writer, barrier).rebuild(COMMAND, MANIFEST).block()!!

        scans.assert().containsExactly(AggregateIdScanner.FIRST_ID to 2, "002" to 2)
        writes.assert().containsExactly(
            DESTINATION to "001:1",
            DESTINATION to "002:1",
            DESTINATION to "003:1",
        )
        receipt.assert().isEqualTo(
            ElasticsearchIndexRebuildReceipt(
                DESTINATION,
                ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
                42,
                42,
                NOW,
            ),
        )
    }

    @Test
    fun `should fail closed when the write fence advances during the rebuild`() {
        val writerCalls = AtomicInteger()
        val checkpoints = ArrayDeque(listOf(42L, 43L))
        val source = ElasticsearchAuthoritativeEventStreamSource { _, _, _ -> Flux.just(eventStream("001")) }
        val writer = ElasticsearchPhysicalEventStreamWriter { _, _ ->
            writerCalls.incrementAndGet()
            Mono.empty()
        }

        assertThrownBy<ElasticsearchIndexLifecycleException> {
            rebuilder(
                source,
                writer,
                ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(checkpoints.removeFirst()) },
            ).rebuild(COMMAND, MANIFEST).block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED)
            },
        )
        writerCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `should reject broken aggregate ordering version continuity and page bounds`() {
        val writerCalls = AtomicInteger()
        val writer = ElasticsearchPhysicalEventStreamWriter { _, _ ->
            writerCalls.incrementAndGet()
            Mono.empty()
        }
        val invalidSources = listOf(
            listOf(eventStream("002"), eventStream("001")),
            listOf(eventStream("001", aggregateVersion = 1)),
            listOf(eventStream("001"), eventStream("002"), eventStream("003")),
        )

        invalidSources.forEach { streams ->
            assertThrownBy<IllegalStateException> {
                rebuilder(
                    ElasticsearchAuthoritativeEventStreamSource { _, _, _ -> Flux.fromIterable(streams) },
                    writer,
                    ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(42) },
                ).rebuild(COMMAND, MANIFEST).block()
            }
        }
        // Validation is streaming; already validated immutable documents may be written before a later violation.
        // Exact-generation writes are idempotent, while no rebuild receipt is emitted for any invalid source.
        writerCalls.get().assert().isEqualTo(3)
    }

    @Test
    fun `should reject controlled mirror until its convergence controller is implemented`() {
        val scans = AtomicInteger()
        val source = ElasticsearchAuthoritativeEventStreamSource { _, _, _ ->
            scans.incrementAndGet()
            Flux.empty()
        }
        val mirrorManifest = MANIFEST.copy(
            id = ElasticsearchIndexMigrationId("event-stream-mirror"),
            rebuildStrategy = ElasticsearchIndexRebuildStrategy.EVENT_STREAM_CONTROLLED_MIRROR,
        )

        assertThrownBy<ElasticsearchIndexLifecycleException> {
            rebuilder(
                source,
                ElasticsearchPhysicalEventStreamWriter { _, _ -> Mono.empty() },
                ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(42) },
            ).rebuild(COMMAND, mirrorManifest).block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED)
            },
        )
        scans.get().assert().isZero()
    }

    private fun rebuilder(
        source: ElasticsearchAuthoritativeEventStreamSource,
        writer: ElasticsearchPhysicalEventStreamWriter,
        barrier: ElasticsearchEventStreamMigrationBarrier,
    ) = EventStoreElasticsearchEventStreamIndexRebuilder(
        source,
        writer,
        barrier,
        CLOCK,
        ElasticsearchEventStreamRebuildOptions(aggregateScanPageSize = 2, writeConcurrency = 1),
    )

    private fun eventStream(id: String, aggregateVersion: Int = 0): DomainEventStream =
        MockDomainEventStreams.generateEventStream(
            TARGET.namedAggregate.aggregateId(id, "tenant-1"),
            aggregateVersion = aggregateVersion,
            eventCount = 1,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T06:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.EVENT_STREAM,
        )
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("event-stream-rebuild"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(3),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("2".repeat(64)),
            ElasticsearchPhysicalIndex("wow.sales.order.es-v0001-000001"),
            ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
            testEventStreamVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val DESTINATION: ElasticsearchPhysicalIndex = MANIFEST.names.physical
        val COMMAND = ElasticsearchIndexLifecycleCommandId("event-stream-rebuild-command")
    }
}
