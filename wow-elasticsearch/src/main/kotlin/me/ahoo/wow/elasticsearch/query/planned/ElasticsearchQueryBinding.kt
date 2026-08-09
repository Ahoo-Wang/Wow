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

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.mapping.DocValuesPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.NumberPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.PropertyBase
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.query.backend.BackendId
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
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

@ExperimentalQueryBackendApi
enum class ElasticsearchValueEncoding {
    DEFAULT,
    EPOCH_MILLIS,
}

@ExperimentalQueryBackendApi
data class ElasticsearchKeywordReadiness(
    val maximumCharacters: Int,
    val maximumUtf8Bytes: Int,
    val historicalValuesAudited: Boolean,
    val writeConstraintEnforced: Boolean,
) {
    init {
        require(maximumCharacters > 0) { "Elasticsearch keyword maximum characters must be positive." }
        require(maximumUtf8Bytes > 0) { "Elasticsearch keyword maximum UTF-8 bytes must be positive." }
        require(historicalValuesAudited) {
            "Elasticsearch keyword capability requires a completed historical value audit."
        }
        require(writeConstraintEnforced) {
            "Elasticsearch keyword capability requires an enforced write-side length constraint."
        }
    }
}

@ExperimentalQueryBackendApi
data class ElasticsearchGroupReadiness(
    val historicalValuesAudited: Boolean,
) {
    init {
        require(historicalValuesAudited) {
            "Elasticsearch group capability requires a historical logical-value audit."
        }
    }
}

@ExperimentalQueryBackendApi
class ElasticsearchFieldBinding(
    val sourceField: String,
    capabilities: Set<FieldCapability>,
    val exactField: String? = null,
    val presenceField: String? = null,
    val rangeField: String? = null,
    val searchField: String? = null,
    val searchAnalyzer: String? = null,
    val literalField: String? = null,
    val sortField: String? = null,
    val groupField: String? = null,
    val groupReadiness: ElasticsearchGroupReadiness? = null,
    val nestedPath: String? = null,
    val valueEncoding: ElasticsearchValueEncoding = ElasticsearchValueEncoding.DEFAULT,
    val keywordReadiness: ElasticsearchKeywordReadiness? = null,
) {
    val capabilities: Set<FieldCapability> = Collections.unmodifiableSet(
        LinkedHashSet(capabilities.sortedBy(FieldCapability::name)),
    )

    init {
        physicalFields().forEach(::requirePhysicalPath)
        requireRole(FieldCapability.EXACT, exactField)
        requireRole(FieldCapability.PRESENCE, presenceField)
        requireRole(FieldCapability.RANGE, rangeField)
        requireRole(FieldCapability.FULL_TEXT, searchField)
        require(FieldCapability.FULL_TEXT !in capabilities || !searchAnalyzer.isNullOrBlank()) {
            "Elasticsearch FULL_TEXT capability requires an explicit search analyzer."
        }
        requireRole(FieldCapability.LITERAL_PATTERN, literalField)
        requireRole(FieldCapability.SORTABLE, sortField)
        requireRole(FieldCapability.AGGREGATABLE, groupField)
        require(FieldCapability.AGGREGATABLE !in capabilities || groupReadiness != null) {
            "Elasticsearch AGGREGATABLE capability requires explicit group readiness."
        }
        requireRole(FieldCapability.ELEMENT_MATCH, nestedPath)
    }

    private fun requireRole(capability: FieldCapability, field: String?) {
        require(capability !in capabilities || field != null) {
            "Elasticsearch capability $capability requires an explicit physical field role."
        }
    }

    private fun physicalFields(): List<String> = listOfNotNull(
        sourceField,
        exactField,
        presenceField,
        rangeField,
        searchField,
        literalField,
        sortField,
        groupField,
        nestedPath,
    )
}

