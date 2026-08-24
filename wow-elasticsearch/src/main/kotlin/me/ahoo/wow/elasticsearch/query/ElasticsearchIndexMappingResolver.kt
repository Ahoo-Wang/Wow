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

@file:Suppress("DEPRECATION", "NoWildcardImports", "WildcardImport")

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.BooleanProperty
import co.elastic.clients.elasticsearch._types.mapping.CountedKeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.DateNanosProperty
import co.elastic.clients.elasticsearch._types.mapping.DateProperty
import co.elastic.clients.elasticsearch._types.mapping.DocValuesPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.FlattenedProperty
import co.elastic.clients.elasticsearch._types.mapping.IcuCollationProperty
import co.elastic.clients.elasticsearch._types.mapping.IpProperty
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.NumberPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.PropertyBase
import co.elastic.clients.elasticsearch._types.mapping.RangePropertyBase
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.SearchAsYouTypeProperty
import co.elastic.clients.elasticsearch._types.mapping.TextProperty
import co.elastic.clients.elasticsearch._types.mapping.TokenCountProperty
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.Sort
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

enum class ElasticsearchFieldUsage {
    EXACT,
    LITERAL,
    RANGE,
    PRESENCE,
    SEARCH,
    MATCH,
    SORT,
    TERMS,
    NUMERIC,
    DATE,
}

class ElasticsearchFieldResolutionException(message: String) : IllegalArgumentException(message)

data class ElasticsearchMappingRefreshResult(
    val mapping: ElasticsearchIndexMapping,
    val changed: Boolean,
)

class ElasticsearchIndexMappingResolver(
    private val elasticsearchClient: ReactiveElasticsearchClient,
) {
    private val mappings = ConcurrentHashMap<String, ElasticsearchIndexMapping>()
    private val refreshes = ConcurrentHashMap<String, Mono<ElasticsearchMappingRefreshResult>>()

    fun currentOrLoad(indexName: String): Mono<ElasticsearchIndexMapping> =
        Mono.defer {
            mappings[indexName]?.let { Mono.just(it) }
                ?: refresh(indexName).map { it.mapping }
        }

    fun refresh(indexName: String): Mono<ElasticsearchMappingRefreshResult> =
        refreshes.computeIfAbsent(indexName) {
            Mono.defer {
                val request = GetMappingRequest.of { it.index(indexName) }
                elasticsearchClient.indices().getMapping(request)
            }.map { response ->
                require(response.mappings().size == 1) {
                    "Elasticsearch index [$indexName] must resolve to exactly one physical index, " +
                        "but resolved to ${response.mappings().keys}."
                }
                val typeMapping = response.mappings().values.single().mappings()
                val mapping = ElasticsearchIndexMapping.from(indexName, typeMapping)
                ElasticsearchMappingRefreshResult(
                    mapping = mapping,
                    changed = mappings.put(indexName, mapping) != mapping,
                )
            }.doOnSuccess {
                refreshes.remove(indexName)
            }.doOnError {
                refreshes.remove(indexName)
            }.cache()
        }
}

