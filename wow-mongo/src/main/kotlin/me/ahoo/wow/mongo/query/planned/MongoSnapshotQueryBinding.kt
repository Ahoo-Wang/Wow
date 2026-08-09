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

package me.ahoo.wow.mongo.query.planned

import com.mongodb.MongoNamespace
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.BackendStreamSupport
import me.ahoo.wow.query.backend.ExperimentalQueryBackendApi
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

@ExperimentalQueryBackendApi
enum class MongoValueEncoding {
    DEFAULT,
    EPOCH_MILLIS,
    DECIMAL128,
}

@ExperimentalQueryBackendApi
enum class MongoCollationMode {
    SIMPLE_BINARY,
}

@ExperimentalQueryBackendApi
data class MongoTextSearchBinding(
    val scope: SearchScopeId,
    val indexName: String,
) {
    init {
        require(indexName.isNotBlank()) { "Mongo text index name must not be blank." }
        require(indexName.none(Char::isISOControl)) { "Mongo text index name must not contain control characters." }
    }
}

@ExperimentalQueryBackendApi
class MongoQueryBackendNotReadyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@ExperimentalQueryBackendApi
fun MongoSnapshotQueryBinding.toContribution(
    collection: MongoCollection<Document>,
): RecordQueryBackendContribution = prepared.toContribution(collection)

@ExperimentalQueryBackendApi
fun MongoEventStreamQueryBinding.toContribution(
    collection: MongoCollection<Document>,
): RecordQueryBackendContribution = prepared.toContribution(collection)

private fun MongoPreparedQueryBinding.toContribution(
    collection: MongoCollection<Document>,
): RecordQueryBackendContribution {
    requireCollection(collection)
    require(textSearch == null) {
        "Mongo text search requires prepareContribution() to attest the configured text index."
    }
    return createContribution(collection)
}

@ExperimentalQueryBackendApi
fun MongoSnapshotQueryBinding.prepareContribution(
    collection: MongoCollection<Document>,
): Mono<RecordQueryBackendContribution> = prepared.prepareContribution(collection)

@ExperimentalQueryBackendApi
fun MongoEventStreamQueryBinding.prepareContribution(
    collection: MongoCollection<Document>,
): Mono<RecordQueryBackendContribution> = prepared.prepareContribution(collection)

private fun MongoPreparedQueryBinding.prepareContribution(
    collection: MongoCollection<Document>,
): Mono<RecordQueryBackendContribution> {
    requireCollection(collection)
    if (textSearch == null) {
        return Mono.fromSupplier { createContribution(collection) }
    }
    return Flux.from(collection.listIndexes()).collectList()
        .onErrorMap { error ->
            MongoQueryBackendNotReadyException("Mongo text index readiness could not be inspected.", error)
        }.map { indexes ->
            attestTextIndexReadiness(indexes)
            createContribution(collection)
        }
}

private fun MongoPreparedQueryBinding.createContribution(
    collection: MongoCollection<Document>,
): RecordQueryBackendContribution {
    requireCollection(collection)
    val analyticsBackend = if (documentKind == QueryDocumentKind.SNAPSHOT) {
        MongoAnalyticsQueryBackend(collection, this)
    } else {
        null
    }
    return RecordQueryBackendContribution(
        schema = schema,
        backendId = backendId,
        supportedOperations = setOf(
            QueryOperation.SINGLE,
            QueryOperation.STREAM,
            QueryOperation.PAGE,
            QueryOperation.COUNT,
            if (analyticsBackend != null) QueryOperation.ANALYZE else null,
        ).filterNotNull().toSet(),
        streamSupport = BackendStreamSupport.BOUNDED_ONLY,
        semanticTiers = buildSet {
            add(SemanticTier.PORTABLE)
            if (textSearch != null) {
                add(SemanticTier.SEARCH)
            }
        },
        fieldCapabilities = fields.mapValues { (_, field) -> field.capabilities },
        searchScopes = textSearch?.let { search -> setOf(search.scope) }.orEmpty(),
        backend = MongoRecordQueryBackend(collection, this),
        analyticsBackend = analyticsBackend,
        mappingGenerationDigest = capabilityDigest,
    )
}

