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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import java.text.ParsePosition
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQueries
import java.util.IdentityHashMap

abstract class AbstractElasticsearchFilterCompiler(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
    private val documentIdField: String? = null,
) {
    private val defaultZoneId = ZoneId.systemDefault()
    private val filterNormalizer =
        FilterNormalizer(defaultZoneId = defaultZoneId, defaultDeletionState = defaultDeletionState)

    fun compile(filter: FilterExpression, schema: QueryModelSchema): Query =
        compile(filter, schema, FilterScope())

    internal fun compileScoped(
        filter: FilterExpression,
        schema: QueryModelSchema,
        logicalParent: QueryField,
        resolvedParent: QueryField,
        physicalParent: QueryField,
    ): Query = compile(filter, schema, FilterScope(logicalParent, resolvedParent, physicalParent))

    private fun compile(filter: FilterExpression, schema: QueryModelSchema, scope: FilterScope): Query {
        val booleanConstants = IdentityHashMap<FilterExpression, BooleanConstant>()
        classifyBooleanConstants(filter, booleanConstants)
        validateRelativeTimeZones(filter, schema, scope, booleanConstants)
        return compileNormalized(filterNormalizer.normalize(filter), schema, scope)
    }

    internal fun compilePhysical(filter: FilterExpression, parent: String? = null): Query =
        compileNormalized(
            filterNormalizer.normalize(filter),
            schema = null,
            scope = FilterScope(physicalParent = parent?.let(::QueryField)),
        )

    private data class FilterScope(
        val logicalParent: QueryField? = null,
        val resolvedParent: QueryField? = null,
        val physicalParent: QueryField? = null,
    )

    private enum class BooleanConstant {
        MATCH_ALL,
        MATCH_NONE,
    }

    @Suppress("CyclomaticComplexMethod")
    private fun classifyBooleanConstants(
        filter: FilterExpression,
        constants: MutableMap<FilterExpression, BooleanConstant>,
    ): BooleanConstant? {
        val constant = when (filter) {
            MatchAllFilter -> BooleanConstant.MATCH_ALL
            MatchNoneFilter -> BooleanConstant.MATCH_NONE
            is AndFilter -> filter.operands.map { classifyBooleanConstants(it, constants) }.let { operands ->
                when {
                    BooleanConstant.MATCH_NONE in operands -> BooleanConstant.MATCH_NONE
                    operands.all { it == BooleanConstant.MATCH_ALL } -> BooleanConstant.MATCH_ALL
                    else -> null
                }
            }
            is OrFilter -> filter.operands.map { classifyBooleanConstants(it, constants) }.let { operands ->
                when {
                    BooleanConstant.MATCH_ALL in operands -> BooleanConstant.MATCH_ALL
                    operands.all { it == BooleanConstant.MATCH_NONE } -> BooleanConstant.MATCH_NONE
                    else -> null
                }
            }
            is NorFilter -> filter.operands.map { classifyBooleanConstants(it, constants) }.let { operands ->
                when {
                    BooleanConstant.MATCH_ALL in operands -> BooleanConstant.MATCH_NONE
                    operands.all { it == BooleanConstant.MATCH_NONE } -> BooleanConstant.MATCH_ALL
                    else -> null
                }
            }
            is ElementMatchFilter -> {
                classifyBooleanConstants(filter.predicate, constants)
                null
            }
            else -> null
        }
        constant?.let { constants[filter] = it }
        return constant
    }

    private fun validateRelativeTimeZones(
        filter: FilterExpression,
        schema: QueryModelSchema,
        scope: FilterScope,
        constants: Map<FilterExpression, BooleanConstant>,
    ) {
        when (filter) {
            is AndFilter -> {
                if (filter.operands.any { constants[it] == BooleanConstant.MATCH_NONE }) return
                filter.operands.filterNot { constants[it] == BooleanConstant.MATCH_ALL }
                    .forEach { validateRelativeTimeZones(it, schema, scope, constants) }
            }
            is OrFilter -> {
                if (filter.operands.any { constants[it] == BooleanConstant.MATCH_ALL }) return
                filter.operands.filterNot { constants[it] == BooleanConstant.MATCH_NONE }
                    .forEach { validateRelativeTimeZones(it, schema, scope, constants) }
            }
            is NorFilter -> {
                if (filter.operands.any { constants[it] == BooleanConstant.MATCH_ALL }) return
                filter.operands.filterNot { constants[it] == BooleanConstant.MATCH_NONE }
                    .forEach { validateRelativeTimeZones(it, schema, scope, constants) }
            }
            is ElementMatchFilter -> validateRelativeTimeZones(
                filter.predicate,
                schema,
                filter.nestedScope(schema, scope),
                constants,
            )
            is RelativeTimeFilter -> filter.validateRelativeTimeZone(schema, scope)
            else -> Unit
        }
    }

    private fun RelativeTimeFilter.validateRelativeTimeZone(schema: QueryModelSchema, scope: FilterScope) {
        val fieldSchema = schema.resolveFieldSchema(field.absoluteTo(scope.logicalParent), QueryCapability.RANGE)
            ?: schema.resolveFieldSchema(field.absoluteTo(scope.resolvedParent), QueryCapability.RANGE)
        if (fieldSchema?.semanticType !is Temporal.Formatted ||
            fieldSchema.binding(QueryCapability.RANGE)?.storageType?.value !in NATIVE_DATE_TYPES
        ) {
            return
        }
        val formatter = resolvedDateFormatter() ?: return
        val zone = formatter.zone ?: zoneId?.let(ZoneId::of) ?: defaultZoneId
        if (zone.normalized() == ZoneOffset.UTC) return
        // Clear the override and vary only the offset: parser defaults must not pretend to encode a zone.
        val wireFormatter = formatter.withZone(null)
        val parsed = wireFormatter.parseUnresolved(formatter.format(FORMAT_PROBE), ParsePosition(0)) ?: return
        if (parsed.isSupported(ChronoField.INSTANT_SECONDS) || TIME_FIELDS.none(parsed::isSupported)) return
        val encodedZone = parsed.query(TemporalQueries.zone()) != null &&
            wireFormatter.format(FORMAT_PROBE) != wireFormatter.format(FORMAT_PROBE.withZoneSameLocal(ZoneOffset.ofHours(-5)))
        if (!encodedZone) {
            throw QuerySchemaValidationException(
                "Native date field [$field] requires an encoded zone for datetime format " +
                    "[${datePattern ?: formatter}] in zone [$zone].",
            )
        }
    }

    private fun ElementMatchFilter.nestedScope(schema: QueryModelSchema?, scope: FilterScope) = FilterScope(
        logicalParent = field.absoluteTo(scope.logicalParent),
        resolvedParent = field.absoluteTo(scope.resolvedParent),
        physicalParent = QueryField(field.path(schema, QueryCapability.ELEMENT_SCOPE, scope)),
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compileNormalized(
        filter: FilterExpression,
        schema: QueryModelSchema?,
        scope: FilterScope,
    ): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is IdFilter -> documentIdEqual(filter.value)
        is IdsFilter -> documentIdIn(filter.values)
        is AggregateIdFilter -> aggregateIdEqual(filter.value)
        is AggregateIdsFilter -> aggregateIdIn(filter.values)
        is TenantIdFilter -> term {
            it.field(QueryField(MessageRecords.TENANT_ID).metadataPath(schema))
                .value(filter.value)
        }
        is OwnerIdFilter -> term {
            it.field(QueryField(MessageRecords.OWNER_ID).metadataPath(schema))
                .value(filter.value)
        }
        is SpaceIdFilter -> term {
            it.field(QueryField(MessageRecords.SPACE_ID).metadataPath(schema))
                .value(filter.value)
        }
        is AndFilter -> bool {
            it.filter(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
        }
        is OrFilter -> bool {
            it.should(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
                .minimumShouldMatch("1")
        }
        is NorFilter -> bool {
            it.mustNot(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
        }
        is EqualFilter -> {
            val field = filter.field.path(schema, QueryCapability.EXACT_MATCH, scope)
            if (scope.physicalParent == null && field == DOCUMENT_ID_FIELD) {
                documentIdEqual(filter.value.requiredNativeValue().toString())
            } else {
                term { it.field(field).value(filter.value.fieldValue()) }
            }
        }
        is NotEqualFilter -> bool {
            it.mustNot(compileNormalized(EqualFilter(filter.field, filter.value), schema, scope))
        }
        is GreaterThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .lt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .lte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is ContainsFilter -> wildcard {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope))
                .value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope)).value(filter.value)
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope))
                .value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> {
            val field = filter.field.path(schema, QueryCapability.EXACT_MATCH, scope)
            if (scope.physicalParent == null && field == DOCUMENT_ID_FIELD) {
                documentIdIn(filter.values.map { value -> value.requiredNativeValue().toString() })
            } else {
                terms {
                    it.field(field).terms { terms ->
                        terms.value(filter.values.map { value -> value.fieldValue() })
                    }
                }
            }
        }
        is NotInFilter -> bool {
            it.mustNot(compileNormalized(InFilter(filter.field, filter.values), schema, scope))
        }
        is BetweenFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gte(JsonData.of(filter.lowerBound.requiredNativeValue()))
                    .lte(JsonData.of(filter.upperBound.requiredNativeValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(filter.field.path(schema, QueryCapability.EXACT_MATCH, scope))
                    .terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        is IsNotNullFilter -> exists { it.field(filter.field.path(schema, QueryCapability.PRESENCE, scope)) }
        is ExistsFilter -> exists { it.field(filter.field.path(schema, QueryCapability.PRESENCE, scope)) }
        is NotExistsFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        is ElementMatchFilter -> nested {
            val nestedScope = filter.nestedScope(schema, scope)
            it.path(nestedScope.physicalParent!!.path).query(compileNormalized(filter.predicate, schema, nestedScope))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            if (filter.fields.isEmpty()) {
                it.lenient(true)
            } else {
                val capability = when (filter.mode) {
                    SearchMode.TERMS -> QueryCapability.FULL_TEXT_TERMS
                    SearchMode.PHRASE -> QueryCapability.FULL_TEXT_PHRASE
                }
                it.fields(filter.fields.map { field -> field.path(schema, capability, scope) })
            }
            if (filter.mode == SearchMode.PHRASE) it.type(TextQueryType.Phrase)
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term {
                it.field(QueryField(StateAggregateRecords.DELETED).metadataPath(schema)).value(false)
            }
            DeletionState.DELETED -> term {
                it.field(QueryField(StateAggregateRecords.DELETED).metadataPath(schema)).value(true)
            }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        else -> error("Unsupported filter expression: ${filter::class.java.name}.")
    }

    private fun QueryField.path(
        schema: QueryModelSchema?,
        capability: QueryCapability,
        scope: FilterScope,
    ): String {
        if (schema == null) return path(scope.physicalParent?.path)
        val physicalField = schema.resolvePhysicalField(
            this,
            capability,
            logicalParent = scope.logicalParent,
            resolvedParent = scope.resolvedParent,
            physicalParent = scope.physicalParent,
        )
        return scope.physicalParent?.append(physicalField)?.path ?: physicalField.path
    }

    private fun QueryField.metadataPath(schema: QueryModelSchema?): String =
        path(schema, QueryCapability.EXACT_MATCH, FilterScope())

    private fun QueryField.path(parent: String?): String =
        if (parent == null || path == parent || path.startsWith("$parent.")) path else "$parent.$path"

    private companion object {
        const val DOCUMENT_ID_FIELD = "_id"
        val NATIVE_DATE_TYPES = setOf("date", "date_nanos")
        val TIME_FIELDS = ChronoField.entries.filter { it.isTimeBased }
        val FORMAT_PROBE = ZonedDateTime.of(2000, 6, 15, 12, 34, 56, 0, ZoneOffset.ofHours(8))
    }

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
