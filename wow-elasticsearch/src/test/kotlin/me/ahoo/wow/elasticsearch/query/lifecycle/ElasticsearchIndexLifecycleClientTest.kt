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

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.cluster.PutComponentTemplateRequest
import co.elastic.clients.elasticsearch.cluster.PutComponentTemplateResponse
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.GetAliasRequest
import co.elastic.clients.elasticsearch.indices.GetAliasResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateResponse
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.json.JsonData
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClusterClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Consumer

class ElasticsearchIndexLifecycleClientTest {
    @Test
    fun `versioned templates should bind metadata component composition and exact generation pattern`() {
        val template = ElasticsearchVersionedIndexTemplate(MANIFEST, mapping())
        val component = template.componentRequest()
        val index = template.indexTemplateRequest()

        component.name().assert().isEqualTo("wow.sales.order.snapshot-query-mapping-v0002")
        component.version().assert().isEqualTo(2L)
        component.template().mappings()!!.meta().string(MAPPING_VERSION).assert().isEqualTo("v0002")
        index.name().assert().isEqualTo("wow.sales.order.snapshot-query-template-v0002")
        index.indexPatterns().assert().containsExactly("wow.sales.order.snapshot-v0002-*")
        index.composedOf().assert().containsExactly(component.name())
        index.allowAutoCreate().assert().isFalse()
        index.meta().string(CAPABILITY_DIGEST).assert().isEqualTo(MANIFEST.capabilityDigest.value)
    }