@ExperimentalQueryBackendApi
class ElasticsearchSearchScopeBinding(
    val scope: SearchScopeId,
    fields: Map<QueryFieldId.Path, String>,
) {
    val fields: Map<QueryFieldId.Path, String>

    init {
        require(fields.isNotEmpty()) { "Elasticsearch search scope fields must not be empty." }
        val copy = LinkedHashMap<QueryFieldId.Path, String>(fields.size)
        fields.entries.sortedBy { entry -> entry.key.toString() }.forEach { entry ->
            requirePhysicalPath(entry.value)
            copy[entry.key] = entry.value
        }
        this.fields = Collections.unmodifiableMap(copy)
    }
}

@ExperimentalQueryBackendApi
class ElasticsearchQueryBackendNotReadyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@ExperimentalQueryBackendApi
class ElasticsearchSnapshotQueryBinding(
    val schema: QueryDocumentSchema,
    val indexName: String,
    val mappingVersion: String,
    fields: Map<QueryFieldId, ElasticsearchFieldBinding>,
    searchScopes: Iterable<ElasticsearchSearchScopeBinding> = emptyList(),
    val backendId: BackendId = BackendId("elasticsearch"),
) {
    internal val prepared = prepareBinding(schema, indexName, mappingVersion, fields, searchScopes, backendId)
    val fields: Map<QueryFieldId, ElasticsearchFieldBinding> = prepared.fields
    val searchScopes: Map<SearchScopeId, ElasticsearchSearchScopeBinding> = prepared.searchScopes
}

@ExperimentalQueryBackendApi
fun ElasticsearchSnapshotQueryBinding.prepareContribution(
    client: ReactiveElasticsearchClient,
): Mono<RecordQueryBackendContribution> = client.indices().getMapping { request -> request.index(indexName) }
    .switchIfEmpty(
        Mono.error(
            ElasticsearchQueryBackendNotReadyException(
                "Elasticsearch index [$indexName] returned no mapping response.",
            ),
        ),
    ).onErrorMap { error ->
        if (error is ElasticsearchQueryBackendNotReadyException) {
            error
        } else {
            ElasticsearchQueryBackendNotReadyException(
                "Elasticsearch mapping readiness for index [$indexName] could not be inspected.",
                error,
            )
        }
    }.map { response ->
        prepared.attestReadiness(response.mappings().mapValues { (_, record) -> record.mappings() })
        prepared.createContribution(client)
    }

