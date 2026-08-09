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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class EventStoreElasticsearchSnapshotIndexRebuilderTest {
    @Test
    fun `should rebuild ordered authoritative pages into the exact physical index`() {
        val first = aggregate("001", 1)
        val second = aggregate("002", 2)
        val third = aggregate("003", 3)
        val requests = mutableListOf<Pair<String, Int>>()
        val writes = mutableListOf<Pair<ElasticsearchPhysicalIndex, String>>()
        val source = ElasticsearchAuthoritativeSnapshotSource { target, afterId, limit ->
            target.assert().isEqualTo(TARGET)
            requests += afterId to limit
            Flux.fromIterable(
                when (afterId) {
                    AggregateIdScanner.FIRST_ID -> listOf(first, second)
                    "002" -> listOf(third)
                    else -> emptyList()
                },
            )
        }
        val writer = ElasticsearchPhysicalSnapshotWriter { index, snapshot ->
            writes += index to snapshot.aggregateId.id
            Mono.empty()
        }

        val receipt = rebuilder(source, writer).rebuild(COMMAND, MANIFEST).block()!!

        requests.assert().containsExactly(AggregateIdScanner.FIRST_ID to 2, "002" to 2)
        writes.assert().containsExactly(
            DESTINATION to "001",
            DESTINATION to "002",
            DESTINATION to "003",
        )
        receipt.assert().isEqualTo(
            ElasticsearchIndexRebuildReceipt(
                DESTINATION,
                ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
                null,
                null,
                NOW,
            ),
        )
    }

    @Test
    fun `should reject unordered or over-sized source pages before writing`() {
        val writes = AtomicInteger()
        val writer = ElasticsearchPhysicalSnapshotWriter { _, _ ->
            writes.incrementAndGet()
            Mono.empty()
        }
        listOf(
            listOf(aggregate("002", 1), aggregate("001", 1)),
            listOf(aggregate("001", 1), aggregate("002", 1), aggregate("003", 1)),
        ).forEach { page ->
            val source = ElasticsearchAuthoritativeSnapshotSource { _, _, _ -> Flux.fromIterable(page) }
            assertThrownBy<IllegalStateException> {
                rebuilder(source, writer).rebuild(COMMAND, MANIFEST).block()
            }
        }
        writes.get().assert().isZero()
    }

    @Test
    fun `should reject a non-snapshot rebuild strategy before source access`() {
        val scans = AtomicInteger()
        val source = ElasticsearchAuthoritativeSnapshotSource { _, _, _ ->
            scans.incrementAndGet()
            Flux.empty()
        }
        val eventManifest = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("event-rebuild"),
            QueryTarget(TARGET.namedAggregate, QueryDocumentKind.EVENT_STREAM),
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(3),
            SchemaContractId("3".repeat(64)),
            ElasticsearchIndexCapabilityDigest("4".repeat(64)),
            ElasticsearchPhysicalIndex("wow.sales.order.event_stream-v0001-000001"),
            ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
            testEventStreamVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )

        assertThrownBy<ElasticsearchIndexLifecycleException> {
            rebuilder(source, ElasticsearchPhysicalSnapshotWriter { _, _ -> Mono.empty() })
                .rebuild(COMMAND, eventManifest)
                .block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED)
            },
        )
        scans.get().assert().isZero()
    }

    private fun rebuilder(
        source: ElasticsearchAuthoritativeSnapshotSource,
        writer: ElasticsearchPhysicalSnapshotWriter,
    ) = EventStoreElasticsearchSnapshotIndexRebuilder(
        source,
        writer,
        CLOCK,
        ElasticsearchSnapshotRebuildOptions(scanPageSize = 2, writeConcurrency = 1),
    )

    private fun aggregate(id: String, aggregateVersion: Int): ReadOnlyStateAggregate<Any> = mockk {
        every { aggregateId } returns TARGET.namedAggregate.aggregateId(id, "tenant-1")
        every { version } returns aggregateVersion
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("snapshot-rebuild"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(3),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("2".repeat(64)),
            ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001"),
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            testVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val DESTINATION: ElasticsearchPhysicalIndex = MANIFEST.names.physical
        val COMMAND = ElasticsearchIndexLifecycleCommandId("rebuild-command")
    }
}
