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

package me.ahoo.wow.query.internal.schema

import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

@JvmInline
internal value class SchemaContractId(val value: String) {
    init {
        require(value.matches(HEX_PATTERN)) {
            "Schema contract id must be a SHA-256 hex string."
        }
    }

    private companion object {
        val HEX_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal class QueryDocumentSchema(
    val target: QueryTarget,
    fields: Iterable<QueryFieldSchema>,
    searchScopes: Iterable<QuerySearchScopeDefinition>,
) {
    val fields: Map<QueryFieldId, QueryFieldSchema>
    private val fieldAliases: Map<QueryFieldId.Path, QueryFieldId>
    val searchScopes: Map<SearchScopeId, QuerySearchScopeDefinition>
    val contractId: SchemaContractId

    init {
        val fieldList = fields.toList()
        require(fieldList.map(QueryFieldSchema::id).distinct().size == fieldList.size) {
            "Query field ids must be unique."
        }
        val sortedFields = fieldList.sortedWith(compareBy(QUERY_FIELD_ID_COMPARATOR, QueryFieldSchema::id))
        val fieldMap = LinkedHashMap<QueryFieldId, QueryFieldSchema>(sortedFields.size)
        sortedFields.forEach { fieldMap[it.id] = it }
        validatePathPrefixes(fieldMap)
        this.fields = Collections.unmodifiableMap(fieldMap)
        fieldAliases = buildFieldAliases(sortedFields, fieldMap)

        val searchScopeList = searchScopes.toList()
        require(searchScopeList.map(QuerySearchScopeDefinition::id).distinct().size == searchScopeList.size) {
            "Search scope ids must be unique."
        }
        val scopeMap = LinkedHashMap<SearchScopeId, QuerySearchScopeDefinition>(searchScopeList.size)
        val legacyKeys = mutableSetOf<Pair<QueryFieldId.Path?, QueryFieldId.Path>>()
        searchScopeList.sortedBy { it.id.value }.forEach { definition ->
            validateSearchScope(definition, fieldMap)
            definition.legacyAliases.forEach { alias ->
                require(legacyKeys.add(definition.owner to alias)) {
                    "Legacy search scope alias $alias is ambiguous for owner ${definition.owner}."
                }
            }
            scopeMap[definition.id] = definition
        }
        this.searchScopes = Collections.unmodifiableMap(scopeMap)
        contractId = SchemaContractId(SchemaContractEncoder.encode(this))
    }

    fun resolveLegacySearchScope(
        owner: QueryFieldId.Path?,
        alias: QueryFieldId.Path,
    ): QuerySearchScopeDefinition? = searchScopes.values.singleOrNull { definition ->
        definition.owner == owner && alias in definition.legacyAliases
    }

    fun resolveField(id: QueryFieldId): QueryFieldId? =
        when {
            id in fields -> id
            id is QueryFieldId.Path -> fieldAliases[id]
            else -> null
        }

    fun elementOwner(field: QueryFieldId.Path): QueryFieldId.Path? = nearestElementOwner(field, fields)

    private fun buildFieldAliases(
        fields: List<QueryFieldSchema>,
        fieldMap: Map<QueryFieldId, QueryFieldSchema>,
    ): Map<QueryFieldId.Path, QueryFieldId> {
        val aliases = LinkedHashMap<QueryFieldId.Path, QueryFieldId>()
        fields.forEach { field ->
            field.logicalAliases.forEach { alias ->
                require(alias !in fieldMap) {
                    "Query field alias $alias conflicts with a canonical field."
                }
                require(aliases.put(alias, field.id) == null) {
                    "Query field alias $alias must be unique."
                }
                if (field.id is QueryFieldId.Path) {
                    require(nearestElementOwner(alias, fieldMap) == nearestElementOwner(field.id, fieldMap)) {
                        "Query field alias $alias must belong to the same element owner as ${field.id}."
                    }
                } else {
                    require(nearestElementOwner(alias, fieldMap) == null) {
                        "System field alias $alias must remain at the root scope."
                    }
                }
            }
        }
        return Collections.unmodifiableMap(aliases)
    }

    private fun validatePathPrefixes(fields: Map<QueryFieldId, QueryFieldSchema>) {
        fields.keys.filterIsInstance<QueryFieldId.Path>().forEach { path ->
            for (prefixLength in 1 until path.segments.size) {
                val prefix = QueryFieldId.Path(path.segments.take(prefixLength))
                val prefixSchema = requireNotNull(fields[prefix]) {
                    "Missing query field prefix $prefix for $path."
                }
                require(prefixSchema.type.isObjectContainer()) {
                    "Query field prefix $prefix must be an object container."
                }
            }
        }
    }

    private fun validateSearchScope(
        definition: QuerySearchScopeDefinition,
        fields: Map<QueryFieldId, QueryFieldSchema>,
    ) {
        definition.owner?.let { owner ->
            val ownerSchema = requireNotNull(fields[owner]) { "Search scope owner $owner is not declared." }
            require(FieldCapability.ELEMENT_MATCH in ownerSchema.capabilities) {
                "Search scope owner $owner must declare ELEMENT_MATCH."
            }
        }
        definition.fields.values.forEach { field ->
            val schema = requireNotNull(fields[field]) {
                "Search scope field $field is not declared."
            }
            require(FieldCapability.FULL_TEXT in schema.capabilities) {
                "Search scope field $field must declare FULL_TEXT."
            }
            require(nearestElementOwner(field, fields) == definition.owner) {
                "Search scope field $field does not belong to owner ${definition.owner}."
            }
        }
        definition.legacyAliases.forEach { alias ->
            require(alias in fields) {
                "Legacy search scope alias $alias is not a declared field."
            }
            require(nearestElementOwner(alias, fields) == definition.owner) {
                "Legacy search scope alias $alias does not belong to owner ${definition.owner}."
            }
        }
    }

    private fun nearestElementOwner(
        field: QueryFieldId.Path,
        fields: Map<QueryFieldId, QueryFieldSchema>,
    ): QueryFieldId.Path? =
        (1 until field.segments.size)
            .map { size -> QueryFieldId.Path(field.segments.take(size)) }
            .lastOrNull { candidate -> FieldCapability.ELEMENT_MATCH in fields[candidate]?.capabilities.orEmpty() }
}

private fun LogicalFieldType.isObjectContainer(): Boolean =
    this == LogicalFieldType.Object || this is LogicalFieldType.Array && elementType == LogicalFieldType.Object

private object SchemaContractEncoder {
    fun encode(schema: QueryDocumentSchema): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUtf8("query-schema-v1")
            output.writeUtf8(schema.target.namedAggregate.contextName)
            output.writeUtf8(schema.target.namedAggregate.aggregateName)
            output.writeUtf8(schema.target.documentKind.name)
            output.writeInt(schema.fields.size)
            schema.fields.values.forEach { field -> output.writeField(field) }
            output.writeInt(schema.searchScopes.size)
            schema.searchScopes.values.forEach { scope -> output.writeScope(scope) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
    }

    private fun DataOutputStream.writeField(field: QueryFieldSchema) {
        writeFieldId(field.id)
        writeType(field.type)
        writeUtf8(field.presence.name)
        writeUtf8(field.nullability.name)
        writeStrings(field.allowedOperators.map { it.name }.sorted())
        writeStrings(field.capabilities.map { it.name }.sorted())
        writeFieldIds(field.logicalAliases.sortedWith(QUERY_FIELD_PATH_COMPARATOR))
    }

    private fun DataOutputStream.writeScope(scope: QuerySearchScopeDefinition) {
        writeUtf8(scope.id.value)
        writeBoolean(scope.owner != null)
        scope.owner?.let { owner -> writeFieldId(owner) }
        writeFieldIds(scope.fields.values)
        writeFieldIds(scope.legacyAliases.sortedWith(QUERY_FIELD_PATH_COMPARATOR))
    }

    private fun DataOutputStream.writeType(type: LogicalFieldType) {
        when (type) {
            LogicalFieldType.Text -> writeUtf8("text")
            LogicalFieldType.Boolean -> writeUtf8("boolean")
            LogicalFieldType.Int64 -> writeUtf8("int64")
            LogicalFieldType.Decimal -> writeUtf8("decimal")
            LogicalFieldType.Instant -> writeUtf8("instant")
            LogicalFieldType.Bytes -> writeUtf8("bytes")
            LogicalFieldType.Object -> writeUtf8("object")
            is LogicalFieldType.Array -> {
                writeUtf8("array")
                writeType(type.elementType)
                writeUtf8(type.elementNullability.name)
                writeUtf8(type.emptySemantics.name)
            }
        }
    }

    private fun DataOutputStream.writeFieldIds(ids: List<QueryFieldId.Path>) {
        writeInt(ids.size)
        ids.forEach { id -> writeFieldId(id) }
    }

    private fun DataOutputStream.writeFieldId(id: QueryFieldId) {
        when (id) {
            is QueryFieldId.System -> {
                writeByte(0)
                writeUtf8(id.kind.name)
            }

            is QueryFieldId.Path -> {
                writeByte(1)
                writeStrings(id.segments)
            }
        }
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeUtf8(value) }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
