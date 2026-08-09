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

package me.ahoo.wow.query.backend

import java.util.Collections

@ExperimentalQueryBackendApi
sealed interface QueryFieldId {
    data class System(val kind: SystemFieldKind) : QueryFieldId

    class Path(segments: Iterable<String>) : QueryFieldId {
        val segments: List<String> = Collections.unmodifiableList(segments.toList())

        init {
            require(this.segments.isNotEmpty()) {
                "Query field path must not be empty."
            }
            require(this.segments.none(String::isBlank)) {
                "Query field path segments must not be blank."
            }
            require(this.segments.none { segment -> segment.any(Char::isISOControl) }) {
                "Query field path segments must not contain control characters."
            }
        }

        override fun equals(other: Any?): Boolean = this === other || other is Path && segments == other.segments

        override fun hashCode(): Int = segments.hashCode()

        override fun toString(): String = segments.joinToString(".")
    }
}

@ExperimentalQueryBackendApi
enum class Presence {
    REQUIRED,
    OPTIONAL,
}

@ExperimentalQueryBackendApi
enum class Nullability {
    NON_NULL,
    NULLABLE,
}

@ExperimentalQueryBackendApi
enum class EmptyArraySemantics {
    DISTINCT,
    COLLAPSES_TO_MISSING,
}

@ExperimentalQueryBackendApi
sealed interface LogicalFieldType {
    data object Text : LogicalFieldType

    data object Boolean : LogicalFieldType

    data object Int64 : LogicalFieldType

    data object Decimal : LogicalFieldType

    data object Instant : LogicalFieldType

    data object Bytes : LogicalFieldType

    data object Object : LogicalFieldType

    data class Array(
        val elementType: LogicalFieldType,
        val elementNullability: Nullability,
        val emptySemantics: EmptyArraySemantics,
    ) : LogicalFieldType
}

@ExperimentalQueryBackendApi
enum class FieldCapability {
    EXACT,
    PRESENCE,
    RANGE,
    FULL_TEXT,
    LITERAL_PATTERN,
    SORTABLE,
    PROJECTABLE,
    AGGREGATABLE,
    ELEMENT_MATCH,
    ELEMENT_NULL,
}

