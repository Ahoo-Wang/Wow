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
    val backendId: String,
    val bindingId: String,
    options: Map<String, String> = emptyMap()
) {
    val options: Map<String, String> = immutableMap(options, "Capability binding options") { key, value ->
        require(key.isNotBlank() && value.isNotBlank()) { "Capability binding options cannot be blank." }
        require(key !in FORBIDDEN_OPTION_KEYS) { "Capability binding option is not allowed." }
    }

    init {
        require(backendId.isNotBlank()) { "Backend id cannot be blank." }
        require(bindingId.isNotBlank()) { "Binding id cannot be blank." }
    }

    override fun equals(other: Any?): Boolean = other is QueryCapabilityBinding &&
        backendId == other.backendId && bindingId == other.bindingId && options == other.options

    override fun hashCode(): Int = 31 * (31 * backendId.hashCode() + bindingId.hashCode()) + options.hashCode()

    override fun toString(): String =
        "QueryCapabilityBinding(backendId=$backendId, bindingId=$bindingId, optionKeys=${options.keys})"

    private companion object {
        val FORBIDDEN_OPTION_KEYS = setOf("authority", "driver", "physicalObject")
    }
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
    bindings: Map<String, QueryCapabilityBinding> = emptyMap(),
    val stringOptions: StringQueryOptions? = if (valueKind == QueryFieldValueKind.STRING) StringQueryOptions() else null,
    val system: Boolean = false
) {
    val operators: Set<PortableOperator> = immutableSet(operators, "Field operators")
    val capabilities: Set<QueryCapabilityId> = immutableSet(capabilities, "Field capabilities")
    val bindings: Map<String, QueryCapabilityBinding> = immutableMap(bindings, "Field bindings") { key, binding ->
        require(key == binding.backendId) { "Field binding key must match backend id." }
    }

    init {
        require(queryable || this.operators.isEmpty()) { "Non-queryable field cannot declare portable operators." }
        require(valueKind == QueryFieldValueKind.STRING || stringOptions == null) {
            "String options require a string field."
        }
        require(!elementMatchEnabled || collectionKind == QueryCollectionKind.OBJECT) {
            "Element match requires an object collection."
        }
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
        bindings: Map<String, QueryCapabilityBinding> = this.bindings,
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

    override fun toString(): String = "QueryFieldSchema(path=$path, valueKind=$valueKind, system=$system)"

    companion object {
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
            PortableOperator.ALL_IN
        )
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
        this.fields = Collections.unmodifiableMap(snapshot)
    }

    fun withField(field: QueryFieldSchema): QuerySchema {
        val updated = LinkedHashMap(fields)
        updated[field.path] = field
        return QuerySchema(target, updated.values)
    }

    override fun equals(other: Any?): Boolean = other is QuerySchema && target == other.target && fields == other.fields

    override fun hashCode(): Int = 31 * target.hashCode() + fields.hashCode()
}

private fun <T> immutableSet(values: Set<T>, label: String): Set<T> {
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "$label cardinality changed during immutable snapshot." }
    return Collections.unmodifiableSet(snapshot)
}

private fun <K, V> immutableMap(
    values: Map<K, V>,
    label: String,
    validate: (K, V) -> Unit
): Map<K, V> {
    val snapshot = LinkedHashMap<K, V>(values.size)
    values.forEach { (key, value) ->
        validate(key, value)
        snapshot[key] = value
    }
    require(snapshot.size == values.size) { "$label cardinality changed during immutable snapshot." }
    return Collections.unmodifiableMap(snapshot)
}
