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

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
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
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.IndexSettings
import co.elastic.clients.json.JsonData
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import java.util.concurrent.ConcurrentHashMap

/**
 * The mapping of an index, cached per index until [refresh].
 *
 * Wow creates an aggregate's index on its first write, or when the query side prepares it at startup, so until then
 * the index may not exist. Its mapping is then the one the index will be created with, [provisional]'s: that mapping
 * is [provisional][ElasticsearchIndexMapping.provisional] and never cached, so the next load reads the index once it
 * exists.
 */
internal class ElasticsearchIndexMappingResolver(
    private val client: ElasticsearchAsyncClient,
    private val provisional: (String) -> TypeMapping = { TypeMapping.of { it } },
) {
    private val mappings = ConcurrentHashMap<String, ElasticsearchIndexMapping>()

    suspend fun currentOrLoad(indexName: String): ElasticsearchIndexMapping = mappings[indexName] ?: refresh(indexName)

    suspend fun refresh(indexName: String): ElasticsearchIndexMapping = try {
        existing(indexName).also { mappings[indexName] = it }
    } catch (failure: ElasticsearchException) {
        if (!ElasticsearchFailures.isIndexNotFound(failure)) throw failure
        mappings.remove(indexName)
        ElasticsearchIndexMapping.from(indexName, provisional(indexName), provisional = true, singleShard = true)
    }

    /** Forgets the mapping of [indexName], which changed: the next load reads it. */
    fun evict(indexName: String) {
        mappings.remove(indexName)
    }

    private suspend fun existing(indexName: String): ElasticsearchIndexMapping {
        val mapping = client.indices().getMapping(GetMappingRequest.of { it.index(indexName) }).awaitElasticsearch()
        val settings = client.indices().getSettings(maxResultWindowRequest(indexName)).awaitElasticsearch()
        val maxResultWindow = settings.maxResultWindow()
        val singleShard = settings.singleShard()
        return ElasticsearchIndexMapping.common(
            indexName,
            mapping.mappings().mapValues { (_, record) ->
                ElasticsearchIndexMapping.from(
                    indexName,
                    record.mappings(),
                    maxResultWindow = maxResultWindow,
                    singleShard = singleShard,
                )
            },
        )
    }
}

/** Elasticsearch's default `index.max_result_window`: the furthest record `from + size` may reach. */
internal const val DEFAULT_MAX_RESULT_WINDOW = 10_000

private const val MAX_RESULT_WINDOW_SETTING = "index.max_result_window"
private const val NUMBER_OF_SHARDS_SETTING = "index.number_of_shards"

private fun maxResultWindowRequest(indexName: String): GetIndicesSettingsRequest =
    GetIndicesSettingsRequest.of { it.index(indexName).name(MAX_RESULT_WINDOW_SETTING, NUMBER_OF_SHARDS_SETTING) }

/** Whether the name is one physical index with one primary shard (Elasticsearch's default when none is declared). */
private fun GetIndicesSettingsResponse.singleShard(): Boolean =
    settings().size == 1 && (settings().values.single().settings()?.declaredShards() ?: 1) == 1

private fun IndexSettings.declaredShards(): Int? = (index()?.numberOfShards() ?: numberOfShards())?.toIntOrNull()

/** The window the index declares, or the default when it declares none; the narrowest over several indices. */
private fun GetIndicesSettingsResponse.maxResultWindow(): Int = settings().values.minOfOrNull { state ->
    state.settings()?.declaredMaxResultWindow() ?: DEFAULT_MAX_RESULT_WINDOW
} ?: DEFAULT_MAX_RESULT_WINDOW

private fun IndexSettings.declaredMaxResultWindow(): Int? = index()?.maxResultWindow() ?: maxResultWindow()

@ConsistentCopyVisibility
internal data class ElasticsearchIndexMapping private constructor(
    val indexName: String,
    internal val fields: Map<String, ElasticsearchMappedField>,
    internal val sourceEnabled: Boolean,
    internal val sourceIncludes: List<String>,
    internal val sourceExcludes: List<String>,
    /** The index's `index.max_result_window`: an offset page may not reach beyond it. */
    val maxResultWindow: Int,
    /**
     * Whether the index does not exist yet: the mapping is the one its templates will create it with, and every
     * query of it answers nothing.
     */
    val provisional: Boolean,
    /**
     * Whether the name is one physical index with one primary shard. A `terms` aggregation ordered by a metric merges
     * each shard's top buckets, so only then are its first groups exact.
     */
    val singleShard: Boolean = false,
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
        /**
         * The mapping of a name that resolves to several physical indices (an alias over rollover indices, a data
         * stream): the fields every index maps the same way. A field one index lacks or maps otherwise is left out,
         * so no query sorts, filters or groups on a field some of the indices cannot serve the same way; it becomes
         * queryable once every index behind the name maps it alike. The indices must agree on `_source`, which
         * decides what a projection returns; otherwise the name fails closed.
         */
        internal fun common(
            indexName: String,
            mappings: Map<String, ElasticsearchIndexMapping>,
        ): ElasticsearchIndexMapping {
            require(mappings.isNotEmpty()) { "Elasticsearch index [$indexName] resolved to no physical index." }
            val first = mappings.values.first()
            if (mappings.size == 1) return first
            // Several physical indices merge their top buckets as shards do.
            val sources = mappings.mapValues { (_, mapping) ->
                Triple(mapping.sourceEnabled, mapping.sourceIncludes, mapping.sourceExcludes)
            }
            require(sources.values.distinct().size == 1) {
                "Elasticsearch index [$indexName] resolves to physical indices whose _source settings differ: $sources."
            }
            val fields = first.fields.filter { (path, field) ->
                mappings.values.all { it.fields[path]?.mapsAlike(field) == true }
            }
            return first.copy(fields = fields, singleShard = false)
        }

        @Suppress(
            "CyclomaticComplexMethod"
        ) // One mapping walk carries ancestor facts into properties, multifields and aliases.
        fun from(
            indexName: String,
            typeMapping: TypeMapping,
            maxResultWindow: Int = DEFAULT_MAX_RESULT_WINDOW,
            provisional: Boolean = false,
            singleShard: Boolean = false,
        ): ElasticsearchIndexMapping {
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
                maxResultWindow = maxResultWindow,
                provisional = provisional,
                singleShard = singleShard,
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
) {
    /** The same mapping in another index; [JsonData] has no value equality, so [nullValue] compares by its text. */
    fun mapsAlike(other: ElasticsearchMappedField): Boolean =
        copy(nullValue = null) == other.copy(nullValue = null) && nullValue?.toString() == other.nullValue?.toString()
}

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
