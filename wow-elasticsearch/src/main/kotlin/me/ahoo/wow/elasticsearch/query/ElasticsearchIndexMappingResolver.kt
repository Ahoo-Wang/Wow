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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.BooleanProperty
import co.elastic.clients.elasticsearch._types.mapping.CountedKeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.DateNanosProperty
import co.elastic.clients.elasticsearch._types.mapping.DateProperty
import co.elastic.clients.elasticsearch._types.mapping.DocValuesPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.IcuCollationProperty
import co.elastic.clients.elasticsearch._types.mapping.IpProperty
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.NumberPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.PropertyBase
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.SearchAsYouTypeProperty
import co.elastic.clients.elasticsearch._types.mapping.TextProperty
import co.elastic.clients.elasticsearch._types.mapping.TokenCountProperty
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Sort
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

enum class ElasticsearchFieldUsage {
    EXACT,
    LITERAL,
    RANGE,
    SEARCH,
    SORT,
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
        val mappedField = fields[field]
            ?: resolutionFailure(
                "Elasticsearch field [$field] is not mapped in index [$indexName].",
            )
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

    fun resolve(condition: Condition): Condition =
        when (condition.operator) {
            Operator.AND,
            Operator.OR,
            Operator.NOR,
            -> condition.copy(children = condition.children.map(::resolve))

            Operator.EQ,
            Operator.NE,
            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            Operator.TRUE,
            Operator.FALSE,
            -> condition.withResolvedField(ElasticsearchFieldUsage.EXACT)

            Operator.CONTAINS,
            Operator.STARTS_WITH,
            Operator.ENDS_WITH,
            -> condition.withResolvedField(ElasticsearchFieldUsage.LITERAL)

            Operator.GT,
            Operator.LT,
            Operator.GTE,
            Operator.LTE,
            Operator.BETWEEN,
            Operator.TODAY,
            Operator.BEFORE_TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS,
            -> condition.withResolvedField(ElasticsearchFieldUsage.RANGE)

            Operator.MATCH -> condition.withResolvedField(ElasticsearchFieldUsage.SEARCH)
            Operator.ELEM_MATCH -> condition.copy(
                field = requireNested(condition.field),
                children = condition.children.map(::resolve),
            )

            else -> condition
        }

    fun resolve(sort: List<Sort>): List<Sort> =
        sort.map { it.copy(field = resolve(it.field, ElasticsearchFieldUsage.SORT)) }

    private fun Condition.withResolvedField(usage: ElasticsearchFieldUsage): Condition =
        copy(field = resolve(field, usage))

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
                    sortable = property.hasDocValues(),
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
                fields[target]?.let { fields[name] = it.copy(multiFields = emptySet()) }
            }
            return ElasticsearchIndexMapping(indexName, fields)
        }

        private fun preferredSuffixes(usage: ElasticsearchFieldUsage): List<String> =
            when (usage) {
                ElasticsearchFieldUsage.SEARCH -> listOf("text")
                else -> listOf("keyword", "exact")
            }
    }
}

private data class ElasticsearchMappedField(
    val kind: Property.Kind,
    val indexed: Boolean,
    val sortable: Boolean,
    val multiFields: Set<String>,
) {
    fun supports(usage: ElasticsearchFieldUsage): Boolean =
        when (usage) {
            ElasticsearchFieldUsage.EXACT -> (indexed || sortable) && kind in EXACT_KINDS
            ElasticsearchFieldUsage.LITERAL -> indexed && kind in LITERAL_KINDS
            ElasticsearchFieldUsage.RANGE -> (indexed || sortable) && kind in RANGE_KINDS
            ElasticsearchFieldUsage.SEARCH -> indexed && kind in SEARCH_KINDS
            ElasticsearchFieldUsage.SORT -> sortable && kind in EXACT_KINDS
        }

    companion object {
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
        private val EXACT_KINDS = NUMERIC_KINDS + TERM_KINDS + setOf(
            Property.Kind.Boolean,
            Property.Kind.Date,
            Property.Kind.DateNanos,
            Property.Kind.Ip,
            Property.Kind.Version,
        )
        private val LITERAL_KINDS = TERM_KINDS
        private val RANGE_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + setOf(
            Property.Kind.Date,
            Property.Kind.DateNanos,
        )
        private val SEARCH_KINDS = setOf(
            Property.Kind.Text,
            Property.Kind.MatchOnlyText,
            Property.Kind.SearchAsYouType,
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
        multiFields = emptySet(),
    )
}

private fun Property.hasDocValues(): Boolean =
    when (_kind()) {
        Property.Kind.ConstantKeyword,
        Property.Kind.CountedKeyword,
        -> true

        else -> (_get() as? DocValuesPropertyBase)?.let { it.docValues() != false } == true
    }

private fun Property.isIndexed(): Boolean =
    when (val property = _get()) {
        is BooleanProperty -> property.index() != false
        is CountedKeywordProperty -> property.index() != false
        is DateNanosProperty -> property.index() != false
        is DateProperty -> property.index() != false
        is IcuCollationProperty -> property.index() != false
        is IpProperty -> property.index() != false
        is KeywordProperty -> property.index() != false
        is NumberPropertyBase -> property.index() != false
        is SearchAsYouTypeProperty -> property.index() != false
        is TextProperty -> property.index() != false
        is TokenCountProperty -> property.index() != false
        else -> true
    }