private fun MongoPreparedQueryBinding.requireCollection(collection: MongoCollection<Document>) {
    require(collection.namespace == namespace) {
        "Mongo query binding namespace[$namespace] does not match collection[${collection.namespace}]."
    }
}

@ExperimentalQueryBackendApi
class MongoFieldBinding(
    val path: String,
    capabilities: Set<FieldCapability>,
    val valueEncoding: MongoValueEncoding = MongoValueEncoding.DEFAULT,
) {
    val capabilities: Set<FieldCapability> = Collections.unmodifiableSet(LinkedHashSet(capabilities))

    init {
        require(path.isNotBlank()) { "Mongo field path must not be blank." }
        val segments = path.split('.')
        require(segments.none(String::isBlank)) { "Mongo field path segments must not be blank." }
        require(segments.none { segment -> segment.startsWith('$') }) {
            "Mongo field path segments must not start with '$'."
        }
        require(path.none(Char::isISOControl)) { "Mongo field path must not contain control characters." }
    }
}

@ExperimentalQueryBackendApi
class MongoSnapshotQueryBinding(
    val schema: QueryDocumentSchema,
    val namespace: MongoNamespace,
    fields: Map<QueryFieldId, MongoFieldBinding>,
    val backendId: BackendId = BackendId("mongo"),
    val collationMode: MongoCollationMode = MongoCollationMode.SIMPLE_BINARY,
    val textSearch: MongoTextSearchBinding? = null,
) {
    internal val prepared = prepareMongoBinding(
        schema,
        namespace,
        fields,
        backendId,
        collationMode,
        textSearch,
        QueryDocumentKind.SNAPSHOT,
        MessageRecords.AGGREGATE_ID,
        SNAPSHOT_SYSTEM_PATHS,
    )
    val fields: Map<QueryFieldId, MongoFieldBinding> = prepared.fields

    companion object {
        fun frameworkFields(schema: QueryDocumentSchema, namespace: MongoNamespace): MongoSnapshotQueryBinding {
            val bindings = linkedMapOf<QueryFieldId, MongoFieldBinding>()
            fun bind(kind: SystemFieldKind, path: String) {
                val id = QueryFieldId.System(kind)
                schema.fields[id]?.let { field -> bindings[id] = MongoFieldBinding(path, field.capabilities) }
            }
            SNAPSHOT_SYSTEM_PATHS.forEach(::bind)
            return MongoSnapshotQueryBinding(schema, namespace, bindings)
        }
    }
}

@ExperimentalQueryBackendApi
class MongoEventStreamQueryBinding(
    val schema: QueryDocumentSchema,
    val namespace: MongoNamespace,
    fields: Map<QueryFieldId, MongoFieldBinding>,
    val backendId: BackendId = BackendId("mongo"),
    val collationMode: MongoCollationMode = MongoCollationMode.SIMPLE_BINARY,
    val textSearch: MongoTextSearchBinding? = null,
) {
    internal val prepared = prepareMongoBinding(
        schema,
        namespace,
        fields,
        backendId,
        collationMode,
        textSearch,
        QueryDocumentKind.EVENT_STREAM,
        MessageRecords.ID,
        EVENT_STREAM_SYSTEM_PATHS,
    )
    val fields: Map<QueryFieldId, MongoFieldBinding> = prepared.fields

    companion object {
        fun frameworkFields(schema: QueryDocumentSchema, namespace: MongoNamespace): MongoEventStreamQueryBinding {
            val bindings = linkedMapOf<QueryFieldId, MongoFieldBinding>()
            EVENT_STREAM_SYSTEM_PATHS.forEach { (kind, path) ->
                val id = QueryFieldId.System(kind)
                schema.fields[id]?.let { field -> bindings[id] = MongoFieldBinding(path, field.capabilities) }
            }
            return MongoEventStreamQueryBinding(schema, namespace, bindings)
        }
    }
}

