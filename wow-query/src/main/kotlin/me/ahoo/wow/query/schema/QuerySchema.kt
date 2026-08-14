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
package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import java.util.Collections

enum class QueryFieldValueKind {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    STRING,
    TIME,
    ENUM,
    BINARY,
    OBJECT,
    MAP
}

enum class QueryCollectionKind {
    NONE,
    SCALAR,
    OBJECT
}

@JvmInline
value class QueryBackendId(val value: String) {
    init {
        require(BACKEND_ID_PATTERN.matches(value) && value !in RESERVED_BACKEND_IDS) {
            "Query backend id is invalid."
        }
    }

    override fun toString(): String = value

    private companion object {
        val BACKEND_ID_PATTERN = Regex("[a-z][a-z0-9-]{0,63}")
        val RESERVED_BACKEND_IDS = setOf("authority", "driver", "system")
    }
}

@JvmInline
value class QueryBackendFieldPath(val value: String) {
    init {
        require(BACKEND_FIELD_PATH_PATTERN.matches(value)) { "Query backend field path is invalid." }
    }

    override fun toString(): String = value

    private companion object {
        val BACKEND_FIELD_PATH_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")
    }
}

enum class QueryFieldUsage {
    EXACT,
    SEARCH,
    SORT,
    NESTED
}

class StringQueryOptions(
    comparisonModes: Set<StringComparisonMode> = StringComparisonMode.entries.toSet(),
    val collation: String? = null,
    val maxLength: Int? = null
) {
    val comparisonModes: Set<StringComparisonMode> = immutableSet(comparisonModes, "String comparison modes")

    init {
        require(this.comparisonModes.isNotEmpty()) { "String comparison modes cannot be empty." }
        require(collation == null || collation.isNotBlank()) { "Collation cannot be blank." }
        require(maxLength == null || maxLength > 0) { "Maximum string length must be positive." }
    }

    override fun equals(other: Any?): Boolean = other is StringQueryOptions &&
        comparisonModes == other.comparisonModes && collation == other.collation && maxLength == other.maxLength

    override fun hashCode(): Int = 31 * (31 * comparisonModes.hashCode() + (collation?.hashCode() ?: 0)) +
        (maxLength ?: 0)
}

class QueryCapabilityBinding(
    val backendId: QueryBackendId,
    val usage: QueryFieldUsage,
    val field: QueryBackendFieldPath
) {
    override fun equals(other: Any?): Boolean = other is QueryCapabilityBinding &&
        backendId == other.backendId && usage == other.usage && field == other.field

    override fun hashCode(): Int = 31 * (31 * backendId.hashCode() + usage.hashCode()) + field.hashCode()

    override fun toString(): String = "QueryCapabilityBinding(backendId=$backendId, usage=$usage, field=$field)"
}

