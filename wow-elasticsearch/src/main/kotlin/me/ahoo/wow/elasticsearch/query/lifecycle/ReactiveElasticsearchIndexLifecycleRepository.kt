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

package me.ahoo.wow.elasticsearch.query.lifecycle

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.OpType
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.GetRequest
import co.elastic.clients.elasticsearch.core.GetResponse
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.IndexResponse
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.IndexSettings
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.json.JsonData
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/** Durable state repository. Creation of its system index is deliberately an explicit management operation. */
internal class ReactiveElasticsearchIndexLifecycleRepository(
    private val client: ReactiveElasticsearchClient,
    private val indexName: String = DEFAULT_INDEX_NAME,
    private val codec: ElasticsearchIndexLifecycleStateCodec = ElasticsearchIndexLifecycleStateCodec(),
) : ElasticsearchIndexLifecycleRepository {
    init {
        require(indexName.matches(SYSTEM_INDEX_PATTERN)) { "Elasticsearch lifecycle system index name is invalid." }
    }

    fun ensureIndex(): Mono<Void> = Mono.defer {
        client.indices().create(systemIndexRequest())
            .flatMap { response ->
                if (!response.isExpected(indexName)) {
                    Mono.error(
                        IllegalStateException("Elasticsearch lifecycle system index was not fully acknowledged."),
                    )
                } else {
                    Mono.just(true)
                }
            }
            .switchIfEmpty(
                Mono.error(IllegalStateException("Elasticsearch lifecycle system index returned no response.")),
            )
            .onErrorResume(::isAlreadyExists) { Mono.just(false) }
            .then(Mono.defer(::validateSystemIndex))
    }

    override fun create(state: ElasticsearchIndexMigrationState): Mono<ElasticsearchIndexLifecycleStoredState> =
        Mono.defer {
            client.index(createRequest(state))
                .map { response -> response.stored(state) }
                .onErrorResume(::isConflict) { Mono.empty() }
        }

    override fun load(id: ElasticsearchIndexMigrationId): Mono<ElasticsearchIndexLifecycleStoredState> =
        Mono.defer {
            client.get(
                GetRequest.of { request -> request.index(indexName).id(id.value) },
                Map::class.java,
            )
                .flatMap { response -> if (response.found()) Mono.just(response.decode(id)) else Mono.empty() }
                .onErrorResume(::isNotFound) { Mono.empty() }
        }

    override fun compareAndSet(
        expected: ElasticsearchIndexLifecycleStoredState,
        state: ElasticsearchIndexMigrationState,
    ): Mono<ElasticsearchIndexLifecycleStoredState> = Mono.defer {
        require(expected.state.manifest.id == state.manifest.id) {
            "Elasticsearch migration state id must match repository key."
        }
        val version = expected.version as? ElasticsearchIndexLifecycleRepositoryVersion.Elasticsearch
            ?: error("Elasticsearch repository requires an Elasticsearch storage version.")
        client.index(compareAndSetRequest(state, version))
            .map { response -> response.stored(state) }
            .onErrorResume(::isConflict) { Mono.empty() }
    }

    private fun createRequest(state: ElasticsearchIndexMigrationState): IndexRequest<Map<String, Any>> =
        IndexRequest.of { request ->
            request.index(indexName)
                .id(state.manifest.id.value)
                .opType(OpType.Create)
                .refresh(Refresh.WaitFor)
                .document(state.document())
        }

    private fun compareAndSetRequest(
        state: ElasticsearchIndexMigrationState,
        version: ElasticsearchIndexLifecycleRepositoryVersion.Elasticsearch,
    ): IndexRequest<Map<String, Any>> = IndexRequest.of { request ->
        request.index(indexName)
            .id(state.manifest.id.value)
            .ifSeqNo(version.sequenceNumber)
            .ifPrimaryTerm(version.primaryTerm)
            .refresh(Refresh.WaitFor)
            .document(state.document())
    }

    private fun ElasticsearchIndexMigrationState.document(): Map<String, Any> = linkedMapOf(
        FORMAT_VERSION_FIELD to FORMAT_VERSION,
        MIGRATION_ID_FIELD to manifest.id.value,
        REVISION_FIELD to revision,
        PAYLOAD_FIELD to Base64.getEncoder().encodeToString(codec.encode(this)),
    )

    private fun GetResponse<Map<*, *>>.decode(
        expectedId: ElasticsearchIndexMigrationId,
    ): ElasticsearchIndexLifecycleStoredState {
        requireResponseIdentity(expectedId, index(), id())
        val source = source() ?: corrupt(expectedId, "Elasticsearch lifecycle state document has no source.")
        if (source.keys != DOCUMENT_FIELDS) {
            corrupt(expectedId, "Elasticsearch lifecycle state document has an invalid shape.")
        }
        val format = source[FORMAT_VERSION_FIELD].exactLong(expectedId, FORMAT_VERSION_FIELD)
        if (format != FORMAT_VERSION.toLong()) {
            corrupt(expectedId, "Elasticsearch lifecycle state document has an unsupported format version.")
        }
        if (source[MIGRATION_ID_FIELD] != expectedId.value) {
            corrupt(expectedId, "Elasticsearch lifecycle state document belongs to another migration.")
        }
        val revision = source[REVISION_FIELD].exactLong(expectedId, REVISION_FIELD)
        val payload = source[PAYLOAD_FIELD] as? String
            ?: corrupt(expectedId, "Elasticsearch lifecycle state document payload is invalid.")
        if (payload.length > MAX_BASE64_PAYLOAD_CHARS) {
            corrupt(expectedId, "Elasticsearch lifecycle state document payload exceeds the limit.")
        }
        val decoded = try {
            codec.decode(expectedId, Base64.getDecoder().decode(payload))
        } catch (error: IllegalArgumentException) {
            throw corrupted(expectedId, "Elasticsearch lifecycle state document payload is not base64.", error)
        }
        if (decoded.revision != revision) {
            corrupt(expectedId, "Elasticsearch lifecycle state revision does not match its payload.")
        }
        return ElasticsearchIndexLifecycleStoredState(decoded, repositoryVersion(expectedId, seqNo(), primaryTerm()))
    }

    private fun IndexResponse.stored(
        state: ElasticsearchIndexMigrationState,
    ): ElasticsearchIndexLifecycleStoredState {
        requireResponseIdentity(state.manifest.id, index(), id())
        return ElasticsearchIndexLifecycleStoredState(
            state,
            repositoryVersion(state.manifest.id, seqNo(), primaryTerm()),
        )
    }

    private fun requireResponseIdentity(
        expectedId: ElasticsearchIndexMigrationId,
        actualIndex: String,
        actualId: String,
    ) {
        if (actualIndex != indexName || actualId != expectedId.value) {
            corrupt(expectedId, "Elasticsearch lifecycle repository response identity is invalid.")
        }
    }

    private fun repositoryVersion(
        id: ElasticsearchIndexMigrationId,
        sequenceNumber: Long?,
        primaryTerm: Long?,
    ): ElasticsearchIndexLifecycleRepositoryVersion.Elasticsearch {
        if (sequenceNumber == null || primaryTerm == null) {
            corrupt(id, "Elasticsearch lifecycle repository response has no concurrency token.")
        }
        if (sequenceNumber < 0 || primaryTerm <= 0) {
            corrupt(id, "Elasticsearch lifecycle repository response has no concurrency token.")
        }
        return ElasticsearchIndexLifecycleRepositoryVersion.Elasticsearch(sequenceNumber, primaryTerm)
    }

    private fun validateSystemIndex(): Mono<Void> = client.indices().getMapping(
        GetMappingRequest.of { request -> request.index(indexName) },
    ).flatMap { response ->
        val mappings = response.mappings()
        if (mappings.size != 1 || indexName !in mappings) {
            Mono.error(IllegalStateException("Elasticsearch lifecycle system index mapping is missing."))
        } else {
            val mapping = mappings.getValue(indexName).mapping()
            if (mapping.isSystemIndexMapping()) {
                Mono.empty()
            } else {
                Mono.error(IllegalStateException("Elasticsearch lifecycle system index mapping is incompatible."))
            }
        }
    }

    private fun systemIndexRequest(): CreateIndexRequest = CreateIndexRequest.of { request ->
        request.index(indexName)
            .settings(IndexSettings.of { settings -> settings.hidden(true).numberOfShards("1") })
            .mappings(systemIndexMapping())
    }

    private fun systemIndexMapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.dynamic(DynamicMapping.Strict)
            .meta(REPOSITORY_FORMAT_META, JsonData.of(REPOSITORY_FORMAT))
            .properties(FORMAT_VERSION_FIELD) { property ->
                property.integer { number -> number.index(false).docValues(false) }
            }
            .properties(MIGRATION_ID_FIELD) { property ->
                property.keyword { keyword -> keyword.index(false).docValues(false) }
            }
            .properties(REVISION_FIELD) { property ->
                property.long_ { number -> number.index(false).docValues(false) }
            }
            .properties(PAYLOAD_FIELD) { property ->
                property.keyword { keyword -> keyword.index(false).docValues(false) }
            }
    }

    private fun TypeMapping.isSystemIndexMapping(): Boolean {
        if (dynamic() != DynamicMapping.Strict || properties().keys != DOCUMENT_FIELDS) return false
        val repositoryFormat = meta()[REPOSITORY_FORMAT_META]?.to(String::class.java)
        if (repositoryFormat != REPOSITORY_FORMAT) return false
        return properties().getValue(FORMAT_VERSION_FIELD).isNotIndexedInteger() &&
            properties().getValue(MIGRATION_ID_FIELD).isNotIndexedKeyword() &&
            properties().getValue(REVISION_FIELD).isNotIndexedLong() &&
            properties().getValue(PAYLOAD_FIELD).isNotIndexedKeyword()
    }

    internal companion object {
        const val DEFAULT_INDEX_NAME = ".wow-query-index-lifecycle-v1"
        private const val FORMAT_VERSION = 1
        private const val REPOSITORY_FORMAT = "v1"
        private const val REPOSITORY_FORMAT_META = "wow_query_lifecycle_repository_format"
        private const val FORMAT_VERSION_FIELD = "formatVersion"
        private const val MIGRATION_ID_FIELD = "migrationId"
        private const val REVISION_FIELD = "revision"
        private const val PAYLOAD_FIELD = "payload"
        private const val MAX_BASE64_PAYLOAD_CHARS =
            ((ElasticsearchIndexLifecycleStateCodec.MAX_PAYLOAD_BYTES + 2) / 3) * 4
        private val DOCUMENT_FIELDS = setOf(
            FORMAT_VERSION_FIELD,
            MIGRATION_ID_FIELD,
            REVISION_FIELD,
            PAYLOAD_FIELD,
        )
        private val SYSTEM_INDEX_PATTERN = Regex("\\.[a-z0-9][a-z0-9._-]{0,253}")
    }
}

