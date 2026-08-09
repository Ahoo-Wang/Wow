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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.match
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchNone
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import co.elastic.clients.json.JsonData
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRecordQueryPlan
import me.ahoo.wow.query.backend.BackendRecordResultPlan
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.CaseSensitivity
import me.ahoo.wow.query.backend.JunctionOperator
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.SystemFieldKind

internal data class ElasticsearchCompiledRecordQuery(
    val query: Query,
    val sourceFilter: SourceFilter?,
    val sort: List<SortOptions>,
    val limit: Int?,
    val page: BackendPageWindow?,
)

internal class ElasticsearchRecordQueryCompiler(
    private val binding: ElasticsearchPreparedQueryBinding,
) {
    constructor(binding: ElasticsearchSnapshotQueryBinding) : this(binding.prepared)

    fun compile(plan: BackendRecordQueryPlan): ElasticsearchCompiledRecordQuery {
        if (plan.target != binding.schema.target || plan.schemaContractId != binding.schema.contractId) {
            unsupported()
        }
        val result = plan as? BackendRecordResultPlan
        return ElasticsearchCompiledRecordQuery(
            compileCondition(plan.filter.condition),
            result?.projection?.let(::compileProjection),
            result?.sort?.map { sort ->
                SortOptions.of { option ->
                    option.field { field ->
                        field.field(requireField(sort.field).sortField ?: unsupported())
                            .order(
                                when (sort.direction) {
                                    NormalizedSortDirection.ASC -> SortOrder.Asc
                                    NormalizedSortDirection.DESC -> SortOrder.Desc
                                },
                            )
                    }
                }
            }.orEmpty(),
            when (plan) {
                is BackendSingleQueryPlan -> 1
                is BackendStreamQueryPlan -> plan.limit
                is BackendPageQueryPlan,
                is BackendCountQueryPlan,
                -> null
            },
            (plan as? BackendPageQueryPlan)?.page,
        )
    }

    internal fun compileCondition(condition: BackendPlannedCondition): Query =
        when (condition) {
            BackendPlannedCondition.All -> matchAll { it }
            BackendPlannedCondition.None -> matchNone { it }
            is BackendPlannedCondition.Junction -> compileJunction(condition)
            is BackendPlannedCondition.Predicate -> compilePredicate(condition)
            is BackendPlannedCondition.ElementMatch -> compileElementMatch(condition)
            is BackendPlannedCondition.Search -> compileSearch(condition)
            is BackendPlannedCondition.Native -> unsupported()
        }

    private fun compileJunction(condition: BackendPlannedCondition.Junction): Query {
        val children = condition.children.map(::compileCondition)
        return when (condition.operator) {
            JunctionOperator.AND -> bool { builder -> builder.filter(children) }
            JunctionOperator.OR -> bool { builder -> builder.should(children).minimumShouldMatch("1") }
            JunctionOperator.NOR -> bool { builder -> builder.mustNot(children) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun compilePredicate(predicate: BackendPlannedCondition.Predicate): Query {
        if (predicate.options.caseSensitivity != CaseSensitivity.SENSITIVE) {
            unsupported()
        }
        return when (predicate.operator) {
            PredicateOperator.EQ -> compileEqual(predicate)
            PredicateOperator.NE -> negate(compileEqual(predicate))
            PredicateOperator.GT -> compileRange(predicate, RangeBound.GT)
            PredicateOperator.LT -> compileRange(predicate, RangeBound.LT)
            PredicateOperator.GTE -> compileRange(predicate, RangeBound.GTE)
            PredicateOperator.LTE -> compileRange(predicate, RangeBound.LTE)
            PredicateOperator.CONTAINS -> compileLiteral(predicate, LiteralKind.CONTAINS)
            PredicateOperator.IN -> compileIn(predicate, negated = false)
            PredicateOperator.NOT_IN -> compileIn(predicate, negated = true)
            PredicateOperator.BETWEEN -> compileBetween(predicate)
            PredicateOperator.ALL_IN -> compileAllIn(predicate)
            PredicateOperator.STARTS_WITH -> compileLiteral(predicate, LiteralKind.STARTS_WITH)
            PredicateOperator.ENDS_WITH -> compileLiteral(predicate, LiteralKind.ENDS_WITH)
            PredicateOperator.IS_NULL -> compileNull(predicate)
            PredicateOperator.NOT_NULL -> negate(compileNull(predicate))
            PredicateOperator.IS_TRUE -> compileTerm(predicate, NormalizedValue.BooleanValue(true))
            PredicateOperator.IS_FALSE -> compileTerm(predicate, NormalizedValue.BooleanValue(false))
            PredicateOperator.EXISTS -> {
                val exists = predicate.value as? NormalizedValue.BooleanValue ?: unsupported()
                if (exists.value) present(predicate) else missing(predicate)
            }
        }
    }

    private fun compileEqual(predicate: BackendPlannedCondition.Predicate): Query {
        val value = requireNotNull(predicate.value)
        if (value == NormalizedValue.Null) {
            return compileNull(predicate)
        }
        return compileTerm(predicate, value)
    }

    private fun compileTerm(predicate: BackendPlannedCondition.Predicate, value: NormalizedValue): Query {
        val physical = requireField(predicate.field).exactField ?: unsupported()
        if (physical == ES_ID_FIELD) {
            val text = value as? NormalizedValue.Text ?: unsupported()
            return ids { builder -> builder.values(text.value) }
        }
        return term { builder -> builder.field(physical).value(value.toFieldValue(predicate.field)) }
    }

    private fun compileIn(predicate: BackendPlannedCondition.Predicate, negated: Boolean): Query {
        val values = predicate.value.requireList()
        val nonNull = values.filterNot { value -> value == NormalizedValue.Null }
        val containsNull = values.any { value -> value == NormalizedValue.Null }
        val terms = if (nonNull.isEmpty()) {
            matchNone { it }
        } else {
            compileTerms(predicate.field, nonNull)
        }
        val combined = if (!negated && values.any { value -> value == NormalizedValue.Null }) {
            bool { builder -> builder.should(terms, compileNull(predicate)).minimumShouldMatch("1") }
        } else {
            terms
        }
        if (!negated) return combined
        if (!containsNull) return negate(combined)
        val nonNullValues = if (nonNull.isEmpty()) matchAll { it } else negate(terms)
        val explicitNonNull = bool { builder -> builder.must(present(predicate), nonNullValues) }
        return bool { builder -> builder.should(missing(predicate), explicitNonNull).minimumShouldMatch("1") }
    }

    private fun compileTerms(field: QueryFieldId, values: List<NormalizedValue>): Query {
        val physical = requireField(field).exactField ?: unsupported()
        if (physical == ES_ID_FIELD) {
            return ids { builder ->
                builder.values(values.map { value -> (value as? NormalizedValue.Text)?.value ?: unsupported() })
            }
        }
        return terms { builder ->
            builder.field(physical).terms { terms ->
                terms.value(values.map { value -> value.toFieldValue(field) })
            }
        }
    }

    private fun compileAllIn(predicate: BackendPlannedCondition.Predicate): Query {
        val values = predicate.value.requireList()
        if (values.any { value -> value == NormalizedValue.Null }) {
            unsupported()
        }
        val physical = requireField(predicate.field).exactField ?: unsupported()
        return termsSet { builder ->
            builder.field(physical)
                .terms(values.map { value -> value.toFieldValue(predicate.field) })
                .minimumShouldMatch(values.size.toString())
        }
    }

    private fun compileBetween(predicate: BackendPlannedCondition.Predicate): Query {
        val values = predicate.value.requireList()
        if (values.size != 2 || values.any { value -> value == NormalizedValue.Null }) {
            unsupported()
        }
        val physical = requireField(predicate.field).rangeField ?: unsupported()
        return range { builder ->
            builder.untyped { range ->
                range.field(physical)
                    .gte(values[0].toJsonData(predicate.field))
                    .lte(values[1].toJsonData(predicate.field))
            }
        }
    }

    private fun compileRange(predicate: BackendPlannedCondition.Predicate, bound: RangeBound): Query {
        val value = requireNotNull(predicate.value)
        if (value == NormalizedValue.Null) unsupported()
        val physical = requireField(predicate.field).rangeField ?: unsupported()
        return range { builder ->
            builder.untyped { range ->
                range.field(physical).also {
                    when (bound) {
                        RangeBound.GT -> range.gt(value.toJsonData(predicate.field))
                        RangeBound.LT -> range.lt(value.toJsonData(predicate.field))
                        RangeBound.GTE -> range.gte(value.toJsonData(predicate.field))
                        RangeBound.LTE -> range.lte(value.toJsonData(predicate.field))
                    }
                }
            }
        }
    }

    private fun compileLiteral(
        predicate: BackendPlannedCondition.Predicate,
        kind: LiteralKind,
    ): Query {
        val physical = requireField(predicate.field).literalField ?: unsupported()
        val literal = (predicate.value as? NormalizedValue.Text)?.value ?: unsupported()
        val escaped = literal.escapeWildcard()
        val pattern = when (kind) {
            LiteralKind.CONTAINS -> "*$escaped*"
            LiteralKind.STARTS_WITH -> "$escaped*"
            LiteralKind.ENDS_WITH -> "*$escaped"
        }
        return wildcard { builder -> builder.field(physical).value(pattern).caseInsensitive(false) }
    }

    private fun present(predicate: BackendPlannedCondition.Predicate): Query {
        val physical = requireField(predicate.field).presenceField ?: unsupported()
        return term { builder -> builder.field(physical).value(true) }
    }

    private fun missing(predicate: BackendPlannedCondition.Predicate): Query = negate(present(predicate))

    private fun compileNull(predicate: BackendPlannedCondition.Predicate): Query {
        val field = requireField(predicate.field)
        val exact = field.exactField ?: unsupported()
        return negate(exists { builder -> builder.field(exact) })
    }

    private fun compileElementMatch(condition: BackendPlannedCondition.ElementMatch): Query {
        val field = requireField(condition.field)
        val nestedPath = field.nestedPath ?: unsupported()
        return nested { builder -> builder.path(nestedPath).query(compileCondition(condition.condition)) }
    }

    private fun compileSearch(condition: BackendPlannedCondition.Search): Query {
        val scope = binding.searchScopes[condition.scope] ?: unsupported()
        val searches = scope.fields.values.map { physical ->
            match { builder -> builder.field(physical).query(condition.text) }
        }
        return if (searches.size == 1) {
            searches.single()
        } else {
            bool { builder -> builder.should(searches).minimumShouldMatch("1") }
        }
    }

    private fun compileProjection(projection: BackendProjection): SourceFilter? =
        when (projection) {
            BackendProjection.All -> null
            is BackendProjection.Include -> SourceFilter.of { filter ->
                filter.includes(
                    canonicalSourcePaths(
                        projection.fields + QueryFieldId.System(SystemFieldKind.IDENTITY),
                    ),
                )
            }

            is BackendProjection.Exclude -> SourceFilter.of { filter ->
                val identitySource = requireField(QueryFieldId.System(SystemFieldKind.IDENTITY)).sourceField
                filter.excludes(
                    canonicalSourcePaths(projection.fields).filterNot { path -> path == identitySource },
                )
            }
        }

    private fun canonicalSourcePaths(fields: List<QueryFieldId>): List<String> {
        val paths = fields.map { field -> requireField(field).sourceField }
        return paths.distinct().filter { candidate ->
            paths.none { other -> other != candidate && candidate.startsWith("$other.") }
        }.sorted()
    }

    private fun requireField(field: QueryFieldId): ElasticsearchFieldBinding =
        binding.fields[field] ?: unsupported()

    private fun NormalizedValue.toFieldValue(field: QueryFieldId): FieldValue =
        when (this) {
            is NormalizedValue.Text -> FieldValue.of(value)
            is NormalizedValue.BooleanValue -> FieldValue.of(value)
            is NormalizedValue.Int64 -> FieldValue.of(value)
            is NormalizedValue.InstantValue -> {
                if (requireField(field).valueEncoding != ElasticsearchValueEncoding.EPOCH_MILLIS) unsupported()
                FieldValue.of(value.toEpochMilli())
            }

            NormalizedValue.Null,
            is NormalizedValue.Decimal,
            is NormalizedValue.Bytes,
            is NormalizedValue.ListValue,
            is NormalizedValue.ObjectValue,
            -> unsupported()
        }

    private fun NormalizedValue.toJsonData(field: QueryFieldId): JsonData =
        JsonData.of(toFieldValue(field)._get())

    private fun NormalizedValue?.requireList(): List<NormalizedValue> =
        (this as? NormalizedValue.ListValue)?.values ?: unsupported()

    private fun negate(query: Query): Query = bool { builder -> builder.mustNot(query) }

    private fun String.escapeWildcard(): String = buildString(length + 4) {
        this@escapeWildcard.forEach { char ->
            if (char in WILDCARD_META) append('\\')
            append(char)
        }
    }

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private enum class RangeBound {
        GT,
        LT,
        GTE,
        LTE,
    }

    private enum class LiteralKind {
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
    }

    private companion object {
        const val ES_ID_FIELD = "_id"
        val WILDCARD_META = setOf('\\', '*', '?')
    }
}
