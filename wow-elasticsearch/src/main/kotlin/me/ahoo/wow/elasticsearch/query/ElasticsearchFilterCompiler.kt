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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchNone
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.multiMatch
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.prefix
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.script
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.*
import me.ahoo.wow.elasticsearch.query.aggregation.RuntimeExpressionCompiler
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ignoreCase
import me.ahoo.wow.query.requiredOperandValue

/**
 * Compiles admitted filters to Elasticsearch queries. Every field, system fields included, compiles at the physical
 * path its admission resolved; a root exact match on `_id` becomes an `ids` query.
 */
object ElasticsearchFilterCompiler {
    /** Compiles an admitted count filter. */
    fun compile(admitted: AdmittedQuery<FilterExpression>): Query = compile(admitted.query, admitted)

    /**
     * Compiles [filter], a filter of [admitted] at any scope: each field's resolution carries its absolute physical
     * path, which nested queries and nested aggregations both address.
     */
    fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>): Query =
        compileNormalized(filter, admitted, nested = false)

    /** [nested]: inside a nested query, where the document id is not addressable by `_id`. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compileNormalized(
        filter: FilterExpression,
        admitted: AdmittedQuery<*>,
        nested: Boolean,
    ): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is IdFilter -> exactEqual(admitted.systemPath(filter), filter.value, nested)
        is IdsFilter -> exactIn(admitted.systemPath(filter), filter.values, nested)
        is AggregateIdFilter -> exactEqual(admitted.systemPath(filter), filter.value, nested)
        is AggregateIdsFilter -> exactIn(admitted.systemPath(filter), filter.values, nested)
        is TenantIdFilter -> term {
            it.field(admitted.systemPath(filter)).value(filter.value)
        }
        is OwnerIdFilter -> term {
            it.field(admitted.systemPath(filter)).value(filter.value)
        }
        is SpaceIdFilter -> term {
            it.field(admitted.systemPath(filter)).value(filter.value)
        }
        is AndFilter -> bool {
            it.filter(filter.operands.map { operand -> compileNormalized(operand, admitted, nested) })
        }
        is OrFilter -> bool {
            it.should(filter.operands.map { operand -> compileNormalized(operand, admitted, nested) })
                .minimumShouldMatch("1")
        }
        is NorFilter -> bool {
            it.mustNot(filter.operands.map { operand -> compileNormalized(operand, admitted, nested) })
        }
        is EqualFilter -> {
            val field = admitted.physicalPath(filter.field)
            if (field.addressesDocumentId(nested)) {
                ids { it.values(filter.value.requiredOperandValue().toString()) }
            } else {
                term { it.field(field).value(filter.value.fieldValue()) }
            }
        }
        is NotEqualFilter -> bool {
            it.mustNot(compileNormalized(EqualFilter(filter.field, filter.value), admitted, nested))
        }
        is GreaterThanFilter -> range {
            it.untyped { range ->
                range.field(admitted.physicalPath(filter.field))
                    .gt(JsonData.of(filter.value.requiredOperandValue()))
            }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(admitted.physicalPath(filter.field))
                    .gte(JsonData.of(filter.value.requiredOperandValue()))
            }
        }
        is LessThanFilter -> range {
            it.untyped { range ->
                range.field(admitted.physicalPath(filter.field))
                    .lt(JsonData.of(filter.value.requiredOperandValue()))
            }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(admitted.physicalPath(filter.field))
                    .lte(JsonData.of(filter.value.requiredOperandValue()))
            }
        }
        is ContainsFilter -> wildcard {
            it.field(admitted.physicalPath(filter.field))
                .value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(admitted.physicalPath(filter.field)).value(filter.value)
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(admitted.physicalPath(filter.field))
                .value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> {
            val field = admitted.physicalPath(filter.field)
            if (field.addressesDocumentId(nested)) {
                ids { it.values(filter.values.map { value -> value.requiredOperandValue().toString() }) }
            } else {
                terms {
                    it.field(field).terms { terms ->
                        terms.value(filter.values.map { value -> value.fieldValue() })
                    }
                }
            }
        }
        is NotInFilter -> bool {
            it.mustNot(compileNormalized(InFilter(filter.field, filter.values), admitted, nested))
        }
        is BetweenFilter -> range {
            it.untyped { range ->
                range.field(admitted.physicalPath(filter.field))
                    .gte(JsonData.of(filter.lowerBound.requiredOperandValue()))
                    .lte(JsonData.of(filter.upperBound.requiredOperandValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(admitted.physicalPath(filter.field))
                    .terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool { it.mustNot(present(admitted.physicalPath(filter.field), nested)) }
        is IsNotNullFilter -> present(admitted.physicalPath(filter.field), nested)
        is ExistsFilter -> present(admitted.physicalPath(filter.field), nested)
        is NotExistsFilter -> bool { it.mustNot(present(admitted.physicalPath(filter.field), nested)) }
        is ElementMatchFilter -> nested {
            val nestedPath = admitted.physicalPath(filter.field)
            it.path(nestedPath).query(compileNormalized(filter.predicate, admitted, nested = true))
        }
        is ExpressionFilter -> script { query ->
            query.script(RuntimeExpressionCompiler(admitted).compileCondition(filter))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            if (filter.fields.isEmpty()) {
                it.lenient(true)
            } else {
                it.fields(filter.fields.map(admitted::physicalPath))
            }
            if (filter.mode == SearchMode.PHRASE) it.type(TextQueryType.Phrase)
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term {
                it.field(admitted.systemPath(filter)).value(false)
            }
            DeletionState.DELETED -> term {
                it.field(admitted.systemPath(filter)).value(true)
            }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool { it.mustNot(present(admitted.physicalPath(filter.field), nested)) }

        is IsEmptyStringFilter, is IsNotEmptyStringFilter, is RelativeTimeFilter ->
            throw QueryExecutionException("Filter [${filter.operator}] must be normalized before compilation.")
    }

    /**
     * Whether the document holds a value at [path]. A value longer than a keyword's `ignore_above` (or one
     * `ignore_malformed` drops) is not indexed, so `exists` misses it; Elasticsearch names such fields in the root
     * document's `_ignored`, which counts them as present. Without it a resource whose ABAC tag is over-long would
     * read as untagged, a public resource. Inside a nested query `_ignored`, a root document field, cannot be read.
     */
    private fun present(path: String, nested: Boolean): Query {
        val exists = exists { it.field(path) }
        if (nested) return exists
        return bool {
            it.should(exists, term { ignored -> ignored.field(IGNORED_FIELD).value(path) }).minimumShouldMatch("1")
        }
    }

    private const val IGNORED_FIELD = "_ignored"

    /** Inside a nested query the document id is not addressable by `_id`. */
    private fun String.addressesDocumentId(nested: Boolean): Boolean = !nested && this == DOCUMENT_ID_FIELD

    private fun exactEqual(path: String, value: String, nested: Boolean): Query =
        if (path.addressesDocumentId(nested)) {
            ids { it.values(value) }
        } else {
            term { it.field(path).value(value) }
        }

    private fun exactIn(path: String, values: List<String>, nested: Boolean): Query =
        if (path.addressesDocumentId(nested)) {
            ids { it.values(values) }
        } else {
            terms { it.field(path).terms { terms -> terms.value(values.map(FieldValue::of)) } }
        }

    private const val DOCUMENT_ID_FIELD = "_id"

    private fun tools.jackson.databind.JsonNode.fieldValue(): FieldValue {
        val value = requiredOperandValue()
        return when (value) {
            is String -> FieldValue.of(value)
            is Boolean -> FieldValue.of(value)
            else -> FieldValue.of(value)
        }
    }
}

private fun String.escapeWildcard(): String =
    replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("?", "\\?")