internal class ElasticsearchPreparedQueryBinding(
    val schema: QueryDocumentSchema,
    val indexName: String,
    val mappingVersion: String,
    val fields: Map<QueryFieldId, ElasticsearchFieldBinding>,
    val searchScopes: Map<SearchScopeId, ElasticsearchSearchScopeBinding>,
    val backendId: BackendId,
) {
    val capabilityDigest: String = ElasticsearchCapabilityDigestEncoder.encode(this)

    fun attestReadiness(mappings: Map<String, TypeMapping>) {
        if (mappings.isEmpty()) {
            notReady("Elasticsearch index [$indexName] has no concrete mapping.")
        }
        mappings.entries.sortedBy(Map.Entry<String, TypeMapping>::key).forEach { (index, mapping) ->
            attestMapping(index, mapping)
        }
    }

    fun createContribution(client: ReactiveElasticsearchClient): RecordQueryBackendContribution =
        RecordQueryBackendContribution(
            schema,
            backendId,
            setOf(
                QueryOperation.SINGLE,
                QueryOperation.STREAM,
                QueryOperation.PAGE,
                QueryOperation.COUNT,
                QueryOperation.ANALYZE,
            ),
            me.ahoo.wow.query.backend.BackendStreamSupport.BOUNDED_ONLY,
            buildSet {
                add(SemanticTier.PORTABLE)
                if (searchScopes.isNotEmpty()) add(SemanticTier.SEARCH)
            },
            fields.mapValues { (_, field) -> field.capabilities },
            searchScopes.keys,
            ElasticsearchSnapshotRecordQueryBackend(client, this),
            ElasticsearchAnalyticsQueryBackend(client, this),
            capabilityDigest,
        )

    private fun attestMapping(index: String, mapping: TypeMapping) {
        val actualVersion = mapping.requiredMeta(index, ELASTICSEARCH_QUERY_MAPPING_VERSION_META)
        if (actualVersion != mappingVersion) {
            notReady("Elasticsearch index [$index] mapping version [$actualVersion] does not match [$mappingVersion].")
        }
        requireMeta(index, mapping, ELASTICSEARCH_QUERY_DOCUMENT_KIND_META, schema.target.documentKind.name)
        requireMeta(index, mapping, ELASTICSEARCH_QUERY_SCHEMA_CONTRACT_META, schema.contractId.value)
        requireMeta(index, mapping, ELASTICSEARCH_QUERY_CAPABILITY_DIGEST_META, capabilityDigest)
        val properties = flattenProperties(mapping.properties())
        fields.forEach { (field, binding) -> attestField(index, field, binding, properties) }
        searchScopes.values.forEach { scope ->
            scope.fields.forEach { (field, searchField) ->
                requireSearchField(
                    index,
                    searchField,
                    fields.getValue(field).searchAnalyzer,
                    properties,
                )
            }
        }
    }

    private fun attestField(
        index: String,
        field: QueryFieldId,
        binding: ElasticsearchFieldBinding,
        properties: Map<String, Property>,
    ) {
        requireSourceField(index, binding.sourceField, properties)
        binding.exactField?.let { physical ->
            if (physical != ES_ID_FIELD) {
                requireExactField(index, field, binding, physical, properties)
            }
        }
        binding.presenceField?.let { physical ->
            requirePresenceField(index, physical, properties)
        }
        binding.rangeField?.let { physical -> requireRangeField(index, field, physical, properties) }
        binding.searchField?.let { physical ->
            requireSearchField(index, physical, binding.searchAnalyzer, properties)
        }
        binding.literalField?.let { physical -> requireLiteralField(index, field, binding, physical, properties) }
        binding.sortField?.let { physical ->
            requireOrderedBucketField(index, field, binding, physical, properties)
        }
        binding.groupField?.let { physical ->
            requireOrderedBucketField(index, field, binding, physical, properties)
            requireNullBucketField(index, physical, properties)
        }
        binding.nestedPath?.let { physical ->
            val property = requireProperty(index, physical, properties)
            if (!property.isNested) notReady("Elasticsearch field [$physical] in index [$index] is not nested.")
        }
    }

    private fun requireSourceField(index: String, physical: String, properties: Map<String, Property>) {
        if (physical != ES_ID_FIELD) requireProperty(index, physical, properties)
    }

    private fun requireExactField(
        index: String,
        field: QueryFieldId,
        binding: ElasticsearchFieldBinding,
        physical: String,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        when (schema.fields.getValue(field).type.operandType()) {
            LogicalFieldType.Text -> {
                if (!property.isKeyword && !property.isConstantKeyword) {
                    notReady("Elasticsearch exact field [$physical] in index [$index] is not keyword.")
                }
                requireKeywordCompleteness(index, physical, property, binding)
            }

            LogicalFieldType.Boolean -> if (!property.isBoolean) typeMismatch(index, physical)
            LogicalFieldType.Int64 -> if (!property.isIntegralNumber()) typeMismatch(index, physical)
            LogicalFieldType.Instant -> if (!property.isLong || binding.valueEncoding != ElasticsearchValueEncoding.EPOCH_MILLIS) {
                typeMismatch(index, physical)
            }

            LogicalFieldType.Decimal,
            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> notReady("Elasticsearch exact field [$physical] in index [$index] has no portable binding.")
        }
        requireIndexed(property, index, physical)
    }

    private fun requireRangeField(
        index: String,
        field: QueryFieldId,
        physical: String,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        when (schema.fields.getValue(field).type.operandType()) {
            LogicalFieldType.Int64 -> if (!property.isIntegralNumber()) typeMismatch(index, physical)
            LogicalFieldType.Instant -> if (!property.isLong || fields.getValue(field).valueEncoding !=
                ElasticsearchValueEncoding.EPOCH_MILLIS
            ) {
                typeMismatch(index, physical)
            }

            else -> notReady("Elasticsearch range field [$physical] in index [$index] has no portable binding.")
        }
        requireIndexed(property, index, physical)
    }

    private fun requireLiteralField(
        index: String,
        field: QueryFieldId,
        binding: ElasticsearchFieldBinding,
        physical: String,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        if (!property.isKeyword && !property.isWildcard) {
            typeMismatch(index, physical)
        }
        if (property.isKeyword) requireKeywordCompleteness(index, physical, property, binding)
        requireIndexed(property, index, physical)
        if (schema.fields.getValue(field).type.operandType() != LogicalFieldType.Text) typeMismatch(index, physical)
    }

    private fun requireKeywordCompleteness(
        index: String,
        physical: String,
        property: Property,
        binding: ElasticsearchFieldBinding,
    ) {
        val readiness = binding.keywordReadiness
            ?: notReady("Elasticsearch keyword field [$physical] in index [$index] lacks completeness attestation.")
        val ignoreAbove = (property._get() as PropertyBase).ignoreAbove()
        if (ignoreAbove != null && ignoreAbove < readiness.maximumCharacters) {
            notReady(
                "Elasticsearch keyword field [$physical] in index [$index] ignore_above[$ignoreAbove] " +
                    "is below the attested maximum[${readiness.maximumCharacters}].",
            )
        }
        if (property.isKeyword && property.keyword().normalizer() != null) {
            notReady(
                "Elasticsearch keyword field [$physical] in index [$index] normalizer" +
                    "[${property.keyword().normalizer()}] is not portable.",
            )
        }
    }

    private fun requireSearchField(
        index: String,
        physical: String,
        expectedAnalyzer: String?,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        if (!property.isText) {
            notReady("Elasticsearch search field [$physical] in index [$index] is not explicitly analyzed text.")
        }
        val actualAnalyzer = property.text().analyzer()
        val actualSearchAnalyzer = property.text().searchAnalyzer() ?: actualAnalyzer
        if (actualAnalyzer != expectedAnalyzer || actualSearchAnalyzer != expectedAnalyzer) {
            notReady(
                "Elasticsearch search field [$physical] in index [$index] analyzer[$actualAnalyzer] " +
                    "search_analyzer[$actualSearchAnalyzer] does not match [$expectedAnalyzer].",
            )
        }
        requireIndexed(property, index, physical)
    }

    private fun requirePresenceField(
        index: String,
        physical: String,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        if (!property.isBoolean) {
            notReady("Elasticsearch presence field [$physical] in index [$index] must be a boolean marker.")
        }
        requireIndexed(property, index, physical)
    }

    private fun requireDocValuesField(index: String, physical: String, properties: Map<String, Property>) {
        val property = requireProperty(index, physical, properties)
        val docValues = property._get() as? DocValuesPropertyBase
            ?: notReady("Elasticsearch field [$physical] in index [$index] has no doc values contract.")
        if (docValues.docValues() == false) {
            notReady("Elasticsearch field [$physical] in index [$index] disables doc values.")
        }
    }

    private fun requireNullBucketField(index: String, physical: String, properties: Map<String, Property>) {
        val property = requireProperty(index, physical, properties)
        val nullValue = when {
            property.isKeyword -> property.keyword().nullValue()
            property.isBoolean -> property.boolean_().nullValue()
            property.isByte -> property.byte_().nullValue()
            property.isShort -> property.short_().nullValue()
            property.isInteger -> property.integer().nullValue()
            property.isLong -> property.long_().nullValue()
            else -> null
        }
        if (nullValue != null) {
            notReady(
                "Elasticsearch group field [$physical] in index [$index] indexes null_value[$nullValue]; " +
                    "missing and explicit null would form different composite buckets.",
            )
        }
    }

    private fun requireOrderedBucketField(
        index: String,
        field: QueryFieldId,
        binding: ElasticsearchFieldBinding,
        physical: String,
        properties: Map<String, Property>,
    ) {
        val property = requireProperty(index, physical, properties)
        when (schema.fields.getValue(field).type.operandType()) {
            LogicalFieldType.Text -> {
                if (!property.isKeyword && !property.isConstantKeyword) {
                    typeMismatch(index, physical)
                }
                requireKeywordCompleteness(index, physical, property, binding)
            }

            LogicalFieldType.Boolean -> if (!property.isBoolean) typeMismatch(index, physical)
            LogicalFieldType.Int64 -> if (!property.isIntegralNumber()) typeMismatch(index, physical)
            LogicalFieldType.Instant -> if (!property.isLong ||
                binding.valueEncoding != ElasticsearchValueEncoding.EPOCH_MILLIS
            ) {
                typeMismatch(index, physical)
            }

            LogicalFieldType.Decimal,
            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> notReady("Elasticsearch field [$physical] in index [$index] has no portable sort/group binding.")
        }
        requireDocValuesField(index, physical, properties)
    }

    private fun requireIndexed(property: Property, index: String, physical: String) {
        val indexed = when {
            property.isKeyword -> property.keyword().index()
            property.isText -> property.text().index()
            property.isBoolean -> property.boolean_().index()
            property._get() is NumberPropertyBase -> (property._get() as NumberPropertyBase).index()
            else -> notReady("Elasticsearch field [$physical] in index [$index] has no indexed leaf contract.")
        }
        if (indexed == false) notReady("Elasticsearch field [$physical] in index [$index] is not indexed.")
    }

    private fun requireProperty(index: String, physical: String, properties: Map<String, Property>): Property =
        properties[physical] ?: notReady("Elasticsearch field [$physical] is missing from index [$index].")

    private fun typeMismatch(index: String, physical: String): Nothing =
        notReady("Elasticsearch field [$physical] in index [$index] has an incompatible mapping type.")

    private fun requireMeta(
        index: String,
        mapping: TypeMapping,
        key: String,
        expected: String,
    ) {
        val actual = mapping.requiredMeta(index, key)
        if (actual != expected) {
            notReady("Elasticsearch index [$index] metadata [$key] value [$actual] does not match [$expected].")
        }
    }
}

