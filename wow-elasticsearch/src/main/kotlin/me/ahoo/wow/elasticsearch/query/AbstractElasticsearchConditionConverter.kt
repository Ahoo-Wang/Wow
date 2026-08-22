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
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.match
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchNone
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.multiMatch
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.prefix
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.*
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.converter.AbstractConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

abstract class AbstractElasticsearchConditionConverter(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
    private val documentIdField: String? = null,
) : AbstractConditionConverter<Query>() {
    private val filterNormalizer = FilterNormalizer(defaultDeletionState = defaultDeletionState)

    fun convert(filter: FilterExpression): Query = internalConvert(filterNormalizer.normalize(filter))

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun internalConvert(filter: FilterExpression): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is AndFilter -> bool { it.filter(filter.operands.map(::internalConvert)) }
        is OrFilter -> bool { it.should(filter.operands.map(::internalConvert)).minimumShouldMatch("1") }
        is NorFilter -> bool { it.mustNot(filter.operands.map(::internalConvert)) }
        is EqualFilter -> if (filter.field.isDocumentId) {
            documentIdEqual(filter)
        } else {
            term { it.field(filter.field.value).value(filter.value.fieldValue()) }
        }
        is NotEqualFilter -> bool { it.mustNot(internalConvert(EqualFilter(filter.field, filter.value))) }
        is GreaterThanFilter -> range {
            it.untyped { range -> range.field(filter.field.value).gt(JsonData.of(filter.value.requiredNativeValue())) }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range -> range.field(filter.field.value).gte(JsonData.of(filter.value.requiredNativeValue())) }
        }
        is LessThanFilter -> range {
            it.untyped { range -> range.field(filter.field.value).lt(JsonData.of(filter.value.requiredNativeValue())) }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range -> range.field(filter.field.value).lte(JsonData.of(filter.value.requiredNativeValue())) }
        }
        is ContainsFilter -> wildcard {
            it.field(filter.field.value).value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(filter.field.value).value(filter.value).caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(filter.field.value).value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> if (filter.field.isDocumentId) {
            documentIdIn(filter)
        } else {
            terms {
                it.field(
                    filter.field.value
                ).terms { terms -> terms.value(filter.values.map { value -> value.fieldValue() }) }
            }
        }
        is NotInFilter -> bool { it.mustNot(internalConvert(InFilter(filter.field, filter.values))) }
        is BetweenFilter -> range {
            it.untyped {
                it.field(filter.field.value)
                    .gte(JsonData.of(filter.lowerBound.requiredNativeValue()))
                    .lte(JsonData.of(filter.upperBound.requiredNativeValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(filter.field.value).terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool { it.mustNot { query -> query.exists { exists -> exists.field(filter.field.value) } } }
        is IsNotNullFilter -> exists { it.field(filter.field.value) }
        is ExistsFilter -> exists { it.field(filter.field.value) }
        is NotExistsFilter -> bool {
            it.mustNot { query -> query.exists { exists -> exists.field(filter.field.value) } }
        }
        is ElementMatchFilter -> nested {
            it.path(filter.field.value).query(internalConvert(filter.predicate))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            if (filter.fields.isNotEmpty()) it.fields(filter.fields.map(LogicalField::value))
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term { it.field(StateAggregateRecords.DELETED).value(false) }
            DeletionState.DELETED -> term { it.field(StateAggregateRecords.DELETED).value(true) }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool {
            it.mustNot { query -> query.exists { exists -> exists.field(filter.field.value) } }
        }
        is TodayFilter,
        is BeforeTodayFilter,
        is TomorrowFilter,
        is ThisWeekFilter,
        is NextWeekFilter,
        is LastWeekFilter,
        is ThisMonthFilter,
        is LastMonthFilter,
        is RecentDaysFilter,
        is EarlierDaysFilter,
        -> error("Relative-time filter must be normalized before compilation.")
    }

    private val LogicalField.isDocumentId: Boolean
        get() = value == "_id"

    private fun documentIdEqual(filter: EqualFilter): Query = documentIdField?.let { field ->
        term { it.field(field).value(filter.value.fieldValue()) }
    } ?: ids { it.values(filter.value.requiredNativeValue().toString()) }

    private fun documentIdIn(filter: InFilter): Query = documentIdField?.let { field ->
        terms {
            it.field(field).terms { terms -> terms.value(filter.values.map { value -> value.fieldValue() }) }
        }
    } ?: ids { it.values(filter.values.map { value -> value.requiredNativeValue().toString() }) }

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.requiredNativeValue(): Any = when {
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        else -> throw IllegalArgumentException("Filter value must be a non-null JSON scalar.")
    }

    private fun tools.jackson.databind.JsonNode.fieldValue(): FieldValue = when {
        isString -> FieldValue.of(asString())
        isBoolean -> FieldValue.of(booleanValue())
        else -> FieldValue.of(requiredNativeValue())
    }
    override fun and(condition: Condition): Query {
        return bool { builder ->
            builder.filter(condition.children.map { internalConvert(it) })
        }
    }

    override fun or(condition: Condition): Query {
        return bool { builder ->
            builder.should(condition.children.map { internalConvert(it) })
                .minimumShouldMatch("1")
        }
    }

    override fun nor(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(condition.children.map { internalConvert(it) })
        }
    }

    override fun id(condition: Condition): Query {
        return ids {
            it.values(condition.valueAs<String>())
        }
    }

    override fun ids(condition: Condition): Query {
        return ids {
            it.values(condition.valueAs<List<String>>())
        }
    }

    override fun tenantId(condition: Condition): Query {
        return term {
            it.field(MessageRecords.TENANT_ID)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun ownerId(condition: Condition): Query {
        return term {
            it.field(MessageRecords.OWNER_ID)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun spaceId(condition: Condition): Query {
        return term {
            it.field(MessageRecords.SPACE_ID)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun all(condition: Condition): Query {
        return matchAll { it }
    }

    override fun eq(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun ne(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(eq(condition))
        }
    }

    override fun gt(condition: Condition): Query {
        return range {
            it.untyped {
                it.field(condition.field)
                    .gt(JsonData.of(condition.value))
            }
        }
    }

    override fun lt(condition: Condition): Query {
        return range {
            it.untyped {
                it.field(condition.field)
                    .lt(JsonData.of(condition.value))
            }
        }
    }

    override fun gte(condition: Condition): Query {
        return range {
            it.untyped {
                it.field(condition.field)
                    .gte(JsonData.of(condition.value))
            }
        }
    }

    override fun lte(condition: Condition): Query {
        return range {
            it.untyped {
                it.field(condition.field)
                    .lte(JsonData.of(condition.value))
            }
        }
    }

    override fun contains(condition: Condition): Query {
        return wildcard {
            it.field(condition.field)
                .value("*${condition.valueAs<String>().escapeWildcard()}*")
                .caseInsensitive(condition.ignoreCase())
        }
    }

    override fun match(condition: Condition): Query =
        match {
            it
                .field(condition.field)
                .query(condition.valueAs<String>())
        }

    override fun isIn(condition: Condition): Query {
        return terms {
            it.field(condition.field)
                .terms { builder ->
                    condition.valueAs<List<Any>>().map {
                        FieldValue.of(it)
                    }.toList().let { builder.value(it) }
                }
        }
    }

    override fun notIn(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(isIn(condition))
        }
    }

    override fun between(condition: Condition): Query {
        val valueIterable = condition.valueAs<Iterable<Any>>()
        val ite = valueIterable.iterator()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val first = ite.next()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val second = ite.next()
        return range {
            it.untyped {
                it.field(condition.field)
                    .gte(JsonData.of(first))
                    .lte(JsonData.of(second))
            }
        }
    }

    override fun allIn(condition: Condition): Query {
        val values = condition.valueAs<List<Any>>().map {
            FieldValue.of(it)
        }
        return termsSet { builder ->
            builder.field(condition.field)
                .terms(values)
                .minimumShouldMatch(values.size.toString())
        }
    }

    override fun startsWith(condition: Condition): Query {
        return prefix {
            it.field(condition.field)
                .value(condition.valueAs<String>())
                .caseInsensitive(condition.ignoreCase())
        }
    }

    override fun endsWith(condition: Condition): Query {
        return wildcard {
            it.field(condition.field)
                .value("*${condition.valueAs<String>().escapeWildcard()}")
                .caseInsensitive(condition.ignoreCase())
        }
    }

    override fun elemMatch(condition: Condition): Query {
        return nested {
            it.path(condition.field)
                .query(
                    bool { builder ->
                        builder.filter(condition.children.map { internalConvert(it) })
                    }
                )
        }
    }

    override fun isNull(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot {
                it.exists {
                    it.field(condition.field)
                }
            }
        }
    }

    override fun notNull(condition: Condition): Query {
        return exists {
            it.field(condition.field)
        }
    }

    override fun isTrue(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.TRUE)
        }
    }

    override fun isFalse(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.FALSE)
        }
    }

    override fun exists(condition: Condition): Query {
        val exists = condition.valueAs<Boolean>()
        val existsQuery = exists {
            it.field(condition.field)
        }
        if (exists) {
            return existsQuery
        }
        return bool { builder ->
            builder.mustNot(existsQuery)
        }
    }

    override fun deleted(condition: Condition): Query {
        return when (condition.deletionState()) {
            DeletionState.ACTIVE -> {
                term {
                    it.field(StateAggregateRecords.DELETED)
                        .value(false)
                }
            }

            DeletionState.DELETED -> {
                term {
                    it.field(StateAggregateRecords.DELETED)
                        .value(true)
                }
            }

            DeletionState.ALL -> {
                matchAll {
                    it
                }
            }
        }
    }
}

private fun String.escapeWildcard(): String =
    replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("?", "\\?")