class QueryFieldSchema @JvmOverloads constructor(
    val path: LogicalField,
    val valueKind: QueryFieldValueKind,
    val nullable: Boolean,
    val collectionKind: QueryCollectionKind = QueryCollectionKind.NONE,
    val nested: Boolean = valueKind == QueryFieldValueKind.OBJECT,
    val queryable: Boolean = defaultOperators(valueKind, collectionKind).isNotEmpty(),
    val sortable: Boolean = collectionKind == QueryCollectionKind.NONE && (
        valueKind == QueryFieldValueKind.INTEGER || valueKind == QueryFieldValueKind.DECIMAL ||
            valueKind == QueryFieldValueKind.TIME
        ),
    val projectable: Boolean = true,
    val elementMatchEnabled: Boolean = false,
    operators: Set<PortableOperator> = defaultOperators(valueKind, collectionKind),
    capabilities: Set<QueryCapabilityId> = emptySet(),
    bindings: Set<QueryCapabilityBinding> = emptySet(),
    val stringOptions: StringQueryOptions? = if (valueKind == QueryFieldValueKind.STRING) StringQueryOptions() else null,
    val system: Boolean = false
) {
    val operators: Set<PortableOperator> = immutableSet(operators, "Field operators")
    val capabilities: Set<QueryCapabilityId> = immutableSet(capabilities, "Field capabilities")
    val bindings: Set<QueryCapabilityBinding> = immutableSet(bindings, "Field bindings")

    init {
        require(defaultOperators(valueKind, collectionKind).containsAll(this.operators)) {
            "Field declares a portable operator incompatible with its value or collection kind."
        }
        require(queryable || this.operators.isEmpty()) { "Non-queryable field cannot declare portable operators." }
        require(valueKind == QueryFieldValueKind.STRING || stringOptions == null) {
            "String options require a string field."
        }
        require(!nested || valueKind == QueryFieldValueKind.OBJECT) { "Only object fields can be nested." }
        require(collectionKind != QueryCollectionKind.OBJECT || valueKind == QueryFieldValueKind.OBJECT) {
            "Object collection requires an object value kind."
        }
        require(
            collectionKind != QueryCollectionKind.SCALAR ||
                (valueKind != QueryFieldValueKind.OBJECT && valueKind != QueryFieldValueKind.MAP)
        ) {
            "Scalar collection cannot use an object or map value kind."
        }
        require(!elementMatchEnabled || collectionKind == QueryCollectionKind.OBJECT) {
            "Element match requires an object collection."
        }
        require(this.capabilities.all { it.isAllowedCapability() }) { "Query capability id is not allowed." }
        require(
            QueryCapabilityId(FULL_TEXT_CAPABILITY) !in this.capabilities ||
                (valueKind == QueryFieldValueKind.STRING && collectionKind == QueryCollectionKind.NONE)
        ) { "Full-text capability requires a scalar string field." }
        require(this.bindings.map { it.backendId to it.usage }.distinct().size == this.bindings.size) {
            "Field bindings must be unique by backend and usage."
        }
        this.bindings.forEach(::validateBinding)
    }

    fun copy(
        path: LogicalField = this.path,
        valueKind: QueryFieldValueKind = this.valueKind,
        nullable: Boolean = this.nullable,
        collectionKind: QueryCollectionKind = this.collectionKind,
        nested: Boolean = this.nested,
        queryable: Boolean = this.queryable,
        sortable: Boolean = this.sortable,
        projectable: Boolean = this.projectable,
        elementMatchEnabled: Boolean = this.elementMatchEnabled,
        operators: Set<PortableOperator> = this.operators,
        capabilities: Set<QueryCapabilityId> = this.capabilities,
        bindings: Set<QueryCapabilityBinding> = this.bindings,
        stringOptions: StringQueryOptions? = this.stringOptions,
        system: Boolean = this.system
    ): QueryFieldSchema = QueryFieldSchema(
        path = path,
        valueKind = valueKind,
        nullable = nullable,
        collectionKind = collectionKind,
        nested = nested,
        queryable = queryable,
        sortable = sortable,
        projectable = projectable,
        elementMatchEnabled = elementMatchEnabled,
        operators = operators,
        capabilities = capabilities,
        bindings = bindings,
        stringOptions = stringOptions,
        system = system
    )

    override fun equals(other: Any?): Boolean =
        other is QueryFieldSchema && equalityProperties() == other.equalityProperties()

    override fun hashCode(): Int = equalityProperties().hashCode()

    private fun equalityProperties(): List<Any?> = listOf(
        path,
        valueKind,
        nullable,
        collectionKind,
        nested,
        queryable,
        sortable,
        projectable,
        elementMatchEnabled,
        operators,
        capabilities,
        bindings,
        stringOptions,
        system
    )

    private fun validateBinding(binding: QueryCapabilityBinding) {
        when (binding.usage) {
            QueryFieldUsage.EXACT -> require(
                queryable && valueKind != QueryFieldValueKind.OBJECT && valueKind != QueryFieldValueKind.MAP
            ) { "Exact binding requires a queryable scalar field." }

            QueryFieldUsage.SEARCH -> require(
                valueKind == QueryFieldValueKind.STRING && collectionKind == QueryCollectionKind.NONE &&
                    QueryCapabilityId(FULL_TEXT_CAPABILITY) in capabilities
            ) { "Search binding requires a scalar string field with the full-text capability." }

            QueryFieldUsage.SORT -> require(sortable) { "Sort binding requires a sortable field." }
            QueryFieldUsage.NESTED -> require(
                valueKind == QueryFieldValueKind.OBJECT &&
                    (nested || collectionKind == QueryCollectionKind.OBJECT)
            ) { "Nested binding requires an object field." }
        }
    }

    override fun toString(): String = "QueryFieldSchema(path=$path, valueKind=$valueKind, system=$system)"

    companion object {
        private const val FULL_TEXT_CAPABILITY = "full-text"
        private val EXTENSION_CAPABILITY_PATTERN = Regex("x-[a-z][a-z0-9-]*:[a-z][a-z0-9-]*")

        @JvmStatic
        fun string(path: LogicalField, nullable: Boolean): QueryFieldSchema = QueryFieldSchema(
            path = path,
            valueKind = QueryFieldValueKind.STRING,
            nullable = nullable
        )

        private fun defaultOperators(
            valueKind: QueryFieldValueKind,
            collectionKind: QueryCollectionKind
        ): Set<PortableOperator> {
            if (collectionKind != QueryCollectionKind.NONE) {
                return when (collectionKind) {
                    QueryCollectionKind.NONE -> emptySet()
                    QueryCollectionKind.SCALAR -> COLLECTION_OPERATORS
                    QueryCollectionKind.OBJECT -> PRESENCE_OPERATORS
                }
            }
            return when (valueKind) {
                QueryFieldValueKind.BOOLEAN -> BOOLEAN_OPERATORS
                QueryFieldValueKind.ENUM -> EQUALITY_OPERATORS
                QueryFieldValueKind.INTEGER,
                QueryFieldValueKind.DECIMAL,
                QueryFieldValueKind.TIME -> ORDERED_OPERATORS
                QueryFieldValueKind.STRING -> STRING_OPERATORS
                QueryFieldValueKind.BINARY -> EQUALITY_OPERATORS
                QueryFieldValueKind.OBJECT -> PRESENCE_OPERATORS
                QueryFieldValueKind.MAP -> emptySet()
            }
        }

        private val PRESENCE_OPERATORS = setOf(
            PortableOperator.NULL,
            PortableOperator.NOT_NULL,
            PortableOperator.EXISTS
        )
        private val EQUALITY_OPERATORS = PRESENCE_OPERATORS + setOf(
            PortableOperator.EQ,
            PortableOperator.NE,
            PortableOperator.IN,
            PortableOperator.NOT_IN
        )
        private val BOOLEAN_OPERATORS = EQUALITY_OPERATORS + setOf(PortableOperator.TRUE, PortableOperator.FALSE)
        private val ORDERED_OPERATORS = EQUALITY_OPERATORS + setOf(
            PortableOperator.GT,
            PortableOperator.LT,
            PortableOperator.GTE,
            PortableOperator.LTE,
            PortableOperator.BETWEEN
        )
        private val STRING_OPERATORS = EQUALITY_OPERATORS + setOf(
            PortableOperator.CONTAINS,
            PortableOperator.STARTS_WITH,
            PortableOperator.ENDS_WITH
        )
        private val COLLECTION_OPERATORS = PRESENCE_OPERATORS + setOf(
            PortableOperator.IN,
            PortableOperator.NOT_IN,
            PortableOperator.ALL_IN,
            PortableOperator.EMPTY_COLLECTION
        )

        private fun QueryCapabilityId.isAllowedCapability(): Boolean =
            value == FULL_TEXT_CAPABILITY || EXTENSION_CAPABILITY_PATTERN.matches(value)
    }
}

