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
import co.elastic.clients.elasticsearch._types.mapping.FlattenedProperty
import co.elastic.clients.elasticsearch._types.mapping.IcuCollationProperty
import co.elastic.clients.elasticsearch._types.mapping.IpProperty
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.NestedProperty
import co.elastic.clients.elasticsearch._types.mapping.NumberPropertyBase
import co.elastic.clients.elasticsearch._types.mapping.ObjectProperty
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.PropertyBase
import co.elastic.clients.elasticsearch._types.mapping.RangePropertyBase
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.SearchAsYouTypeProperty
import co.elastic.clients.elasticsearch._types.mapping.TextProperty
import co.elastic.clients.elasticsearch._types.mapping.TokenCountProperty
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.json.JsonData
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

class ElasticsearchIndexMappingResolver(
    private val elasticsearchClient: ReactiveElasticsearchClient,
) {
    private val mappings = ConcurrentHashMap<String, ElasticsearchIndexMapping>()
    private val refreshes = ConcurrentHashMap<String, Mono<ElasticsearchIndexMapping>>()

    fun currentOrLoad(indexName: String): Mono<ElasticsearchIndexMapping> = Mono.defer {
        mappings[indexName]?.let { Mono.just(it) } ?: refresh(indexName)
    }

    fun refresh(indexName: String): Mono<ElasticsearchIndexMapping> = refreshes.computeIfAbsent(indexName) {
        lateinit var candidate: Mono<ElasticsearchIndexMapping>
        candidate = Mono.defer {
            elasticsearchClient.indices().getMapping(GetMappingRequest.of { it.index(indexName) })
        }.map { response ->
            require(response.mappings().size == 1) {
                "Elasticsearch index [$indexName] must resolve to exactly one physical index, " +
                    "but resolved to ${response.mappings().keys}."
            }
            ElasticsearchIndexMapping.from(indexName, response.mappings().values.single().mappings())
        }.doOnSuccess { mapping ->
            mapping?.let { mappings[indexName] = it }
            refreshes.remove(indexName, candidate)
        }.doOnError {
            refreshes.remove(indexName, candidate)
        }
            .cache()
        candidate
    }
}

@ConsistentCopyVisibility
data class ElasticsearchIndexMapping private constructor(
    val indexName: String,
    internal val fields: Map<String, ElasticsearchMappedField>,
    internal val sourceEnabled: Boolean,
    internal val sourceIncludes: List<String>,
    internal val sourceExcludes: List<String>,
) {
    val fieldCount: Int
        get() = fields.size

    internal fun find(field: String): ElasticsearchMappedField? {
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

    private val sourceIncludePatterns = sourceIncludes.map { it.sourcePattern() }
    private val sourceExcludePatterns = sourceExcludes.associateWith { it.sourcePattern() }

    internal val fullProjectionAvailable: Boolean
        get() = sourceEnabled && sourceIncludes.isEmpty() && sourceExcludes.isEmpty()

    internal fun sourceAvailable(path: String): Boolean {
        if (!sourceEnabled) return false
        val parts = path.split('.')
        val ancestors = parts.indices.map { parts.take(it + 1).joinToString(".") }
        if (sourceIncludes.isNotEmpty() && sourceIncludePatterns.none { pattern -> ancestors.any(pattern::matches) }) {
            return false
        }
        return sourceExcludePatterns.none { (pattern, matcher) ->
            ancestors.any(matcher::matches) || pattern.startsWith("$path.") ||
                // A wildcard with a shared prefix may remove an undeclared descendant.
                pattern.contains('*') && pattern.substringBefore('*').let { prefix ->
                    path.startsWith(prefix) || prefix.startsWith("$path.")
                }
        }
    }

    companion object {
        @Suppress(
            "CyclomaticComplexMethod"
        ) // One mapping walk carries ancestor facts into properties, multifields and aliases.
        fun from(indexName: String, typeMapping: TypeMapping): ElasticsearchIndexMapping {
            val fields = linkedMapOf<String, ElasticsearchMappedField>()
            val aliases = linkedMapOf<String, String>()

            fun visit(path: String, property: Property, projectionPath: String = path, parentEnabled: Boolean = typeMapping.enabled() != false) {
                if (property.isAlias) {
                    property.alias().path()?.let { aliases[path] = it }
                    return
                }
                val enabled = parentEnabled && when (property._kind()) {
                    Property.Kind.Object -> property.`object`().enabled() != false
                    Property.Kind.Nested -> property.nested().enabled() != false
                    else -> true
                }
                val propertyBase = property._get() as? PropertyBase
                val multiFields = propertyBase?.fields().orEmpty().keys.mapTo(linkedSetOf()) { "$path.$it" }
                fields[path] = ElasticsearchMappedField(
                    physicalPath = path,
                    kind = property._kind(),
                    indexed = enabled && property.isIndexed(),
                    sortable = enabled && property.isSortable(),
                    aggregatable = enabled && property.isAggregatable(),
                    enabled = enabled,
                    multiFields = multiFields,
                    projectionPath = projectionPath,
                    ignoreAbove = propertyBase?.ignoreAbove(),
                    nullValue = property.nullValue(),
                    normalizer = if (property.isKeyword) property.keyword().normalizer() else null,
                )
                propertyBase?.fields().orEmpty().forEach { (name, field) ->
                    visit("$path.$name", field, projectionPath, enabled)
                }
                propertyBase?.properties().orEmpty().forEach { (name, field) ->
                    visit("$path.$name", field, "$projectionPath.$name", enabled)
                }
            }

            typeMapping.properties().forEach { (name, property) -> visit(name, property) }
            typeMapping.runtime().forEach { (name, runtimeField) ->
                if (runtimeField.type() == RuntimeFieldType.Composite) {
                    runtimeField.fields().forEach { (fieldName, field) ->
                        val path = "$name.$fieldName"
                        field.type().toMappedField(path)?.let { fields[path] = it }
                    }
                } else {
                    runtimeField.type().toMappedField(name)?.let { fields[name] = it }
                }
            }
            aliases.forEach { (name, target) ->
                fields[target]?.let {
                    fields[name] = it.copy(multiFields = emptySet())
                }
            }
            return ElasticsearchIndexMapping(
                indexName,
                fields.toMap(),
                sourceEnabled = typeMapping.source()?.enabled() != false,
                sourceIncludes = java.util.List.copyOf(typeMapping.source()?.includes().orEmpty()),
                sourceExcludes = java.util.List.copyOf(typeMapping.source()?.excludes().orEmpty()),
            )
        }
    }
}

internal data class ElasticsearchMappedField(
    val physicalPath: String,
    val kind: Property.Kind,
    val indexed: Boolean,
    val sortable: Boolean,
    val aggregatable: Boolean,
    val multiFields: Set<String>,
    val projectionPath: String?,
    val ignoreAbove: Int? = null,
    val nullValue: JsonData? = null,
    val normalizer: String? = null,
    val enabled: Boolean = true,
)

private fun RuntimeFieldType.toMappedField(physicalPath: String): ElasticsearchMappedField? {
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
        physicalPath = physicalPath,
        kind = kind,
        indexed = true,
        sortable = true,
        aggregatable = true,
        multiFields = emptySet(),
        projectionPath = null,
    )
}