internal class MongoPreparedQueryBinding(
    val schema: QueryDocumentSchema,
    val namespace: MongoNamespace,
    val fields: Map<QueryFieldId, MongoFieldBinding>,
    val backendId: BackendId,
    val collationMode: MongoCollationMode,
    val textSearch: MongoTextSearchBinding?,
    val identityOutputField: String,
    val documentKind: QueryDocumentKind,
) {
    val capabilityDigest: String = MongoCapabilityDigestEncoder.encode(this)
}

private fun prepareMongoBinding(
    schema: QueryDocumentSchema,
    namespace: MongoNamespace,
    fields: Map<QueryFieldId, MongoFieldBinding>,
    backendId: BackendId,
    collationMode: MongoCollationMode,
    textSearch: MongoTextSearchBinding?,
    documentKind: QueryDocumentKind,
    identityOutputField: String,
    systemPaths: Map<SystemFieldKind, String>,
): MongoPreparedQueryBinding {
    require(schema.target.documentKind == documentKind) {
        "Mongo query binding requires a $documentKind target."
    }
    val copy = LinkedHashMap<QueryFieldId, MongoFieldBinding>(fields.size)
    fields.forEach { (field, binding) ->
        val fieldSchema = requireNotNull(schema.fields[field]) {
            "Mongo field $field is not declared by the logical schema."
        }
        require(fieldSchema.capabilities.containsAll(binding.capabilities)) {
            "Mongo field $field overclaims the logical schema contract."
        }
        requireValidEncoding(fieldSchema.type, binding.valueEncoding)
        if (field is QueryFieldId.Path) {
            require(field.segments.joinToString(".") == binding.path) {
                "Mongo record materialization requires logical and physical user paths to match."
            }
            require(binding.path !in systemPaths.values) {
                "Mongo user field $field must not collide with a framework system field."
            }
        }
        copy[field] = binding
    }
    val immutableFields = Collections.unmodifiableMap(copy)
    require(immutableFields.keys == schema.fields.keys) {
        "Mongo query binding must cover every field in the logical schema."
    }
    systemPaths.forEach { (kind, expectedPath) ->
        val id = QueryFieldId.System(kind)
        if (id in schema.fields && id in immutableFields) {
            require(immutableFields[id]?.path == expectedPath) {
                "Mongo $documentKind system field $kind must bind to $expectedPath."
            }
        }
    }
    require(immutableFields[QueryFieldId.System(SystemFieldKind.IDENTITY)]?.path == Documents.ID_FIELD) {
        "Mongo $documentKind identity must bind to ${Documents.ID_FIELD}."
    }
    if (documentKind == QueryDocumentKind.EVENT_STREAM) {
        require(QueryFieldId.System(SystemFieldKind.DELETED) !in schema.fields) {
            "Mongo EventStream schema must not declare snapshot deletion semantics."
        }
    }
    textSearch?.let { search -> validateTextSearch(schema, immutableFields, search) }
    return MongoPreparedQueryBinding(
        schema,
        namespace,
        immutableFields,
        backendId,
        collationMode,
        textSearch,
        identityOutputField,
        documentKind,
    )
}

private fun validateTextSearch(
    schema: QueryDocumentSchema,
    fields: Map<QueryFieldId, MongoFieldBinding>,
    search: MongoTextSearchBinding,
) {
    val definition = requireNotNull(schema.searchScopes[search.scope]) {
        "Mongo text search scope ${search.scope} is not declared by the logical schema."
    }
    require(definition.owner == null) {
        "Mongo text search supports root document scopes only."
    }
    definition.fields.forEach { field ->
        require(FieldCapability.FULL_TEXT in requireNotNull(fields[field]).capabilities) {
            "Mongo text search field $field must bind FULL_TEXT capability."
        }
    }
}