class QuerySchema(
    override val target: QueryTarget,
    fields: Collection<QueryFieldSchema>
) : QuerySchemaView {
    override val fields: Map<LogicalField, QueryFieldSchema>

    init {
        val snapshot = LinkedHashMap<LogicalField, QueryFieldSchema>(fields.size)
        fields.forEach { field ->
            require(snapshot.put(field.path, field) == null) { "Query schema contains duplicate logical fields." }
        }
        require(snapshot.size == fields.size) { "Query schema field cardinality changed during immutable snapshot." }
        validateSystemFields(target, snapshot)
        validateFieldHierarchy(snapshot)
        this.fields = Collections.unmodifiableMap(snapshot)
    }

    fun withField(field: QueryFieldSchema): QuerySchema {
        val updated = LinkedHashMap(fields)
        updated[field.path] = field
        return QuerySchema(target, updated.values)
    }

    override fun equals(other: Any?): Boolean = other is QuerySchema && target == other.target && fields == other.fields

    override fun hashCode(): Int = 31 * target.hashCode() + fields.hashCode()

    private fun validateSystemFields(
        target: QueryTarget,
        fields: Map<LogicalField, QueryFieldSchema>
    ) {
        val canonicalSystemFields = QuerySystemFields.fields(target.documentKind).associateBy(QueryFieldSchema::path)
        fields.values.filter(QueryFieldSchema::system).forEach { field ->
            val canonical = canonicalSystemFields[field.path]
            require(canonical != null && field.hasSameIdentity(canonical)) {
                "Query schema contains an undeclared or incompatible system field."
            }
        }
    }

    private fun validateFieldHierarchy(fields: Map<LogicalField, QueryFieldSchema>) {
        fields.values.forEach { field ->
            val segments = field.path.value.split('.')
            val snapshotTagChild = isSnapshotTagChild(field, segments, fields)
            if (segments.first() == "tags" && segments.size > 1) {
                require(snapshotTagChild) {
                    "Only declared snapshot tag scalar collections may be nested below the system tags map."
                }
            }
            for (endIndex in 1 until segments.size) {
                val ancestor = fields[LogicalField(segments.take(endIndex).joinToString("."))] ?: continue
                require(ancestor.valueKind == QueryFieldValueKind.OBJECT || snapshotTagChild) {
                    "Query field cannot be declared below a scalar, collection, or map field."
                }
            }
        }
    }

    private fun isSnapshotTagChild(
        field: QueryFieldSchema,
        segments: List<String>,
        fields: Map<LogicalField, QueryFieldSchema>
    ): Boolean {
        if (target.documentKind != QueryDocumentKind.SNAPSHOT) {
            return false
        }
        if (segments.size != 2 || segments.first() != "tags") {
            return false
        }
        if (field.system || field.collectionKind != QueryCollectionKind.SCALAR) {
            return false
        }
        if (field.valueKind == QueryFieldValueKind.OBJECT || field.valueKind == QueryFieldValueKind.MAP) {
            return false
        }
        val canonicalTags = QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT)
            .single { it.path.value == "tags" }
        return fields[canonicalTags.path]?.hasSameIdentity(canonicalTags) == true
    }

    private fun QueryFieldSchema.hasSameIdentity(other: QueryFieldSchema): Boolean =
        path == other.path && valueKind == other.valueKind && nullable == other.nullable &&
            collectionKind == other.collectionKind && nested == other.nested && system == other.system
}

internal fun QuerySchemaView.immutableSnapshot(): QuerySchema {
    if (this is QuerySchema) {
        return this
    }
    val snapshotTarget = target
    val sourceFields = fields
    val expectedSize = sourceFields.size
    val fieldSnapshot = ArrayList<QueryFieldSchema>(expectedSize)
    sourceFields.forEach { (path, field) ->
        require(path == field.path) { "Query schema field key must match its logical path." }
        fieldSnapshot += field
    }
    require(fieldSnapshot.size == expectedSize && sourceFields.size == expectedSize) {
        "Query schema field cardinality changed during immutable snapshot."
    }
    require(target == snapshotTarget) { "Query schema target changed during immutable snapshot." }
    return QuerySchema(snapshotTarget, fieldSnapshot)
}

private fun <T> immutableSet(values: Set<T>, label: String): Set<T> {
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "$label cardinality changed during immutable snapshot." }
    return Collections.unmodifiableSet(snapshot)
}
