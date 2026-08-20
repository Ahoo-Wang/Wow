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

package me.ahoo.wow.elasticsearch.query.gateway

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MatchNone
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.schema.QueryValueKind
import tools.jackson.databind.JsonNode
import java.time.Instant

internal class ElasticsearchQueryCompiler(private val snapshot: ElasticsearchExecutionSnapshot) {
    fun query(expression: QueryExpression): Query = compile(expression)

    fun sort(query: SecuredQuery, pit: Boolean): List<SortOptions> = buildList {
        query.sort.forEach { sort ->
            val field = snapshot.field(sort.field).sort ?: notReady()
            add(
                SortOptions.of { option ->
                    option.field { builder ->
                        builder.field(field)
                            .order(if (sort.direction == QuerySortDirection.ASC) SortOrder.Asc else SortOrder.Desc)
                    }
                }
            )
        }
        if (pit) {
            add(SortOptions.of { option -> option.field { field -> field.field(SHARD_DOC).order(SortOrder.Asc) } })
        }
    }

    private fun compile(expression: QueryExpression, prefix: String? = null): Query = when (expression) {
        MatchAll -> Query.of { it.matchAll { match -> match } }
        MatchNone -> Query.of { it.matchNone { match -> match } }
        is LogicalExpression -> logical(expression, prefix)
        is PredicateExpression -> predicate(expression, prefix)
        is LegacyConditionExpression -> SnapshotConditionConverter.convert(expression.condition)
        is ElementMatchExpression -> elementMatch(expression, prefix)
        is SearchExpression -> search(expression, prefix)
        is RelativeTimeExpression -> notReady()
    }