private object MongoCapabilityDigestEncoder {
    fun encode(binding: MongoPreparedQueryBinding): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeUTF("wow.mongo.query.binding.v1")
                output.writeUTF(binding.schema.contractId.value)
                output.writeUTF(binding.namespace.databaseName)
                output.writeUTF(binding.namespace.collectionName)
                output.writeUTF(binding.documentKind.name)
                output.writeUTF(binding.backendId.value)
                output.writeUTF(binding.collationMode.name)
                output.writeInt(binding.fields.size)
                binding.fields.entries.sortedBy { entry -> entry.key.stableKey() }.forEach { (field, physical) ->
                    output.writeUTF(field.stableKey())
                    output.writeUTF(physical.path)
                    output.writeUTF(physical.valueEncoding.name)
                    output.writeInt(physical.capabilities.size)
                    physical.capabilities.sortedBy(FieldCapability::name).forEach { capability ->
                        output.writeUTF(capability.name)
                    }
                }
                output.writeBoolean(binding.textSearch != null)
                binding.textSearch?.let { search ->
                    output.writeUTF(search.scope.value)
                    output.writeUTF(search.indexName)
                }
            }
            buffer.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun QueryFieldId.stableKey(): String = when (this) {
        is QueryFieldId.Path -> "path:${segments.joinToString("\u0000")}"
        is QueryFieldId.System -> "system:${kind.name}"
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun MongoPreparedQueryBinding.attestTextIndexReadiness(indexes: List<Document>) {
    val search = requireNotNull(textSearch) { "Mongo text search is not configured." }
    val definition = requireNotNull(schema.searchScopes[search.scope])
    val expectedFields = definition.fields.map { field -> requireNotNull(fields[field]).path }.toSet()
    val candidates = indexes.filter { index -> index.getString("name") == search.indexName }
    if (candidates.size != 1 || !candidates.single().isExactTextIndex(expectedFields)) {
        throw MongoQueryBackendNotReadyException(
            "Mongo text index ${search.indexName} is missing or does not match scope ${search.scope}.",
        )
    }
}

internal fun MongoSnapshotQueryBinding.attestTextIndexReadiness(indexes: List<Document>) {
    prepared.attestTextIndexReadiness(indexes)
}

internal fun MongoEventStreamQueryBinding.attestTextIndexReadiness(indexes: List<Document>) {
    prepared.attestTextIndexReadiness(indexes)
}

private val SNAPSHOT_SYSTEM_PATHS = linkedMapOf(
    SystemFieldKind.IDENTITY to Documents.ID_FIELD,
    SystemFieldKind.AGGREGATE_ID to Documents.ID_FIELD,
    SystemFieldKind.TENANT_ID to MessageRecords.TENANT_ID,
    SystemFieldKind.OWNER_ID to MessageRecords.OWNER_ID,
    SystemFieldKind.SPACE_ID to MessageRecords.SPACE_ID,
    SystemFieldKind.DELETED to StateAggregateRecords.DELETED,
)

private val EVENT_STREAM_SYSTEM_PATHS = linkedMapOf(
    SystemFieldKind.IDENTITY to Documents.ID_FIELD,
    SystemFieldKind.AGGREGATE_ID to MessageRecords.AGGREGATE_ID,
    SystemFieldKind.TENANT_ID to MessageRecords.TENANT_ID,
    SystemFieldKind.OWNER_ID to MessageRecords.OWNER_ID,
    SystemFieldKind.SPACE_ID to MessageRecords.SPACE_ID,
)

private fun Document.isExactTextIndex(expectedFields: Set<String>): Boolean {
    val key = this["key"] as? Document ?: return false
    val weights = this["weights"] as? Document ?: return false
    val collation = this["collation"] as? Document
    return key.keys == setOf("_fts", "_ftsx") &&
        key["_fts"] == "text" &&
        (key["_ftsx"] as? Number)?.toInt() == 1 &&
        weights.keys == expectedFields &&
        (collation == null || collation.getString("locale") == "simple")
}

private fun requireValidEncoding(type: LogicalFieldType, encoding: MongoValueEncoding) {
    val scalarType = generateSequence(type) { current ->
        (current as? LogicalFieldType.Array)?.elementType
    }.last()
    val expected = when (scalarType) {
        LogicalFieldType.Instant -> MongoValueEncoding.EPOCH_MILLIS
        LogicalFieldType.Decimal -> MongoValueEncoding.DECIMAL128
        else -> MongoValueEncoding.DEFAULT
    }
    require(encoding == expected) {
        "Mongo value encoding $encoding does not match logical field type $type; expected $expected."
    }
}