@ConsistentCopyVisibility
data class ElasticsearchIndexMapping private constructor(
    val indexName: String,
    private val fields: Map<String, ElasticsearchMappedField>,
) {
    val fieldCount: Int
        get() = fields.size

    fun resolve(field: String, usage: ElasticsearchFieldUsage): String {
        val mappedField = findMappedField(field)
            ?: if (usage == ElasticsearchFieldUsage.PRESENCE) {
                return field
            } else {
                resolutionFailure(
                    "Elasticsearch field [$field] is not mapped in index [$indexName].",
                )
            }
        if (mappedField.supports(usage)) {
            return field
        }

        preferredSuffixes(usage).forEach { suffix ->
            val candidate = "$field.$suffix"
            if (candidate in mappedField.multiFields && fields[candidate]?.supports(usage) == true) {
                return candidate
            }
        }

        val candidates = mappedField.multiFields.filter { fields[it]?.supports(usage) == true }
        if (candidates.size == 1) {
            return candidates.single()
        }
        if (candidates.size > 1) {
            resolutionFailure(
                "Elasticsearch field [$field] has ambiguous ${usage.name.lowercase()} mappings $candidates " +
                    "in index [$indexName].",
            )
        }
        resolutionFailure(
            "Elasticsearch field [$field] does not support ${usage.name.lowercase()} queries in index [$indexName].",
        )
    }

    private fun findMappedField(field: String): ElasticsearchMappedField? {
        fields[field]?.let { return it }
        var separator = field.lastIndexOf('.')
        while (separator > 0) {
            fields[field.substring(0, separator)]
                ?.takeIf { it.kind == Property.Kind.Flattened }
                ?.let { return it }
            separator = field.lastIndexOf('.', separator - 1)
        }
        return null
    }

    fun requireNested(field: String): String {
        val mappedField = fields[field]
            ?: throw ElasticsearchFieldResolutionException(
                "Elasticsearch nested field [$field] is not mapped in index [$indexName].",
            )
        if (mappedField.kind != Property.Kind.Nested) {
            throw ElasticsearchFieldResolutionException(
                "Elasticsearch field [$field] must be mapped as nested in index [$indexName].",
            )
        }
        return field
    }

    fun resolve(filter: FilterExpression): FilterExpression = resolve(filter, null, false)

    fun resolveAggregationFilter(filter: FilterExpression): FilterExpression = resolve(filter, null, true)

    private fun resolve(filter: FilterExpression, parent: String?, aggregationFilter: Boolean): FilterExpression =
        resolveTyped(filter, parent, aggregationFilter)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun resolveTyped(
        filter: FilterExpression,
        parent: String?,
        aggregationFilter: Boolean,
    ): FilterExpression = when (filter) {
        is IdFilter,
        is IdsFilter,
        is AggregateIdFilter,
        is AggregateIdsFilter,
        is TenantIdFilter,
        is OwnerIdFilter,
        is SpaceIdFilter,
        -> filter
        is AndFilter -> AndFilter(filter.operands.map { resolve(it, parent, aggregationFilter) })
        is OrFilter -> OrFilter(filter.operands.map { resolve(it, parent, aggregationFilter) })
        is NorFilter -> NorFilter(filter.operands.map { resolve(it, parent, aggregationFilter) })
        is EqualFilter -> filter.copy(
            field = filter.field.resolve(
                parent,
                if (filter.value.isNull) ElasticsearchFieldUsage.PRESENCE else ElasticsearchFieldUsage.EXACT,
                aggregationFilter,
            ),
        )
        is NotEqualFilter -> filter.copy(
            field = filter.field.resolve(
                parent,
                if (filter.value.isNull) ElasticsearchFieldUsage.PRESENCE else ElasticsearchFieldUsage.EXACT,
                aggregationFilter,
            ),
        )
        is InFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.EXACT, aggregationFilter)
        )
        is NotInFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.EXACT, aggregationFilter)
        )
        is ContainsAllFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.EXACT, aggregationFilter)
        )
        is IsEmptyFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.PRESENCE, aggregationFilter)
        )
        is IsNullFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.PRESENCE, aggregationFilter)
        )
        is IsNotNullFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.PRESENCE, aggregationFilter)
        )
        is ExistsFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.PRESENCE, aggregationFilter)
        )
        is NotExistsFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.PRESENCE, aggregationFilter)
        )
        is ContainsFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.LITERAL, aggregationFilter)
        )
        is StartsWithFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.LITERAL, aggregationFilter)
        )
        is EndsWithFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.LITERAL, aggregationFilter)
        )
        is GreaterThanFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is GreaterThanOrEqualFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is LessThanFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is LessThanOrEqualFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is BetweenFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is TodayFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is BeforeTodayFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is TomorrowFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is ThisWeekFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is NextWeekFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is LastWeekFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is ThisMonthFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is LastMonthFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is RecentDaysFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is EarlierDaysFilter -> filter.copy(
            field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE, aggregationFilter)
        )
        is SearchFilter -> filter.copy(
            fields = filter.fields.mapTo(linkedSetOf()) {
                it.resolve(parent, ElasticsearchFieldUsage.SEARCH, aggregationFilter)
            },
        )
        is ElementMatchFilter -> {
            val nestedPath = filter.field.path(parent)
            ElementMatchFilter(
                LogicalField(requireNested(nestedPath)),
                resolve(filter.predicate, nestedPath, aggregationFilter),
            )
        }
        else -> filter
    }

    private fun LogicalField.path(parent: String?): String =
        if (parent == null || value == parent || value.startsWith("$parent.")) value else "$parent.$value"

    private fun LogicalField.resolve(
        parent: String?,
        usage: ElasticsearchFieldUsage,
        aggregationFilter: Boolean = false,
    ): LogicalField {
        if (parent == null && value == "_id" && usage == ElasticsearchFieldUsage.EXACT) return this
        val resolved = this@ElasticsearchIndexMapping.resolve(path(parent), usage)
        val mappedField = fields[resolved]
        if (aggregationFilter && mappedField?.portableAggregation == false) {
            resolutionFailure(
                "Elasticsearch field [$resolved] does not support portable aggregation filters " +
                    "in index [$indexName].",
            )
        }
        if (aggregationFilter && mappedField?.kind == Property.Kind.DateNanos) {
            resolutionFailure(
                "Elasticsearch date_nanos field [$resolved] does not support portable aggregation filters " +
                    "in index [$indexName].",
            )
        }
        return LogicalField(resolved)
    }

    fun resolve(sort: List<Sort>): List<Sort> =
        sort.map {
            if (it.field in METADATA_SORT_FIELDS) {
                it
            } else {
                it.copy(
                    field = resolve(it.field, ElasticsearchFieldUsage.SORT),
                )
            }
        }

    private fun resolutionFailure(message: String): Nothing =
        throw ElasticsearchFieldResolutionException(message)

    companion object {
        fun from(indexName: String, typeMapping: TypeMapping): ElasticsearchIndexMapping {
            val fields = linkedMapOf<String, ElasticsearchMappedField>()
            val aliases = linkedMapOf<String, String>()

            fun visit(path: String, property: Property) {
                if (property.isAlias) {
                    property.alias().path()?.let { aliases[path] = it }
                    return
                }
                val propertyBase = property._get() as? PropertyBase
                val multiFields = propertyBase?.fields().orEmpty().keys.mapTo(linkedSetOf()) { "$path.$it" }
                fields[path] = ElasticsearchMappedField(
                    kind = property._kind(),
                    indexed = property.isIndexed(),
                    sortable = property.isSortable(),
                    portableAggregation = property.isPortableAggregation(),
                    multiFields = multiFields,
                )
                propertyBase?.fields().orEmpty().forEach { (name, field) -> visit("$path.$name", field) }
                propertyBase?.properties().orEmpty().forEach { (name, field) -> visit("$path.$name", field) }
            }

            typeMapping.properties().forEach { (name, property) -> visit(name, property) }
            typeMapping.runtime().forEach { (name, runtimeField) ->
                if (runtimeField.type() == RuntimeFieldType.Composite) {
                    runtimeField.fields().forEach { (fieldName, field) ->
                        field.type().toMappedField()?.let { fields["$name.$fieldName"] = it }
                    }
                } else {
                    runtimeField.type().toMappedField()?.let { fields[name] = it }
                }
            }
            aliases.forEach { (name, target) ->
                fields[target]?.let {
                    fields[name] = it.copy(portableAggregation = false, multiFields = emptySet())
                }
            }
            return ElasticsearchIndexMapping(indexName, fields)
        }

        private fun preferredSuffixes(usage: ElasticsearchFieldUsage): List<String> =
            when (usage) {
                ElasticsearchFieldUsage.SEARCH -> listOf("text")
                else -> listOf("keyword", "exact")
            }

        private val METADATA_SORT_FIELDS = setOf("_score", "_doc", "_shard_doc")
    }
}