    private fun logical(expression: LogicalExpression, prefix: String?): Query {
        val operands = expression.operands.map { compile(it, prefix) }
        return Query.of { query ->
            query.bool { bool ->
                when (expression.operator) {
                    LogicalOperator.AND -> bool.filter(operands)
                    LogicalOperator.OR -> bool.should(operands).minimumShouldMatch("1")
                    LogicalOperator.NOR -> bool.mustNot(operands)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun predicate(expression: PredicateExpression, prefix: String?): Query {
        val logical = effective(expression.field, prefix)
        val field = snapshot.field(logical).exact
        return when (expression.operator) {
            PredicateOperator.EQ -> equality(logical, field, expression.values.single())
            PredicateOperator.NE -> unsupported()
            PredicateOperator.GT -> range(logical, field, "gt", expression.values.single())
            PredicateOperator.LT -> range(logical, field, "lt", expression.values.single())
            PredicateOperator.GTE -> range(logical, field, "gte", expression.values.single())
            PredicateOperator.LTE -> range(logical, field, "lte", expression.values.single())
            PredicateOperator.CONTAINS -> wildcard(
                field,
                "*${escape(expression.values.single().asString())}*",
                expression
            )
            PredicateOperator.IN -> membership(logical, field, expression.values)
            PredicateOperator.NOT_IN -> unsupported()
            PredicateOperator.BETWEEN -> between(logical, field, expression.values[0], expression.values[1])
            PredicateOperator.CONTAINS_ALL -> and(
                expression.values.map { value -> term(checkNotNull(field), fieldValue(logical, value)) }
            )

            PredicateOperator.STARTS_WITH -> wildcard(
                field,
                "${escape(expression.values.single().asString())}*",
                expression
            )
            PredicateOperator.ENDS_WITH -> wildcard(
                field,
                "*${escape(expression.values.single().asString())}",
                expression
            )
            PredicateOperator.IS_NULL -> unsupported()
            PredicateOperator.IS_NOT_NULL -> unsupported()

            PredicateOperator.IS_TRUE -> term(checkNotNull(field), FieldValue.TRUE)
            PredicateOperator.IS_FALSE -> term(checkNotNull(field), FieldValue.FALSE)
            PredicateOperator.EXISTS,
            PredicateOperator.IS_EMPTY -> unsupported()
        }
    }

    private fun equality(logical: LogicalField, field: String?, value: JsonNode): Query =
        if (value.isNull) {
            unsupported()
        } else {
            term(field ?: notReady(), fieldValue(logical, value))
        }

    private fun membership(
        logical: LogicalField,
        field: String?,
        values: List<JsonNode>
    ): Query {
        if (values.any(JsonNode::isNull)) unsupported()
        return Query.of { query ->
            query.terms { terms ->
                terms.field(field ?: notReady()).terms { value ->
                    value.value(values.map { fieldValue(logical, it) })
                }
            }
        }
    }

    private fun range(logical: LogicalField, field: String?, kind: String, value: JsonNode): Query = Query.of { query ->
        query.range { range ->
            range.untyped { untyped ->
                val builder = untyped.field(field ?: notReady())
                when (kind) {
                    "gt" -> builder.gt(jsonValue(logical, value))
                    "lt" -> builder.lt(jsonValue(logical, value))
                    "gte" -> builder.gte(jsonValue(logical, value))
                    "lte" -> builder.lte(jsonValue(logical, value))
                    else -> notReady()
                }
            }
        }
    }

    private fun between(logical: LogicalField, field: String?, start: JsonNode, end: JsonNode): Query = Query.of { query ->
        query.range { range ->
            range.untyped { untyped ->
                untyped.field(field ?: notReady())
                    .gte(jsonValue(logical, start))
                    .lte(jsonValue(logical, end))
            }
        }
    }

    private fun wildcard(field: String?, value: String, expression: PredicateExpression): Query = Query.of { query ->
        query.wildcard { wildcard ->
            wildcard.field(field ?: notReady())
                .value(value)
                .caseInsensitive(expression.stringComparison == StringComparison.CASE_INSENSITIVE)
        }
    }

    private fun elementMatch(expression: ElementMatchExpression, prefix: String?): Query {
        val logical = effective(expression.field, prefix)
        val nested = snapshot.field(logical).nested ?: notReady()
        return Query.of { query ->
            query.nested { builder -> builder.path(nested).query(compile(expression.predicate, logical.value)) }
        }
    }

    private fun search(expression: SearchExpression, prefix: String?): Query {
        val fields = expression.fields.map { field -> snapshot.field(effective(field, prefix)).search ?: notReady() }
        return Query.of { query -> query.multiMatch { match -> match.query(expression.query).fields(fields) } }
    }

    private fun term(field: String, value: FieldValue): Query = Query.of { query ->
        query.term { term -> term.field(field).value(value) }
    }

    private fun and(queries: List<Query>): Query = when (queries.size) {
        1 -> queries.single()
        else -> Query.of { query -> query.bool { bool -> bool.filter(queries) } }
    }

    private fun fieldValue(logical: LogicalField, value: JsonNode): FieldValue {
        return when (val encoded = encode(logical, value)) {
            null -> FieldValue.NULL
            is Boolean -> FieldValue.of(encoded)
            is Long -> FieldValue.of(encoded)
            is Double -> FieldValue.of(encoded)
            is String -> FieldValue.of(encoded)
            else -> FieldValue.of(JsonData.of(encoded))
        }
    }

    private fun jsonValue(logical: LogicalField, value: JsonNode): JsonData = JsonData.of(encode(logical, value))

    private fun encode(logical: LogicalField, value: JsonNode): Any? {
        val schema = snapshot.schema[logical] ?: notReady()
        return when {
            value.isNull -> null
            schema.valueKind == QueryValueKind.TIME && schema.system -> Instant.parse(value.asString()).toEpochMilli()
            value.isString -> value.asString()
            value.isBoolean -> value.booleanValue()
            value.isIntegralNumber && value.canConvertToLong() -> value.longValue()
            value.isIntegralNumber -> unsupported()
            value.isFloatingPointNumber -> value.decimalValue()
            else -> notReady()
        }
    }

    private fun effective(field: LogicalField, prefix: String?): LogicalField =
        if (prefix == null) field else LogicalField("$prefix.${field.value}")

    private fun unsupported(): Nothing = throw QueryException(QueryErrorCode.UNSUPPORTED_QUERY, QueryStage.BACKEND)

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            if (character == '*' || character == '?' || character == '\\') append('\\')
            append(character)
        }
    }

    private companion object {
        const val SHARD_DOC = "_shard_doc"
    }
}