@Suppress("CyclomaticComplexMethod") // The native client exposes null_value on individual mapping variants.
private fun Property.nullValue(): JsonData? {
    val sentinel: Any? = when (_kind()) {
        Property.Kind.Keyword -> keyword().nullValue()
        Property.Kind.Wildcard -> wildcard().nullValue()
        Property.Kind.Flattened -> flattened().nullValue()
        Property.Kind.IcuCollationKeyword -> icuCollationKeyword().nullValue()
        Property.Kind.Boolean -> boolean_().nullValue()
        Property.Kind.Byte -> byte_().nullValue()
        Property.Kind.Short -> short_().nullValue()
        Property.Kind.Integer -> integer().nullValue()
        Property.Kind.Long -> long_().nullValue()
        Property.Kind.UnsignedLong -> unsignedLong().nullValue()
        Property.Kind.HalfFloat -> halfFloat().nullValue()
        Property.Kind.Float -> float_().nullValue()
        Property.Kind.Double -> double_().nullValue()
        Property.Kind.ScaledFloat -> scaledFloat().nullValue()
        Property.Kind.TokenCount -> tokenCount().nullValue()
        Property.Kind.Date -> date().nullValue()
        Property.Kind.DateNanos -> dateNanos().nullValue()
        Property.Kind.Ip -> ip().nullValue()
        Property.Kind.GeoPoint -> geoPoint().nullValue()
        Property.Kind.Point -> point().nullValue()
        else -> null
    }
    return sentinel?.let(JsonData::of)
}

private fun Property.isAggregatable(): Boolean = when (_kind()) {
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    -> true
    Property.Kind.Flattened -> flattened().docValues() != false
    Property.Kind.Text -> text().fielddata() == true
    else -> (_get() as? DocValuesPropertyBase)?.let { it.docValues() != false } == true
}

private fun Property.isSortable(): Boolean = when (_kind()) {
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    -> true
    Property.Kind.Flattened -> flattened().docValues() != false
    Property.Kind.Text -> text().fielddata() == true
    else -> (_get() as? DocValuesPropertyBase)?.let { it.docValues() != false } == true
}

@Suppress("CyclomaticComplexMethod")
private fun Property.isIndexed(): Boolean = when (val property = _get()) {
    is ObjectProperty,
    is NestedProperty,
    -> false
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

/** Elasticsearch source filters use simple '*' wildcards, not regular expressions. */
private fun String.sourcePattern(): Regex = Regex(split('*').joinToString(".*", transform = Regex::escape))