private data class ElasticsearchMappedField(
    val kind: Property.Kind,
    val indexed: Boolean,
    val sortable: Boolean,
    val portableAggregation: Boolean,
    val multiFields: Set<String>,
) {
    fun supports(usage: ElasticsearchFieldUsage): Boolean =
        if (usage in AGGREGATION_USAGES) supportsAggregation(usage) else supportsQuery(usage)

    @Suppress("CyclomaticComplexMethod")
    private fun supportsQuery(usage: ElasticsearchFieldUsage): Boolean =
        when (usage) {
            ElasticsearchFieldUsage.EXACT -> isQueryable() && kind in EXACT_KINDS
            ElasticsearchFieldUsage.LITERAL -> indexed && kind in LITERAL_KINDS
            ElasticsearchFieldUsage.RANGE -> isQueryable() && kind in RANGE_KINDS
            ElasticsearchFieldUsage.PRESENCE -> isQueryable()
            ElasticsearchFieldUsage.SEARCH,
            ElasticsearchFieldUsage.MATCH,
            -> indexed && kind in SEARCH_KINDS_BY_USAGE.getValue(usage)
            ElasticsearchFieldUsage.SORT ->
                sortable && (kind in EXACT_KINDS || (indexed && kind == Property.Kind.Text))

            else -> error("Unsupported query field usage: $usage")
        }

    private fun supportsAggregation(usage: ElasticsearchFieldUsage): Boolean =
        portableAggregation &&
            when (usage) {
                ElasticsearchFieldUsage.TERMS -> sortable && kind in TERMS_AGGREGATION_KINDS
                ElasticsearchFieldUsage.NUMERIC -> sortable && kind in AGGREGATION_NUMERIC_KINDS
                ElasticsearchFieldUsage.DATE -> sortable && kind in DATE_KINDS
                else -> error("Unsupported aggregation field usage: $usage")
            }

    private fun isQueryable(): Boolean = indexed || (sortable && kind in DOC_VALUE_QUERY_KINDS)

    companion object {
        private val AGGREGATION_USAGES = setOf(
            ElasticsearchFieldUsage.TERMS,
            ElasticsearchFieldUsage.NUMERIC,
            ElasticsearchFieldUsage.DATE,
        )
        private val NUMERIC_KINDS = setOf(
            Property.Kind.Byte,
            Property.Kind.Short,
            Property.Kind.Integer,
            Property.Kind.Long,
            Property.Kind.UnsignedLong,
            Property.Kind.HalfFloat,
            Property.Kind.Float,
            Property.Kind.Double,
            Property.Kind.ScaledFloat,
            Property.Kind.TokenCount,
        )
        private val KEYWORD_KINDS = setOf(
            Property.Kind.Keyword,
            Property.Kind.ConstantKeyword,
            Property.Kind.CountedKeyword,
            Property.Kind.IcuCollationKeyword,
        )
        private val TERM_KINDS = KEYWORD_KINDS + Property.Kind.Wildcard
        private val DATE_KINDS = setOf(Property.Kind.Date, Property.Kind.DateNanos)
        private val BINARY_TERM_KINDS = setOf(
            Property.Kind.Keyword,
            Property.Kind.ConstantKeyword,
            Property.Kind.CountedKeyword,
            Property.Kind.Wildcard,
        )
        private val AGGREGATION_NUMERIC_KINDS = NUMERIC_KINDS - Property.Kind.TokenCount
        private val TERMS_AGGREGATION_KINDS = AGGREGATION_NUMERIC_KINDS + BINARY_TERM_KINDS + Property.Kind.Boolean
        private val RANGE_FIELD_KINDS = setOf(
            Property.Kind.IntegerRange,
            Property.Kind.FloatRange,
            Property.Kind.LongRange,
            Property.Kind.DoubleRange,
            Property.Kind.DateRange,
            Property.Kind.IpRange,
        )
        private val DOC_VALUE_QUERY_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + setOf(
            Property.Kind.Boolean,
            Property.Kind.Date,
            Property.Kind.DateNanos,
            Property.Kind.Ip,
        )
        private val EXACT_KINDS = NUMERIC_KINDS + TERM_KINDS + setOf(
            Property.Kind.Boolean,
            Property.Kind.Date,
            Property.Kind.DateNanos,
            Property.Kind.Flattened,
            Property.Kind.Ip,
            Property.Kind.Version,
        )
        private val LITERAL_KINDS = TERM_KINDS
        private val RANGE_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + RANGE_FIELD_KINDS + setOf(
            Property.Kind.Date,
            Property.Kind.DateNanos,
            Property.Kind.Ip,
        )
        private val SEARCH_KINDS = setOf(
            Property.Kind.Text,
            Property.Kind.MatchOnlyText,
            Property.Kind.SearchAsYouType,
            Property.Kind.SemanticText,
        )
        private val MATCH_KINDS = SEARCH_KINDS + EXACT_KINDS
        private val SEARCH_KINDS_BY_USAGE = mapOf(
            ElasticsearchFieldUsage.SEARCH to SEARCH_KINDS,
            ElasticsearchFieldUsage.MATCH to MATCH_KINDS,
        )
    }
}

