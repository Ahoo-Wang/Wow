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

package me.ahoo.wow.api.query.expression

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.Collections

private val LOGICAL_FIELD_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")
private val CAPABILITY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

@JvmInline
value class LogicalField(val value: String) {
    init {
        require(LOGICAL_FIELD_PATTERN.matches(value)) { "Logical field is invalid." }
    }

    override fun toString(): String = value
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> {
    val snapshot = LinkedHashSet(values)
    require(snapshot.size == values.size) { "Query expression set cardinality changed during immutable snapshot." }
    return Collections.unmodifiableSet(snapshot)
}

private fun <K, V> immutableMap(values: Map<K, V>, validateKey: (K) -> Unit): Map<K, V> {
    val snapshot = LinkedHashMap<K, V>(values.size)
    values.forEach { (key, value) ->
        validateKey(key)
        snapshot[key] = value
    }
    require(snapshot.size == values.size) { "Query expression map cardinality changed during immutable snapshot." }
    return Collections.unmodifiableMap(snapshot)
}

@JvmInline
value class QueryCapabilityId(val value: String) {
    init {
        require(CAPABILITY_ID_PATTERN.matches(value)) { "Query capability id is invalid." }
    }

    override fun toString(): String = value
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(MatchAll::class, name = "match-all"),
    JsonSubTypes.Type(MatchNone::class, name = "match-none"),
    JsonSubTypes.Type(LogicalExpression::class, name = "logical"),
    JsonSubTypes.Type(PortableLogicalExpression::class, name = "portable-logical"),
    JsonSubTypes.Type(PredicateExpression::class, name = "predicate"),
    JsonSubTypes.Type(ElementMatchExpression::class, name = "element-match"),
    JsonSubTypes.Type(FullTextExpression::class, name = "full-text"),
    JsonSubTypes.Type(NativeExpression::class, name = "native"),
    JsonSubTypes.Type(RelativeTimeExpression::class, name = "relative-time")
)
sealed interface QueryExpression

sealed interface PortableExpression : QueryExpression

sealed interface CapabilityExpression : QueryExpression

data object MatchAll : PortableExpression

data object MatchNone : PortableExpression

enum class RelativeTimeOperation {
    TODAY,
    BEFORE_TODAY,
    TOMORROW,
    THIS_WEEK,
    NEXT_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    RECENT_DAYS,
    EARLIER_DAYS
}

/**
 * A pre-normalization relative-time expression resolved from the invocation's frozen instant.
 *
 * This descriptor is an external request value only. The query runtime eliminates it during normalization before
 * schema validation, policy evaluation, planning, or backend resolution.
 */
class RelativeTimeExpression @JvmOverloads constructor(
    val field: String,
    val operation: RelativeTimeOperation,
    operands: List<QueryValue> = emptyList(),
    val zoneId: String? = null
) : PortableExpression {
    val operands: List<QueryValue> = immutableList(operands)

    init {
        LogicalField(field)
        require(zoneId == null || zoneId.isNotBlank()) { "Relative-time zone id cannot be blank." }
    }

    operator fun component1(): String = field

    operator fun component2(): RelativeTimeOperation = operation

    operator fun component3(): List<QueryValue> = operands

    operator fun component4(): String? = zoneId

    fun copy(
        field: String = this.field,
        operation: RelativeTimeOperation = this.operation,
        operands: List<QueryValue> = this.operands,
        zoneId: String? = this.zoneId
    ): RelativeTimeExpression = RelativeTimeExpression(field, operation, operands, zoneId)

    override fun equals(other: Any?): Boolean = other is RelativeTimeExpression &&
        field == other.field && operation == other.operation && operands == other.operands && zoneId == other.zoneId

    override fun hashCode(): Int = 31 * (31 * (31 * field.hashCode() + operation.hashCode()) + operands.hashCode()) +
        (zoneId?.hashCode() ?: 0)

    override fun toString(): String =
        "RelativeTimeExpression(field=$field, operation=$operation, operands=$operands, zoneId=$zoneId)"
}

enum class LogicalOperator {
    AND,
    OR,
    NOR
}

class LogicalExpression(
    val operator: LogicalOperator,
    operands: List<QueryExpression>
) : QueryExpression {
    val operands: List<QueryExpression> = immutableList(operands)

    init {
        require(this.operands.isNotEmpty()) { "Logical expression operands cannot be empty." }
    }

    operator fun component1(): LogicalOperator = operator

    operator fun component2(): List<QueryExpression> = operands

    fun copy(
        operator: LogicalOperator = this.operator,
        operands: List<QueryExpression> = this.operands
    ): LogicalExpression = LogicalExpression(operator, operands)

    override fun equals(other: Any?): Boolean =
        other is LogicalExpression && operator == other.operator && operands == other.operands

    override fun hashCode(): Int = 31 * operator.hashCode() + operands.hashCode()

    override fun toString(): String = "LogicalExpression(operator=$operator, operands=$operands)"
}

class PortableLogicalExpression(
    val operator: LogicalOperator,
    operands: List<PortableExpression>
) : PortableExpression {
    val operands: List<PortableExpression> = immutableList(operands)

    init {
        require(this.operands.isNotEmpty()) { "Portable logical expression operands cannot be empty." }
    }

    operator fun component1(): LogicalOperator = operator

    operator fun component2(): List<PortableExpression> = operands

    fun copy(
        operator: LogicalOperator = this.operator,
        operands: List<PortableExpression> = this.operands
    ): PortableLogicalExpression = PortableLogicalExpression(operator, operands)

    override fun equals(other: Any?): Boolean =
        other is PortableLogicalExpression && operator == other.operator && operands == other.operands

    override fun hashCode(): Int = 31 * operator.hashCode() + operands.hashCode()

    override fun toString(): String = "PortableLogicalExpression(operator=$operator, operands=$operands)"
}

enum class PortableOperator {
    EQ,
    NE,
    GT,
    LT,
    GTE,
    LTE,
    CONTAINS,
    IN,
    NOT_IN,
    BETWEEN,
    ALL_IN,
    STARTS_WITH,
    ENDS_WITH,
    NULL,
    NOT_NULL,
    TRUE,
    FALSE,
    EXISTS
}

enum class StringComparisonMode {
    DEFAULT,
    CASE_SENSITIVE,
    CASE_INSENSITIVE
}

class PredicateExpression @JvmOverloads constructor(
    val field: LogicalField,
    val operator: PortableOperator,
    values: List<QueryValue>,
    val stringComparison: StringComparisonMode = StringComparisonMode.DEFAULT
) : PortableExpression {
    val values: List<QueryValue> = immutableList(values)

    init {
        require(
            stringComparison == StringComparisonMode.DEFAULT ||
                operator == PortableOperator.CONTAINS ||
                operator == PortableOperator.STARTS_WITH ||
                operator == PortableOperator.ENDS_WITH
        ) { "String comparison mode requires a string matching operator." }
    }

    operator fun component1(): LogicalField = field

    operator fun component2(): PortableOperator = operator

    operator fun component3(): List<QueryValue> = values

    operator fun component4(): StringComparisonMode = stringComparison

    fun copy(
        field: LogicalField = this.field,
        operator: PortableOperator = this.operator,
        values: List<QueryValue> = this.values,
        stringComparison: StringComparisonMode = this.stringComparison
    ): PredicateExpression = PredicateExpression(field, operator, values, stringComparison)

    override fun equals(other: Any?): Boolean =
        other is PredicateExpression && field == other.field && operator == other.operator && values == other.values &&
            stringComparison == other.stringComparison

    override fun hashCode(): Int =
        31 * (31 * (31 * field.hashCode() + operator.hashCode()) + values.hashCode()) + stringComparison.hashCode()

    override fun toString(): String =
        "PredicateExpression(field=$field, operator=$operator, values=$values, stringComparison=$stringComparison)"
}

data class ElementMatchExpression(
    val field: LogicalField,
    val predicate: PortableExpression
) : PortableExpression

class FullTextExpression(
    val capabilityId: QueryCapabilityId,
    val query: String,
    fields: Set<LogicalField>
) : CapabilityExpression {
    val fields: Set<LogicalField> = immutableSet(fields)

    init {
        require(query.isNotBlank()) { "Full-text query cannot be blank." }
        require(this.fields.isNotEmpty()) { "Full-text fields cannot be empty." }
    }

    operator fun component1(): QueryCapabilityId = capabilityId

    operator fun component2(): String = query

    operator fun component3(): Set<LogicalField> = fields

    fun copy(
        capabilityId: QueryCapabilityId = this.capabilityId,
        query: String = this.query,
        fields: Set<LogicalField> = this.fields
    ): FullTextExpression = FullTextExpression(capabilityId, query, fields)

    override fun equals(other: Any?): Boolean =
        other is FullTextExpression && capabilityId == other.capabilityId && query == other.query && fields == other.fields

    override fun hashCode(): Int = 31 * (31 * capabilityId.hashCode() + query.hashCode()) + fields.hashCode()

    override fun toString(): String = "FullTextExpression(capabilityId=$capabilityId, query=$query, fields=$fields)"
}

class NativeExpression(
    val capabilityId: QueryCapabilityId,
    val backendId: String,
    val templateId: String,
    parameters: Map<String, QueryValue>,
    declaredFields: Set<LogicalField>
) : CapabilityExpression {
    val parameters: Map<String, QueryValue> = immutableMap(parameters) { key ->
        require(key.isNotBlank()) { "Native parameter name cannot be blank." }
    }
    val declaredFields: Set<LogicalField> = immutableSet(declaredFields)

    init {
        require(backendId.isNotBlank()) { "Native backend id cannot be blank." }
        require(templateId.isNotBlank()) { "Native template id cannot be blank." }
        require(this.declaredFields.isNotEmpty()) { "Native declared fields cannot be empty." }
    }

    operator fun component1(): QueryCapabilityId = capabilityId

    operator fun component2(): String = backendId

    operator fun component3(): String = templateId

    operator fun component4(): Map<String, QueryValue> = parameters

    operator fun component5(): Set<LogicalField> = declaredFields

    fun copy(
        capabilityId: QueryCapabilityId = this.capabilityId,
        backendId: String = this.backendId,
        templateId: String = this.templateId,
        parameters: Map<String, QueryValue> = this.parameters,
        declaredFields: Set<LogicalField> = this.declaredFields
    ): NativeExpression = NativeExpression(capabilityId, backendId, templateId, parameters, declaredFields)

    override fun equals(other: Any?): Boolean = other is NativeExpression &&
        capabilityId == other.capabilityId && backendId == other.backendId && templateId == other.templateId &&
        parameters == other.parameters && declaredFields == other.declaredFields

    override fun hashCode(): Int {
        var result = capabilityId.hashCode()
        result = 31 * result + backendId.hashCode()
        result = 31 * result + templateId.hashCode()
        result = 31 * result + parameters.hashCode()
        return 31 * result + declaredFields.hashCode()
    }

    override fun toString(): String =
        "NativeExpression(capabilityId=$capabilityId, backendId=$backendId, templateId=$templateId, " +
            "parameterNames=${parameters.keys}, declaredFields=$declaredFields)"
}