@ExperimentalQueryBackendApi
class QueryFieldSchema(
    val id: QueryFieldId,
    val type: LogicalFieldType,
    val presence: Presence,
    val nullability: Nullability,
    allowedOperators: Iterable<PredicateOperator>,
    capabilities: Iterable<FieldCapability>,
    logicalAliases: Iterable<QueryFieldId.Path> = emptyList(),
) {
    val allowedOperators: Set<PredicateOperator> = immutableEnumSet(allowedOperators)
    val capabilities: Set<FieldCapability> = immutableEnumSet(capabilities)
    val logicalAliases: Set<QueryFieldId.Path> = Collections.unmodifiableSet(
        LinkedHashSet(logicalAliases.sortedWith(QUERY_FIELD_PATH_COMPARATOR)),
    )

    init {
        if (FieldCapability.ELEMENT_MATCH in this.capabilities) {
            require(type is LogicalFieldType.Array && type.elementType == LogicalFieldType.Object) {
                "ELEMENT_MATCH requires an array of objects."
            }
        }
        if (FieldCapability.FULL_TEXT in this.capabilities) {
            require(type.operandType() == LogicalFieldType.Text) {
                "FULL_TEXT requires a text field."
            }
        }
        if (FieldCapability.LITERAL_PATTERN in this.capabilities) {
            require(type.operandType() == LogicalFieldType.Text) {
                "LITERAL_PATTERN requires a text field."
            }
        }
        if (type.containsObjectValue()) {
            require(FieldCapability.EXACT !in this.capabilities) {
                "Object values cannot declare EXACT capability."
            }
        }
        if (FieldCapability.RANGE in this.capabilities) {
            require(type.operandType().isOrderedScalar()) {
                "RANGE requires a numeric or instant field."
            }
        }
        if (FieldCapability.SORTABLE in this.capabilities || FieldCapability.AGGREGATABLE in this.capabilities) {
            require(type.isPortableScalar()) {
                "SORTABLE and AGGREGATABLE require a portable scalar field."
            }
        }
        this.allowedOperators.forEach { operator ->
            require(operator.requiredCapability() in this.capabilities) {
                "Operator $operator requires ${operator.requiredCapability()}."
            }
            if (operator == PredicateOperator.ALL_IN) {
                require(type is LogicalFieldType.Array) {
                    "ALL_IN requires an array field."
                }
            }
            if (operator == PredicateOperator.BETWEEN) {
                require(type.operandType().isOrderedScalar()) {
                    "BETWEEN requires an ordered scalar or array element type."
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryFieldSchema &&
            id == other.id &&
            type == other.type &&
            presence == other.presence &&
            nullability == other.nullability &&
            allowedOperators == other.allowedOperators &&
            capabilities == other.capabilities &&
            logicalAliases == other.logicalAliases

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + presence.hashCode()
        result = 31 * result + nullability.hashCode()
        result = 31 * result + allowedOperators.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + logicalAliases.hashCode()
        return result
    }
}

internal fun QueryFieldSchema.accepts(value: NormalizedValue): Boolean =
    if (value == NormalizedValue.Null) {
        nullability == Nullability.NULLABLE
    } else {
        type.accepts(value)
    }

internal fun LogicalFieldType.accepts(value: NormalizedValue): Boolean =
    when (this) {
        LogicalFieldType.Text -> value is NormalizedValue.Text
        LogicalFieldType.Boolean -> value is NormalizedValue.BooleanValue
        LogicalFieldType.Int64 -> value is NormalizedValue.Int64
        LogicalFieldType.Decimal -> value is NormalizedValue.Decimal || value is NormalizedValue.Int64
        LogicalFieldType.Instant -> value is NormalizedValue.InstantValue
        LogicalFieldType.Bytes -> value is NormalizedValue.Bytes
        LogicalFieldType.Object -> value is NormalizedValue.ObjectValue
        is LogicalFieldType.Array ->
            value is NormalizedValue.ListValue && value.values.all(::acceptsElement)
    }

internal fun QueryFieldSchema.acceptsOperand(
    operator: PredicateOperator,
    value: NormalizedValue,
): Boolean {
    if (value == NormalizedValue.Null) {
        return when (operator) {
            PredicateOperator.EQ,
            PredicateOperator.NE,
            PredicateOperator.IN,
            PredicateOperator.NOT_IN,
            PredicateOperator.ALL_IN,
            -> true

            else -> false
        }
    }
    val operandType = if (type is LogicalFieldType.Array) type.elementType else type
    return operandType.accepts(value)
}

internal fun QueryFieldSchema.hasOperandType(expected: LogicalFieldType): Boolean = type.operandType() == expected

private fun LogicalFieldType.Array.acceptsElement(value: NormalizedValue): Boolean =
    if (value == NormalizedValue.Null) {
        elementNullability == Nullability.NULLABLE
    } else {
        elementType.accepts(value)
    }

@ExperimentalQueryBackendApi
class QuerySearchScopeDefinition(
    val id: SearchScopeId,
    val owner: QueryFieldId.Path?,
    fields: Iterable<QueryFieldId.Path>,
    legacyAliases: Iterable<QueryFieldId.Path>,
) {
    val fields: List<QueryFieldId.Path>
    val legacyAliases: Set<QueryFieldId.Path> = Collections.unmodifiableSet(
        LinkedHashSet(legacyAliases.sortedWith(QUERY_FIELD_PATH_COMPARATOR)),
    )

    init {
        val materializedFields = fields.toList()
        require(materializedFields.isNotEmpty()) {
            "Search scope fields must not be empty."
        }
        require(materializedFields.distinct().size == materializedFields.size) {
            "Search scope fields must be unique."
        }
        this.fields = Collections.unmodifiableList(
            materializedFields.sortedWith(QUERY_FIELD_PATH_COMPARATOR),
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QuerySearchScopeDefinition &&
            id == other.id &&
            owner == other.owner &&
            fields == other.fields &&
            legacyAliases == other.legacyAliases

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (owner?.hashCode() ?: 0)
        result = 31 * result + fields.hashCode()
        result = 31 * result + legacyAliases.hashCode()
        return result
    }
}

private fun <E : Enum<E>> immutableEnumSet(values: Iterable<E>): Set<E> =
    Collections.unmodifiableSet(LinkedHashSet(values.sortedBy(Enum<E>::name)))

private fun LogicalFieldType.isOrderedScalar(): Boolean =
    this == LogicalFieldType.Int64 || this == LogicalFieldType.Decimal || this == LogicalFieldType.Instant

private fun LogicalFieldType.containsObjectValue(): Boolean =
    this == LogicalFieldType.Object || this is LogicalFieldType.Array && elementType.containsObjectValue()

private fun LogicalFieldType.isPortableScalar(): Boolean =
    this == LogicalFieldType.Text || this == LogicalFieldType.Boolean || isOrderedScalar()

private fun LogicalFieldType.operandType(): LogicalFieldType =
    if (this is LogicalFieldType.Array) elementType else this

internal val QUERY_FIELD_PATH_COMPARATOR: Comparator<QueryFieldId.Path> = Comparator { left, right ->
    compareSegments(left.segments, right.segments)
}

internal val QUERY_FIELD_ID_COMPARATOR: Comparator<QueryFieldId> = Comparator { left, right ->
    when {
        left is QueryFieldId.System && right is QueryFieldId.System -> left.kind.name.compareTo(right.kind.name)
        left is QueryFieldId.System -> -1
        right is QueryFieldId.System -> 1
        else -> QUERY_FIELD_PATH_COMPARATOR.compare(left as QueryFieldId.Path, right as QueryFieldId.Path)
    }
}

private fun compareSegments(left: List<String>, right: List<String>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) {
            return comparison
        }
    }
    return left.size.compareTo(right.size)
}

private fun PredicateOperator.requiredCapability(): FieldCapability =
    when (this) {
        PredicateOperator.IS_NULL,
        PredicateOperator.NOT_NULL,
        PredicateOperator.EXISTS,
        -> FieldCapability.PRESENCE

        PredicateOperator.GT,
        PredicateOperator.LT,
        PredicateOperator.GTE,
        PredicateOperator.LTE,
        PredicateOperator.BETWEEN,
        -> FieldCapability.RANGE

        PredicateOperator.CONTAINS,
        PredicateOperator.STARTS_WITH,
        PredicateOperator.ENDS_WITH,
        -> FieldCapability.LITERAL_PATTERN

        else -> FieldCapability.EXACT
    }
