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

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Consumer

class ElasticsearchIndexLifecycleIntegrationTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @Test
    fun `versioned templates create an attested generation and alias cutover rollback are atomic`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.indices().create { request -> request.index(SOURCE.value).mappings(sourceMapping()) }.block()
        client.indices().updateAliases { request ->
            request.actions { action ->
                action.add { add ->
                    add.index(SOURCE.value).alias(MANIFEST.names.alias.value).isWriteIndex(true)
                }
            }
        }.block()
        val admin = ReactiveElasticsearchIndexAdminClient(client, CLOCK)

        val initial = admin.inspect(MANIFEST).block()!!
        MANIFEST.validate(initial)
        admin.create(MANIFEST, ElasticsearchVersionedIndexTemplate(MANIFEST, destinationMapping())).block()!!
        val created = admin.inspect(MANIFEST).block()!!
        created.indices[DESTINATION].assert().isEqualTo(MANIFEST.destinationAttestation)

        admin.compareAndSetAlias(MANIFEST, SOURCE, DESTINATION).block()!!
        admin.inspect(MANIFEST).block()!!.aliasTarget.assert().isEqualTo(DESTINATION)

        admin.compareAndSetAlias(MANIFEST, DESTINATION, SOURCE).block()!!
        admin.inspect(MANIFEST).block()!!.aliasTarget.assert().isEqualTo(SOURCE)
        client.indices().exists { request -> request.index(SOURCE.value) }.block()!!.value().assert().isTrue()
        client.indices().exists { request -> request.index(DESTINATION.value) }.block()!!.value().assert().isTrue()
    }

    @Test
    fun `durable repository should persist state and enforce elasticsearch compare and set tokens`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val repository = ReactiveElasticsearchIndexLifecycleRepository(client, REPOSITORY_INDEX)
        repository.ensureIndex().block()
        repository.ensureIndex().block()

        val initial = ElasticsearchIndexMigrationState.initial(MANIFEST)
        val created = repository.create(initial).block()!!
        repository.create(initial).block().assert().isNull()

        val reloaded = ReactiveElasticsearchIndexLifecycleRepository(client, REPOSITORY_INDEX)
            .load(MANIFEST.id)
            .block()!!
        reloaded.state.assert().isEqualTo(initial)
        reloaded.version.assert().isInstanceOf(ElasticsearchIndexLifecycleRepositoryVersion.Elasticsearch::class.java)

        val command = ElasticsearchIndexLifecycleCommand(
            ElasticsearchIndexLifecycleCommandId("validate-command"),
            MANIFEST.id,
            ElasticsearchIndexLifecycleCommandType.VALIDATE,
            expectedRevision = initial.revision,
        )
        val claimed = initial.claim(command, NOW)
        val updated = repository.compareAndSet(reloaded, claimed).block()!!
        updated.state.assert().isEqualTo(claimed)
        repository.compareAndSet(created, claimed).block().assert().isNull()
        repository.load(MANIFEST.id).block()!!.state.assert().isEqualTo(claimed)

        val corruptId = ElasticsearchIndexMigrationId("corrupt-lifecycle-state")
        client.index(
            IndexRequest.of<Map<String, Any>> { request ->
                request.index(REPOSITORY_INDEX)
                    .id(corruptId.value)
                    .refresh(Refresh.True)
                    .document(
                        linkedMapOf(
                            "formatVersion" to 1,
                            "migrationId" to corruptId.value,
                            "revision" to 0L,
                            "payload" to "not-base64",
                        ),
                    )
            },
        ).block()
        assertThrownBy<ElasticsearchIndexLifecycleException> {
            repository.load(corruptId).block()
        }.satisfies(
            Consumer { error ->
                error.code.assert().isEqualTo(ElasticsearchIndexLifecycleErrorCode.REPOSITORY_CORRUPTED)
            },
        )
    }

    @Test
    fun `authoritative snapshot rebuild should write replayed states to the exact physical generation`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.indices().create { request -> request.index(REBUILD_MANIFEST.names.physical.value) }.block()
        val first = stateAggregate("001", 1)
        val second = stateAggregate("002", 2)
        val source = ElasticsearchAuthoritativeSnapshotSource { _, afterId, _ ->
            when (afterId) {
                AggregateIdScanner.FIRST_ID -> Flux.just(first, second)
                else -> Flux.empty()
            }
        }
        val rebuilder = EventStoreElasticsearchSnapshotIndexRebuilder(
            source,
            ReactiveElasticsearchPhysicalSnapshotWriter(client, Refresh.True),
            CLOCK,
            ElasticsearchSnapshotRebuildOptions(scanPageSize = 2, writeConcurrency = 2),
        )

        val receipt = rebuilder.rebuild(
            ElasticsearchIndexLifecycleCommandId("integration-rebuild"),
            REBUILD_MANIFEST,
        ).block()!!

        receipt.physicalIndex.assert().isEqualTo(REBUILD_MANIFEST.names.physical)
        client.count { request -> request.index(REBUILD_MANIFEST.names.physical.value) }
            .block()!!.count().assert().isEqualTo(2)
        listOf("001" to 1, "002" to 2).forEach { (id, version) ->
            val stored = client.get(
                { request -> request.index(REBUILD_MANIFEST.names.physical.value).id(id) },
                Map::class.java,
            ).block()!!.source()!!
            stored[MessageRecords.VERSION].assert().isEqualTo(version)
        }
    }

    @Test
    fun `authoritative and exact physical snapshot sources should produce identical canonical evidence`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.indices().create { request ->
            request.index(VERIFY_MANIFEST.names.physical.value).mappings(sourceMapping())
        }.block()
        val first = stateAggregate("001", 1)
        val second = stateAggregate("002", 2)
        val source = ElasticsearchAuthoritativeSnapshotSource { _, afterId, _ ->
            when (afterId) {
                AggregateIdScanner.FIRST_ID -> Flux.just(first)
                "001" -> Flux.just(second)
                else -> Flux.empty()
            }
        }
        val writer = ReactiveElasticsearchPhysicalSnapshotWriter(client, Refresh.True)
        listOf(first, second).forEach { aggregate ->
            writer.write(
                VERIFY_MANIFEST.names.physical,
                SimpleSnapshot(aggregate, NOW.toEpochMilli()),
            ).block()
        }
        val options = SnapshotElasticsearchIndexVerificationOptions(pageSize = 1)

        val expected = EventStoreSnapshotVerificationSource(source, options)
            .capture(VERIFY_COMMAND, VERIFY_MANIFEST)
            .block()!!
        val actual = ReactiveElasticsearchSnapshotVerificationSource(client, options)
            .inspect(VERIFY_COMMAND, VERIFY_MANIFEST, VERIFY_MANIFEST.names.physical)
            .block()!!

        actual.count.assert().isEqualTo(expected.count)
        actual.identityChecksum.assert().isEqualTo(expected.identityChecksum)
        actual.contentChecksum.assert().isEqualTo(expected.contentChecksum)
        actual.versionContinuity.assert().isTrue()
    }

    @Test
    fun `drained event stream authority and exact physical generation should produce identical evidence`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.indices().create { request ->
            request.index(EVENT_VERIFY_MANIFEST.names.physical.value).mappings(eventStreamMapping())
        }.block()
        val streams = listOf(
            eventStream("001", aggregateVersion = 0, eventCount = 2),
            eventStream("001", aggregateVersion = 2, eventCount = 1),
            eventStream("002", aggregateVersion = 0, eventCount = 1),
        )
        val source = ElasticsearchAuthoritativeEventStreamSource { _, afterId, _ ->
            when (afterId) {
                AggregateIdScanner.FIRST_ID -> Flux.fromIterable(streams.take(2))
                "001" -> Flux.just(streams.last())
                else -> Flux.empty()
            }
        }
        val writer = ReactiveElasticsearchPhysicalEventStreamWriter(client, Refresh.True)
        streams.forEach { stream ->
            writer.write(EVENT_VERIFY_MANIFEST.names.physical, stream).block()
            writer.write(EVENT_VERIFY_MANIFEST.names.physical, stream).block()
        }
        val options = EventStreamElasticsearchIndexVerificationOptions(
            aggregateScanPageSize = 1,
            physicalPageSize = 1,
        )
        val barrier = ElasticsearchEventStreamMigrationBarrier { _, _ -> Mono.just(42L) }
        val indexedWatermark = ElasticsearchEventStreamIndexedWatermarkSource { _, _, physical ->
            physical.assert().isEqualTo(EVENT_VERIFY_MANIFEST.names.physical)
            Mono.just(42L)
        }

        val expected = EventStoreEventStreamVerificationSource(source, barrier, options)
            .capture(EVENT_VERIFY_COMMAND, EVENT_VERIFY_MANIFEST)
            .block()!!
        val actual = ReactiveElasticsearchEventStreamVerificationSource(client, indexedWatermark, options)
            .inspect(EVENT_VERIFY_COMMAND, EVENT_VERIFY_MANIFEST, EVENT_VERIFY_MANIFEST.names.physical)
            .block()!!

        actual.count.assert().isEqualTo(3)
        actual.count.assert().isEqualTo(expected.count)
        actual.identityChecksum.assert().isEqualTo(expected.identityChecksum)
        actual.contentChecksum.assert().isEqualTo(expected.contentChecksum)
        actual.watermark.assert().isEqualTo(expected.watermark)
        actual.versionContinuity.assert().isTrue()
    }

    private fun stateAggregate(id: String, version: Int) = ConstructorStateAggregateFactory.create(
        MOCK_AGGREGATE_METADATA.state,
        MOCK_AGGREGATE_METADATA.aggregateId(id, "tenant-1"),
        MockStateAggregate(id),
        version,
    )

    private fun sourceMapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.meta(MAPPING_VERSION, JsonData.of("v0001"))
            .meta(DOCUMENT_KIND, JsonData.of(QueryDocumentKind.SNAPSHOT.name))
            .meta(SCHEMA_CONTRACT, JsonData.of("0".repeat(64)))
            .meta(CAPABILITY_DIGEST, JsonData.of("c".repeat(64)))
            .properties("aggregateId", keyword())
    }

    private fun destinationMapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.meta(MAPPING_VERSION, JsonData.of(MANIFEST.mappingVersion.tag))
            .meta(DOCUMENT_KIND, JsonData.of(MANIFEST.target.documentKind.name))
            .meta(SCHEMA_CONTRACT, JsonData.of(MANIFEST.schemaContractId.value))
            .meta(CAPABILITY_DIGEST, JsonData.of(MANIFEST.capabilityDigest.value))
            .properties("aggregateId", keyword())
    }

    private fun eventStreamMapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties(MessageRecords.AGGREGATE_ID, keyword())
            .properties(MessageRecords.VERSION) { property -> property.integer { integer -> integer } }
    }

    private fun eventStream(
        id: String,
        aggregateVersion: Int,
        eventCount: Int,
    ): DomainEventStream = MockDomainEventStreams.generateEventStream(
        MOCK_AGGREGATE_METADATA.aggregateId(id, "tenant-1"),
        aggregateVersion = aggregateVersion,
        eventCount = eventCount,
    )

    private fun keyword(): Property = Property.of { property -> property.keyword { keyword -> keyword } }

    private companion object {
        const val MAPPING_VERSION = "wow_query_mapping_version"
        const val DOCUMENT_KIND = "wow_query_document_kind"
        const val SCHEMA_CONTRACT = "wow_query_schema_contract_id"
        const val CAPABILITY_DIGEST = "wow_query_capability_digest"
        const val REPOSITORY_INDEX = ".wow-query-index-lifecycle-integration-v1"
        val NOW: Instant = Instant.parse("2026-08-08T04:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("lifecycle", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val SOURCE = ElasticsearchPhysicalIndex("wow.lifecycle.order.snapshot-v0001-000001")
        val DESTINATION = ElasticsearchPhysicalIndex("wow.lifecycle.order.snapshot-v0002-000007")
        val VERIFICATION_CONTRACT = ElasticsearchIndexVerificationContract(
            ElasticsearchIndexChecksumAlgorithm.CANONICAL_DOCUMENT_SHA256_V1,
            ElasticsearchIndexProbeSuiteId("integration-probes-v1"),
        )
        val VERIFY_COMMAND = ElasticsearchIndexLifecycleCommandId("verify-canonical-snapshot")
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("lifecycle-order-snapshot-v2-g7"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(7),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("a".repeat(64)),
            SOURCE,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            VERIFICATION_CONTRACT,
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val REBUILD_TARGET = QueryTarget(
            MOCK_AGGREGATE_METADATA.namedAggregate,
            QueryDocumentKind.SNAPSHOT,
        )
        val REBUILD_MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("mock-snapshot-v2-g8"),
            REBUILD_TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(8),
            SchemaContractId("5".repeat(64)),
            ElasticsearchIndexCapabilityDigest("6".repeat(64)),
            ElasticsearchIndexNames.of(
                REBUILD_TARGET,
                ElasticsearchIndexMappingVersion(1),
                ElasticsearchIndexGeneration(1),
            ).physical,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            VERIFICATION_CONTRACT,
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val VERIFY_MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("mock-snapshot-v2-g9"),
            REBUILD_TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(9),
            SchemaContractId("5".repeat(64)),
            ElasticsearchIndexCapabilityDigest("6".repeat(64)),
            REBUILD_MANIFEST.sourcePhysicalIndex,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            VERIFICATION_CONTRACT,
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val EVENT_VERIFY_TARGET = QueryTarget(
            MOCK_AGGREGATE_METADATA.namedAggregate,
            QueryDocumentKind.EVENT_STREAM,
        )
        val EVENT_VERIFY_MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("mock-event-stream-v2-g10"),
            EVENT_VERIFY_TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(10),
            SchemaContractId("7".repeat(64)),
            ElasticsearchIndexCapabilityDigest("8".repeat(64)),
            ElasticsearchIndexNames.of(
                EVENT_VERIFY_TARGET,
                ElasticsearchIndexMappingVersion(1),
                ElasticsearchIndexGeneration(1),
            ).physical,
            ElasticsearchIndexRebuildStrategy.EVENT_STREAM_PAUSE_AND_DRAIN,
            ElasticsearchIndexVerificationContract(
                ElasticsearchIndexChecksumAlgorithm.CANONICAL_EVENT_STREAM_SHA256_V1,
                ElasticsearchIndexProbeSuiteId("integration-event-stream-probes-v1"),
            ),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
        val EVENT_VERIFY_COMMAND = ElasticsearchIndexLifecycleCommandId("verify-canonical-event-stream")
    }
}
