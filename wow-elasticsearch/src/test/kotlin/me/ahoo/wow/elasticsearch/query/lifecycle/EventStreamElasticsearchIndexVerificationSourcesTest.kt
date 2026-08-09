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
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.function.Consumer

class EventStreamElasticsearchIndexVerificationSourcesTest {
    @Test
    fun `authority evidence should bind a stable drained watermark to the canonical stream checksum`() {
        val first = eventStream("001")
        val second = eventStream("002")
        val checkpoints = ArrayDeque(listOf(42L, 42L))
        val source = ElasticsearchAuthoritativeEventStreamSource { _, afterId, _ ->
            when (afterId) {
                AggregateIdScanner.FIRST_ID -> Flux.just(first)
                "001" -> Flux.just(second)
                else -> Flux.empty()
            }
        }
        val verification = EventStoreEventStreamVerificationSource(
            source,
            ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(checkpoints.removeFirst()) },
            EventStreamElasticsearchIndexVerificationOptions(aggregateScanPageSize = 1),
        ).capture(COMMAND, MANIFEST).block()!!

        val expected = EventStreamCanonicalChecksumAccumulator().also { accumulator ->
            listOf(first, second).forEach { stream ->
                accumulator.accept(stream.eventStreamDocumentId(), stream.toLinkedHashMap())
            }
        }.finish()
        verification.count.assert().isEqualTo(2)
        verification.identityChecksum.assert().isEqualTo(expected.identityChecksum)
        verification.contentChecksum.assert().isEqualTo(expected.contentChecksum)
        verification.watermark.assert().isEqualTo(42)
        verification.checksumAlgorithm.assert()
            .isEqualTo(ElasticsearchIndexChecksumAlgorithm.CANONICAL_EVENT_STREAM_SHA256_V1)
    }

    @Test
    fun `authority evidence should fail when the write fence advances`() {
        val checkpoints = ArrayDeque(listOf(42L, 43L))
        val source = ElasticsearchAuthoritativeEventStreamSource { _, _, _ -> Flux.just(eventStream("001")) }

        assertThrownBy<ElasticsearchIndexLifecycleException> {
            EventStoreEventStreamVerificationSource(
                source,
                ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(checkpoints.removeFirst()) },
            ).capture(COMMAND, MANIFEST).block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED)
            },
        )
    }

    private fun eventStream(id: String): DomainEventStream = MockDomainEventStreams.generateEventStream(
        TARGET.namedAggregate.aggregateId(id, "tenant-1"),
        eventCount = 1,
    )

    private companion object {
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.EVENT_STREAM,
        )
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("event-stream-verification"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(4),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("2".repeat(64)),
            ElasticsearchPhysicalIndex("wow.sales.order.es-v0001-000001"),
            ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
            testEventStreamVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val COMMAND = ElasticsearchIndexLifecycleCommandId("event-stream-verify-command")
    }
}