private fun CreateIndexResponse.isExpected(indexName: String): Boolean =
    acknowledged() && shardsAcknowledged() && index() == indexName

private fun co.elastic.clients.elasticsearch._types.mapping.Property.isNotIndexedInteger(): Boolean =
    isInteger && integer().index() == false && integer().docValues() == false

private fun co.elastic.clients.elasticsearch._types.mapping.Property.isNotIndexedKeyword(): Boolean =
    isKeyword && keyword().index() == false && keyword().docValues() == false

private fun co.elastic.clients.elasticsearch._types.mapping.Property.isNotIndexedLong(): Boolean =
    isLong && long_().index() == false && long_().docValues() == false

private fun IndexMappingRecord.mapping(): TypeMapping = mappings() ?: item()
    ?: error("Elasticsearch lifecycle index mapping response has no mapping.")

private fun Any?.exactLong(
    id: ElasticsearchIndexMigrationId,
    field: String,
): Long = try {
    when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        is BigInteger -> longValueExact()
        is BigDecimal -> longValueExact()
        else -> corrupt(id, "Elasticsearch lifecycle state field [$field] is not an exact integer.")
    }
} catch (error: ArithmeticException) {
    throw corrupted(id, "Elasticsearch lifecycle state field [$field] is not an exact long.", error)
}

private fun corrupted(
    id: ElasticsearchIndexMigrationId,
    message: String,
    cause: Throwable?,
) = ElasticsearchIndexLifecycleException(
    ElasticsearchIndexLifecycleErrorCode.REPOSITORY_CORRUPTED,
    id,
    message,
    cause,
)

private fun corrupt(id: ElasticsearchIndexMigrationId, message: String): Nothing =
    throw corrupted(id, message, null)

private fun isConflict(error: Throwable): Boolean = when (error) {
    is ElasticsearchException -> error.status() == 409
    is RestStatusException -> error.status == 409
    else -> false
}

private fun isNotFound(error: Throwable): Boolean = when (error) {
    is ElasticsearchException -> error.status() == 404
    is RestStatusException -> error.status == 404
    else -> false
}

private fun isAlreadyExists(error: Throwable): Boolean = when (error) {
    is ElasticsearchException ->
        error.status() == 400 && error.response().error().type() == "resource_already_exists_exception"

    is RestStatusException -> error.status == 400 && error.message?.contains("resource_already_exists_exception") == true
    else -> false
}