private fun RuntimeFieldType.toMappedField(): ElasticsearchMappedField? {
    val kind = when (this) {
        RuntimeFieldType.Boolean -> Property.Kind.Boolean
        RuntimeFieldType.Date -> Property.Kind.Date
        RuntimeFieldType.Double -> Property.Kind.Double
        RuntimeFieldType.Ip -> Property.Kind.Ip
        RuntimeFieldType.Keyword -> Property.Kind.Keyword
        RuntimeFieldType.Long -> Property.Kind.Long
        else -> return null
    }
    return ElasticsearchMappedField(
        kind = kind,
        indexed = true,
        sortable = true,
        portableAggregation = false,
        multiFields = emptySet(),
    )
}

private fun Property.isPortableAggregation(): Boolean {
    val property = _get()
    return when {
        _kind() == Property.Kind.ScaledFloat -> false
        property is KeywordProperty -> property.normalizer() == null && property.ignoreAbove() == null &&
            property.nullValue() == null
        hasNumericNullValue() -> false
        _kind() == Property.Kind.ConstantKeyword -> constantKeyword().value() == null
        else -> true
    }
}

private fun Property.hasNumericNullValue(): Boolean = when (_kind()) {
    Property.Kind.Byte -> byte_().nullValue() != null
    Property.Kind.Short -> short_().nullValue() != null
    Property.Kind.Integer -> integer().nullValue() != null
    Property.Kind.Long -> long_().nullValue() != null
    Property.Kind.UnsignedLong -> unsignedLong().nullValue() != null
    Property.Kind.HalfFloat -> halfFloat().nullValue() != null
    Property.Kind.Float -> float_().nullValue() != null
    Property.Kind.Double -> double_().nullValue() != null
    Property.Kind.ScaledFloat -> scaledFloat().nullValue() != null
    else -> false
}

private fun Property.isSortable(): Boolean =
    when (_kind()) {
        Property.Kind.ConstantKeyword,
        Property.Kind.CountedKeyword,
        -> true

        Property.Kind.Flattened -> flattened().docValues() != false
        Property.Kind.Text -> text().fielddata() == true
        else -> (_get() as? DocValuesPropertyBase)?.let { it.docValues() != false } == true
    }

private fun Property.isIndexed(): Boolean =
    when (val property = _get()) {
        is BooleanProperty -> property.index() != false
        is CountedKeywordProperty -> property.index() != false
        is DateNanosProperty -> property.index() != false
        is DateProperty -> property.index() != false
        is FlattenedProperty -> property.index() != false
        is IcuCollationProperty -> property.index() != false
        is IpProperty -> property.index() != false
        is KeywordProperty -> property.index() != false
        is NumberPropertyBase -> property.index() != false
        is SearchAsYouTypeProperty -> property.index() != false
        is TextProperty -> property.index() != false
        is TokenCountProperty -> property.index() != false
        else -> (property as? RangePropertyBase)?.index() != false
    }