    @Test
    fun `template should reject metadata that does not attest the manifest`() {
        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH) {
            ElasticsearchVersionedIndexTemplate(
                MANIFEST,
                mapping(capabilityDigest = "b".repeat(64)),
            )
        }
    }

    @Test
    fun `create should ensure both templates and reuse an exactly attested destination`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val cluster = mockk<ReactiveElasticsearchClusterClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.cluster() } returns cluster
        every { client.indices() } returns indices
        every { cluster.putComponentTemplate(any<PutComponentTemplateRequest>()) } returns Mono.just(
            PutComponentTemplateResponse.of { response -> response.acknowledged(true) },
        )
        every { indices.putIndexTemplate(any<PutIndexTemplateRequest>()) } returns Mono.just(
            PutIndexTemplateResponse.of { response -> response.acknowledged(true) },
        )
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(mappingResponse())
        val admin = ReactiveElasticsearchIndexAdminClient(client, CLOCK)

        val attestation = admin.create(MANIFEST, ElasticsearchVersionedIndexTemplate(MANIFEST, mapping())).block()!!

        attestation.assert().isEqualTo(MANIFEST.destinationAttestation)
        verify(exactly = 1) { cluster.putComponentTemplate(any<PutComponentTemplateRequest>()) }
        verify(exactly = 1) { indices.putIndexTemplate(any<PutIndexTemplateRequest>()) }
        verify(exactly = 0) { indices.create(any<CreateIndexRequest>()) }
    }

    @Test
    fun `create should explicitly create a missing generation and attest the applied template`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val cluster = mockk<ReactiveElasticsearchClusterClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.cluster() } returns cluster
        every { client.indices() } returns indices
        every { cluster.putComponentTemplate(any<PutComponentTemplateRequest>()) } returns Mono.just(
            PutComponentTemplateResponse.of { response -> response.acknowledged(true) },
        )
        every { indices.putIndexTemplate(any<PutIndexTemplateRequest>()) } returns Mono.just(
            PutIndexTemplateResponse.of { response -> response.acknowledged(true) },
        )
        every { indices.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(GetMappingResponse.of { response -> response.mappings(emptyMap()) }),
            Mono.just(mappingResponse()),
        )
        every { indices.create(any<CreateIndexRequest>()) } returns Mono.just(
            CreateIndexResponse.of { response ->
                response.index(DESTINATION.value).acknowledged(true).shardsAcknowledged(true)
            },
        )
        val admin = ReactiveElasticsearchIndexAdminClient(client, CLOCK)

        admin.create(MANIFEST, ElasticsearchVersionedIndexTemplate(MANIFEST, mapping())).block()!!

        verify(exactly = 1) {
            indices.create(match<CreateIndexRequest> { request -> request.index() == DESTINATION.value })
        }
    }

    @Test
    fun `cutover should remove the exact source with must-exist and add one write alias atomically`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.getAlias(any<GetAliasRequest>()) } returnsMany listOf(
            Mono.just(aliasResponse(SOURCE)),
            Mono.just(aliasResponse(DESTINATION)),
        )
        val captured = slot<UpdateAliasesRequest>()
        every { indices.updateAliases(capture(captured)) } returns Mono.just(
            UpdateAliasesResponse.of { response -> response.acknowledged(true) },
        )
        val admin = ReactiveElasticsearchIndexAdminClient(client, CLOCK)

        val transition = admin.compareAndSetAlias(MANIFEST, SOURCE, DESTINATION).block()!!

        transition.previous.assert().isEqualTo(SOURCE)
        transition.current.assert().isEqualTo(DESTINATION)
        captured.captured.actions().assert().hasSize(2)
        captured.captured.actions()[0].remove().mustExist().assert().isTrue()
        captured.captured.actions()[0].remove().index().assert().isEqualTo(SOURCE.value)
        captured.captured.actions()[1].add().isWriteIndex().assert().isTrue()
        captured.captured.actions()[1].add().index().assert().isEqualTo(DESTINATION.value)
    }

    @Test
    fun `cutover should fail closed when the expected source alias is absent`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.getAlias(any<GetAliasRequest>()) } returns Mono.just(
            GetAliasResponse.of { response -> response.aliases(emptyMap()) },
        )
        val admin = ReactiveElasticsearchIndexAdminClient(client, CLOCK)

        assertLifecycle(ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT) {
            admin.compareAndSetAlias(MANIFEST, SOURCE, DESTINATION).block()
        }
        verify(exactly = 0) { indices.updateAliases(any<UpdateAliasesRequest>()) }
    }

    private fun mapping(
        capabilityDigest: String = MANIFEST.capabilityDigest.value,
    ): TypeMapping = TypeMapping.of { mapping ->
        mapping.meta(MAPPING_VERSION, JsonData.of(MANIFEST.mappingVersion.tag))
            .meta(DOCUMENT_KIND, JsonData.of(MANIFEST.target.documentKind.name))
            .meta(SCHEMA_CONTRACT, JsonData.of(MANIFEST.schemaContractId.value))
            .meta(CAPABILITY_DIGEST, JsonData.of(capabilityDigest))
    }

    private fun mappingResponse(): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(
            DESTINATION.value,
            IndexMappingRecord.of { record -> record.mappings(mapping()) },
        )
    }

    private fun aliasResponse(physical: ElasticsearchPhysicalIndex): GetAliasResponse =
        GetAliasResponse.of { response ->
            response.aliases(
                physical.value,
                IndexAliases.of { index ->
                    index.aliases(MANIFEST.names.alias.value) { alias -> alias.isWriteIndex(true) }
                },
            )
        }

    private fun assertLifecycle(
        code: ElasticsearchIndexLifecycleErrorCode,
        action: () -> Unit,
    ) {
        assertThrownBy<ElasticsearchIndexLifecycleException>(action).satisfies(
            Consumer { error -> error.code.assert().isEqualTo(code) },
        )
    }

    private fun Map<String, JsonData>.string(key: String): String = getValue(key).to(String::class.java)

    private companion object {
        const val MAPPING_VERSION = "wow_query_mapping_version"
        const val DOCUMENT_KIND = "wow_query_document_kind"
        const val SCHEMA_CONTRACT = "wow_query_schema_contract_id"
        const val CAPABILITY_DIGEST = "wow_query_capability_digest"
        val NOW: Instant = Instant.parse("2026-08-08T03:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TARGET = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        val SOURCE = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0001-000001")
        val DESTINATION = ElasticsearchPhysicalIndex("wow.sales.order.snapshot-v0002-000007")
        val MANIFEST = ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId("sales-order-snapshot-v2-g7"),
            TARGET,
            ElasticsearchIndexMappingVersion(2),
            ElasticsearchIndexGeneration(7),
            SchemaContractId("1".repeat(64)),
            ElasticsearchIndexCapabilityDigest("a".repeat(64)),
            SOURCE,
            ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM,
            testVerificationContract(),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
        )
    }
}
