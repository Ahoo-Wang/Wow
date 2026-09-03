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
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.*
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

abstract class AbstractElasticsearchFilterCompiler(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
    private val documentIdField: String? = null,
) {
    private val filterNormalizer = FilterNormalizer(defaultDeletionState = defaultDeletionState)

    fun compile(filter: FilterExpression, parent: String? = null): Query =
        compileNormalized(filterNormalizer.normalize(filter), parent)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compileNormalized(filter: FilterExpression, parent: String?): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is IdFilter -> documentIdEqual(filter.value)
        is IdsFilter -> documentIdIn(filter.values)
        is AggregateIdFilter -> aggregateIdEqual(filter.value)
        is AggregateIdsFilter -> aggregateIdIn(filter.values)
        is TenantIdFilter -> term { it.field(MessageRecords.TENANT_ID).value(filter.value) }
        is OwnerIdFilter -> term { it.field(MessageRecords.OWNER_ID).value(filter.value) }
        is SpaceIdFilter -> term { it.field(MessageRecords.SPACE_ID).value(filter.value) }
        is AndFilter -> bool { it.filter(filter.operands.map { operand -> compileNormalized(operand, parent) }) }
        is OrFilter -> bool {
            it.should(filter.operands.map { operand -> compileNormalized(operand, parent) }).minimumShouldMatch("1")
        }
        is NorFilter -> bool { it.mustNot(filter.operands.map { operand -> compileNormalized(operand, parent) }) }
        is EqualFilter -> if (parent == null && filter.field.isDocumentId) {
            documentIdEqual(filter.value.requiredNativeValue().toString())
        } else {
            term { it.field(filter.field.path(parent)).value(filter.value.fieldValue()) }
        }
        is NotEqualFilter -> bool {
            it.mustNot(compileNormalized(EqualFilter(filter.field, filter.value), parent))
        }
        is GreaterThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(parent)).gt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(parent)).gte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(parent)).lt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(parent)).lte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is ContainsFilter -> wildcard {
            it.field(filter.field.path(parent)).value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(filter.field.path(parent)).value(filter.value)
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(filter.field.path(parent)).value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> if (parent == null && filter.field.isDocumentId) {
            documentIdIn(filter.values.map { value -> value.requiredNativeValue().toString() })
        } else {
            terms {
                it.field(filter.field.path(parent))
                    .terms { terms -> terms.value(filter.values.map { value -> value.fieldValue() }) }
            }
        }
        is NotInFilter -> bool {
            it.mustNot(compileNormalized(InFilter(filter.field, filter.values), parent))
        }
        is BetweenFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(parent))
                    .gte(JsonData.of(filter.lowerBound.requiredNativeValue()))
                    .lte(JsonData.of(filter.upperBound.requiredNativeValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(filter.field.path(parent)).terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool {
            it.mustNot { query -> query.exists { exists -> exists.field(filter.field.path(parent)) } }
        }
        is IsNotNullFilter -> exists { it.field(filter.field.path(parent)) }
        is ExistsFilter -> exists { it.field(filter.field.path(parent)) }
        is NotExistsFilter -> bool {
            it.mustNot { query -> query.exists { exists -> exists.field(filter.field.path(parent)) } }
        }
        is ElementMatchFilter -> nested {
            val nestedPath = filter.field.path(parent)
            it.path(nestedPath).query(compileNormalized(filter.predicate, nestedPath))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            if (filter.fields.isEmpty()) {
                it.lenient(true)
            } else {
                it.fields(filter.fields.map { field -> field.path(parent) })
            }
            if (filter.mode == SearchMode.PHRASE) it.type(TextQueryType.Phrase)
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term { it.field(StateAggregateRecords.DELETED).value(false) }
            DeletionState.DELETED -> term { it.field(StateAggregateRecords.DELETED).value(true) }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool {
            it.mustNot { query -> query.exists { exists -> exists.field(filter.field.path(parent)) } }
        }
        else -> error("Unsupported filter expression: ${filter::class.java.name}.")
    }

    private val QueryField.isDocumentId: Boolean
        get() = path == "_id"

    private fun QueryField.path(parent: String?): String =
        if (parent == null || path == parent || path.startsWith("$parent.")) path else "$parent.$path"

    private fun documentIdEqual(value: String): Query = documentIdField?.let { field ->
        term { it.field(field).value(value) }
    } ?: ids { it.values(value) }

    private fun documentIdIn(values: List<String>): Query = documentIdField?.let { field ->
        terms { it.field(field).terms { terms -> terms.value(values.map(FieldValue::of)) } }
    } ?: ids { it.values(values) }

    protected open fun aggregateIdEqual(value: String): Query =
        term { it.field(MessageRecords.AGGREGATE_ID).value(value) }

    protected open fun aggregateIdIn(values: List<String>): Query =
        terms {
            it.field(MessageRecords.AGGREGATE_ID)
                .terms { terms -> terms.value(values.map(FieldValue::of)) }
        }

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.nativeValue(): Any? = when {
        isNull -> null
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        isPojo -> (this as tools.jackson.databind.node.POJONode).pojo
        isArray -> asSequence().map { it.nativeValue() }.toList()
        else -> error("Filter value must be a scalar, scalar array, or runtime POJO.")
    }

    private fun tools.jackson.databind.JsonNode.requiredNativeValue(): Any =
        requireNotNull(nativeValue()) { "Filter value must be non-null." }

    private fun tools.jackson.databind.JsonNode.fieldValue(): FieldValue = when {
        isString -> FieldValue.of(asString())
        isBoolean -> FieldValue.of(booleanValue())
        else -> FieldValue.of(requiredNativeValue())
    }
}

private fun String.escapeWildcard(): String =
    replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("?", "\\?")
