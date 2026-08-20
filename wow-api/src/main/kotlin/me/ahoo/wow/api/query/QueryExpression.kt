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

package me.ahoo.wow.api.query

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

private val LOGICAL_FIELD_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.(?:[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*")
private val CAPABILITY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

data class LogicalField(
    @get:JsonValue val value: String
) {
    init {
        require(LOGICAL_FIELD_PATTERN.matches(value)) { "Logical field is invalid." }
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): LogicalField = LogicalField(value)
    }
}

data class QueryCapabilityId(
    @get:JsonValue val value: String
) {
    init {
        require(CAPABILITY_ID_PATTERN.matches(value)) { "Query capability id is invalid." }
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(value: String): QueryCapabilityId = QueryCapabilityId(value)
    }
}

object QueryCapabilities {
    @JvmStatic
    val FULL_TEXT: QueryCapabilityId = QueryCapabilityId("full-text")

    @JvmStatic
    val LEGACY_BACKEND: QueryCapabilityId = QueryCapabilityId("legacy-backend")
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(MatchAll::class, name = "match-all"),
    JsonSubTypes.Type(MatchNone::class, name = "match-none"),
    JsonSubTypes.Type(LogicalExpression::class, name = "logical"),
    JsonSubTypes.Type(PredicateExpression::class, name = "predicate"),
    JsonSubTypes.Type(ElementMatchExpression::class, name = "element-match"),
    JsonSubTypes.Type(SearchExpression::class, name = "search"),
    JsonSubTypes.Type(RelativeTimeExpression::class, name = "relative-time")
)
sealed interface QueryExpression

data object MatchAll : QueryExpression

data object MatchNone : QueryExpression

enum class LogicalOperator {
    AND,
    OR,
    NOR
}

data class LogicalExpression(
    val operator: LogicalOperator,
    val operands: List<QueryExpression>
) : QueryExpression {
    init {
        require(operands.isNotEmpty()) { "Logical expression operands cannot be empty." }
    }
}

enum class PredicateOperator {
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
    CONTAINS_ALL,
    STARTS_WITH,
    ENDS_WITH,
    IS_NULL,
    IS_NOT_NULL,
    IS_TRUE,
    IS_FALSE,
    EXISTS,
    IS_EMPTY
}

enum class StringComparison {
    DEFAULT,
    CASE_SENSITIVE,
    CASE_INSENSITIVE
}

data class PredicateExpression @JvmOverloads constructor(
    val field: LogicalField,
    val operator: PredicateOperator,
    val values: List<JsonNode> = emptyList(),
    val stringComparison: StringComparison = StringComparison.DEFAULT
) : QueryExpression

data class ElementMatchExpression(
    val field: LogicalField,
    val predicate: QueryExpression
) : QueryExpression

/** Compatibility-only expression evaluated by the selected legacy backend converter. */
data class LegacyConditionExpression(val condition: Condition) : QueryExpression

data class SearchExpression(
    val query: String,
    val fields: Set<LogicalField>
) : QueryExpression {
    init {
        require(query.isNotBlank()) { "Search query cannot be blank." }
        require(fields.isNotEmpty()) { "Search fields cannot be empty." }
    }
}

enum class RelativeTimeOperator {
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

data class RelativeTimeExpression @JvmOverloads constructor(
    val field: LogicalField,
    val operator: RelativeTimeOperator,
    val values: List<JsonNode> = emptyList(),
    val zoneId: String? = null
) : QueryExpression {
    init {
        require(zoneId == null || zoneId.isNotBlank()) { "Relative-time zone id cannot be blank." }
    }
}

object QueryExpressions {
    @JvmStatic
    fun eq(field: String, value: JsonNode): QueryExpression =
        PredicateExpression(LogicalField(field), PredicateOperator.EQ, listOf(value))

    @JvmStatic
    fun and(expressions: Collection<QueryExpression>): QueryExpression =
        LogicalExpression(LogicalOperator.AND, expressions.toList())

    @JvmStatic
    fun or(expressions: Collection<QueryExpression>): QueryExpression =
        LogicalExpression(LogicalOperator.OR, expressions.toList())

    @JvmStatic
    fun search(query: String, fields: Collection<String>): QueryExpression =
        SearchExpression(query, fields.mapTo(linkedSetOf(), ::LogicalField))
}