private fun TypeMapping.requiredMeta(index: String, key: String): String = meta()[key]?.let { value ->
    runCatching { value.to(String::class.java) }.getOrElse { error ->
        throw ElasticsearchQueryBackendNotReadyException(
            "Elasticsearch index [$index] has unreadable Query metadata [$key].",
            error,
        )
    }
} ?: notReady("Elasticsearch index [$index] is missing Query metadata [$key].")

private object ElasticsearchCapabilityDigestEncoder {
    fun encode(binding: ElasticsearchPreparedQueryBinding): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUtf8("wow-elasticsearch-query-capability-v1")
            output.writeUtf8(binding.schema.target.namedAggregate.contextName)
            output.writeUtf8(binding.schema.target.namedAggregate.aggregateName)
            output.writeUtf8(binding.schema.target.documentKind.name)
            output.writeUtf8(binding.schema.contractId.value)
            output.writeUtf8(binding.backendId.value)
            output.writeUtf8(binding.indexName)
            output.writeInt(binding.fields.size)
            binding.fields.forEach { (field, definition) ->
                output.writeFieldId(field)
                output.writeFieldBinding(definition)
            }
            output.writeInt(binding.searchScopes.size)
            binding.searchScopes.forEach { (scope, definition) ->
                output.writeUtf8(scope.value)
                output.writeInt(definition.fields.size)
                definition.fields.forEach { (field, physical) ->
                    output.writeFieldId(field)
                    output.writeUtf8(physical)
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
    }

    private fun DataOutputStream.writeFieldBinding(binding: ElasticsearchFieldBinding) {
        writeUtf8(binding.sourceField)
        writeStrings(binding.capabilities.map(FieldCapability::name).sorted())
        writeOptional(binding.exactField)
        writeOptional(binding.presenceField)
        writeOptional(binding.rangeField)
        writeOptional(binding.searchField)
        writeOptional(binding.searchAnalyzer)
        writeOptional(binding.literalField)
        writeOptional(binding.sortField)
        writeOptional(binding.groupField)
        writeBoolean(binding.groupReadiness != null)
        binding.groupReadiness?.let { writeBoolean(it.historicalValuesAudited) }
        writeOptional(binding.nestedPath)
        writeUtf8(binding.valueEncoding.name)
        writeBoolean(binding.keywordReadiness != null)
        binding.keywordReadiness?.let { readiness ->
            writeInt(readiness.maximumCharacters)
            writeInt(readiness.maximumUtf8Bytes)
            writeBoolean(readiness.historicalValuesAudited)
            writeBoolean(readiness.writeConstraintEnforced)
        }
    }

    private fun DataOutputStream.writeFieldId(field: QueryFieldId) {
        when (field) {
            is QueryFieldId.System -> {
                writeByte(0)
                writeUtf8(field.kind.name)
            }

            is QueryFieldId.Path -> {
                writeByte(1)
                writeStrings(field.segments)
            }
        }
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeUtf8(value) }
    }

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        value?.let { present -> writeUtf8(present) }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

private fun prepareBinding(
    schema: QueryDocumentSchema,
    indexName: String,
    mappingVersion: String,
    fields: Map<QueryFieldId, ElasticsearchFieldBinding>,
    searchScopes: Iterable<ElasticsearchSearchScopeBinding>,
    backendId: BackendId,
): ElasticsearchPreparedQueryBinding {
    require(schema.target.documentKind == QueryDocumentKind.SNAPSHOT) {
        "Elasticsearch Snapshot query binding requires a Snapshot target."
    }
    requirePhysicalPath(indexName)
    require(indexName == schema.target.namedAggregate.toSnapshotIndexName()) {
        "Elasticsearch Snapshot query binding index[$indexName] does not match target[${schema.target}]."
    }
    require(mappingVersion.isNotBlank() && mappingVersion.none(Char::isISOControl)) {
        "Elasticsearch Query mapping version must not be blank or contain control characters."
    }
    val fieldCopy = LinkedHashMap<QueryFieldId, ElasticsearchFieldBinding>(fields.size)
    fields.entries.sortedBy { entry -> entry.key.toString() }.forEach { (field, binding) ->
        val schemaField = requireNotNull(schema.fields[field]) {
            "Elasticsearch field $field is not declared by the logical schema."
        }
        require(schemaField.capabilities.containsAll(binding.capabilities)) {
            "Elasticsearch field $field overclaims the logical schema contract."
        }
        requireValidEncoding(schemaField.type, binding.valueEncoding)
        if (field is QueryFieldId.Path) {
            require(binding.sourceField == field.toString()) {
                "Elasticsearch record materialization requires logical and source user paths to match."
            }
        }
        fieldCopy[field] = binding
    }
    requireSystemBindings(schema, fieldCopy)
    require(fieldCopy.keys == schema.fields.keys) {
        "Elasticsearch query binding must cover every field in the logical schema."
    }
    val scopeCopy = prepareSearchScopes(schema, fieldCopy, searchScopes)
    return ElasticsearchPreparedQueryBinding(
        schema,
        indexName,
        mappingVersion,
        Collections.unmodifiableMap(fieldCopy),
        scopeCopy,
        backendId,
    )
}

private fun prepareSearchScopes(
    schema: QueryDocumentSchema,
    fields: Map<QueryFieldId, ElasticsearchFieldBinding>,
    searchScopes: Iterable<ElasticsearchSearchScopeBinding>,
): Map<SearchScopeId, ElasticsearchSearchScopeBinding> {
    val prepared = LinkedHashMap<SearchScopeId, ElasticsearchSearchScopeBinding>()
    searchScopes.forEach { scope ->
        val definition = requireNotNull(schema.searchScopes[scope.scope]) {
            "Elasticsearch search scope ${scope.scope} is not declared by the logical schema."
        }
        require(scope.fields.keys == definition.fields.toSet()) {
            "Elasticsearch search scope ${scope.scope} must bind every declared field exactly once."
        }
        scope.fields.forEach { (field, physical) ->
            val fieldBinding = requireNotNull(fields[field]) {
                "Elasticsearch search field $field is not bound."
            }
            require(fieldBinding.searchField == physical && FieldCapability.FULL_TEXT in fieldBinding.capabilities) {
                "Elasticsearch search scope ${scope.scope} must use the field FULL_TEXT role."
            }
        }
        require(prepared.put(scope.scope, scope) == null) {
            "Elasticsearch search scope ${scope.scope} must be unique."
        }
    }
    return Collections.unmodifiableMap(prepared)
}

private fun requireSystemBindings(
    schema: QueryDocumentSchema,
    fields: Map<QueryFieldId, ElasticsearchFieldBinding>,
) {
    SNAPSHOT_SYSTEM_FIELDS.forEach { (kind, physical) ->
        val id = QueryFieldId.System(kind)
        if (id in schema.fields && id in fields) {
            val binding = fields.getValue(id)
            require(binding.sourceField == physical.source && binding.exactField == physical.exact) {
                "Elasticsearch Snapshot system field $kind must bind source[${physical.source}] exact[${physical.exact}]."
            }
        }
    }
    require(fields[QueryFieldId.System(SystemFieldKind.IDENTITY)]?.exactField == ES_ID_FIELD) {
        "Elasticsearch Snapshot identity must bind exact queries to $ES_ID_FIELD."
    }
}

private fun requireValidEncoding(type: LogicalFieldType, encoding: ElasticsearchValueEncoding) {
    val operand = type.operandType()
    require(
        when (operand) {
            LogicalFieldType.Instant -> encoding == ElasticsearchValueEncoding.EPOCH_MILLIS
            else -> encoding == ElasticsearchValueEncoding.DEFAULT
        },
    ) {
        "Elasticsearch value encoding $encoding does not match logical field type $type."
    }
}

private fun flattenProperties(properties: Map<String, Property>): Map<String, Property> {
    val result = LinkedHashMap<String, Property>()
    fun visit(prefix: String?, current: Map<String, Property>) {
        current.entries.sortedBy(Map.Entry<String, Property>::key).forEach { (name, property) ->
            val path = if (prefix == null) name else "$prefix.$name"
            result[path] = property
            val base = property._get() as? PropertyBase
            base?.properties()?.let { children -> visit(path, children) }
            base?.fields()?.let { fields -> visit(path, fields) }
        }
    }
    visit(null, properties)
    return result
}

private fun Property.isIntegralNumber(): Boolean = isByte || isShort || isInteger || isLong || isUnsignedLong

private fun LogicalFieldType.operandType(): LogicalFieldType =
    if (this is LogicalFieldType.Array) elementType else this

private fun requirePhysicalPath(path: String) {
    require(path.isNotBlank()) { "Elasticsearch physical path must not be blank." }
    require(path.none(Char::isISOControl)) { "Elasticsearch physical path must not contain control characters." }
    require(path.split('.').none(String::isBlank)) { "Elasticsearch physical path segments must not be blank." }
}

private fun notReady(message: String): Nothing = throw ElasticsearchQueryBackendNotReadyException(message)

private data class SystemPhysicalFields(val source: String, val exact: String)

private const val ES_ID_FIELD = "_id"
internal const val ELASTICSEARCH_QUERY_MAPPING_VERSION_META = "wow_query_mapping_version"
internal const val ELASTICSEARCH_QUERY_DOCUMENT_KIND_META = "wow_query_document_kind"
internal const val ELASTICSEARCH_QUERY_SCHEMA_CONTRACT_META = "wow_query_schema_contract_id"
internal const val ELASTICSEARCH_QUERY_CAPABILITY_DIGEST_META = "wow_query_capability_digest"

private val SNAPSHOT_SYSTEM_FIELDS = mapOf(
    SystemFieldKind.IDENTITY to SystemPhysicalFields(MessageRecords.AGGREGATE_ID, ES_ID_FIELD),
    SystemFieldKind.AGGREGATE_ID to SystemPhysicalFields(MessageRecords.AGGREGATE_ID, ES_ID_FIELD),
    SystemFieldKind.TENANT_ID to SystemPhysicalFields(MessageRecords.TENANT_ID, MessageRecords.TENANT_ID),
    SystemFieldKind.OWNER_ID to SystemPhysicalFields(MessageRecords.OWNER_ID, MessageRecords.OWNER_ID),
    SystemFieldKind.SPACE_ID to SystemPhysicalFields(MessageRecords.SPACE_ID, MessageRecords.SPACE_ID),
    SystemFieldKind.DELETED to SystemPhysicalFields(StateAggregateRecords.DELETED, StateAggregateRecords.DELETED),
)
