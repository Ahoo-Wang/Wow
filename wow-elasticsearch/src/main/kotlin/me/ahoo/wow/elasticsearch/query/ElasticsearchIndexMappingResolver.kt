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
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate
import co.elastic.clients.elasticsearch._types.mapping.FlattenedProperty
import co.elastic.clients.elasticsearch._types.mapping.IcuCollationProperty
import co.elastic.clients.elasticsearch._types.mapping.IpProperty
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty
import co.elastic.clients.elasticsearch._types.mapping.MatchType
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

    companion object {
        fun from(indexName: String, typeMapping: TypeMapping): ElasticsearchIndexMapping {
            val fields = linkedMapOf<String, ElasticsearchMappedField>()
            val aliases = linkedMapOf<String, String>()

            fun visit(path: String, property: Property, parentDynamic: Boolean) {
                if (property.isAlias) {
                    property.alias().path()?.let { aliases[path] = it }
                    return
                }
                val propertyBase = property._get() as? PropertyBase
                val dynamic = property.hasDynamicChildren(parentDynamic)
                val dynamicChildrenQueryable = property.dynamicChildrenQueryable(dynamic)
                val multiFields = propertyBase?.fields().orEmpty().keys.mapTo(linkedSetOf()) { "$path.$it" }
                fields[path] = ElasticsearchMappedField(
                    kind = property._kind(),
                    indexed = property.isIndexed(),
                    sortable = property.isSortable(),
                    aggregatable = property.isAggregatable(),
                    dynamicChildrenQueryable = dynamicChildrenQueryable,
                    dynamicChildrenExact = property.provesDynamicChildrenExact(
                        path,
                        dynamicChildrenQueryable,
                        typeMapping,
                    ),
                    multiFields = multiFields,
                )
                propertyBase?.fields().orEmpty().forEach { (name, field) -> visit("$path.$name", field, dynamic) }
                propertyBase?.properties().orEmpty().forEach { (name, field) -> visit("$path.$name", field, dynamic) }
            }

            val rootDynamic = typeMapping.dynamic().allowsDynamicFields(parent = true)
            typeMapping.properties().forEach { (name, property) -> visit(name, property, rootDynamic) }
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
                    fields[name] = it.copy(
                        dynamicChildrenQueryable = false,
                        dynamicChildrenExact = false,
                        multiFields = emptySet(),
                    )
                }
            }
            return ElasticsearchIndexMapping(indexName, fields.toMap())
        }
    }
}

internal data class ElasticsearchMappedField(
    val kind: Property.Kind,
    val indexed: Boolean,
    val sortable: Boolean,
    val aggregatable: Boolean,
    val dynamicChildrenQueryable: Boolean,
    val dynamicChildrenExact: Boolean,
    val multiFields: Set<String>,
)

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
        aggregatable = true,
        dynamicChildrenQueryable = false,
        dynamicChildrenExact = false,
        multiFields = emptySet(),
    )
}

private fun DynamicMapping?.allowsDynamicFields(parent: Boolean): Boolean = when (this) {
    null -> parent
    DynamicMapping.True,
    DynamicMapping.Runtime,
    -> true
    DynamicMapping.False,
    DynamicMapping.Strict,
    -> false
}

private fun Property.hasDynamicChildren(parent: Boolean): Boolean =
    (_get() as? PropertyBase)?.dynamic().allowsDynamicFields(parent) &&
        (!isObject || `object`().enabled() != false)

private fun Property.dynamicChildrenQueryable(dynamic: Boolean): Boolean = when (_kind()) {
    Property.Kind.Flattened -> isIndexed()
    Property.Kind.Object,
    Property.Kind.Nested,
    -> dynamic && isIndexed()
    else -> false
}

private fun Property.provesDynamicChildrenExact(
    path: String,
    queryable: Boolean,
    mapping: TypeMapping,
): Boolean = when (_kind()) {
    Property.Kind.Flattened -> queryable
    Property.Kind.Object,
    Property.Kind.Nested,
    -> queryable && mapping.provesDynamicExact(path)
    else -> false
}

private fun TypeMapping.provesDynamicExact(path: String): Boolean {
    dynamicTemplates().forEach { namedTemplate ->
        val template = namedTemplate.value()
        if (template.matchMappingType() != listOf("string")) return@forEach
        when (template.coverage(path)) {
            TemplateCoverage.NONE -> Unit
            TemplateCoverage.PARTIAL -> return false
            TemplateCoverage.ALL -> return template.mapsExactString()
        }
    }
    return false
}

private fun DynamicTemplate.mapsExactString(): Boolean =
    isMapping && mapping().isIndexed() && mapping()._kind() in DYNAMIC_EXACT_KINDS

private fun DynamicTemplate.coverage(path: String): TemplateCoverage {
    if (matchPattern() == MatchType.Regex) return TemplateCoverage.PARTIAL
    val nameCoverage = nameCoverage()
    val pathCoverage = pathCoverage(path)
    if (pathCoverage == TemplateCoverage.NONE) return TemplateCoverage.NONE
    if (hasExclusions()) return TemplateCoverage.PARTIAL
    return if (nameCoverage == TemplateCoverage.ALL && pathCoverage == TemplateCoverage.ALL) {
        TemplateCoverage.ALL
    } else {
        TemplateCoverage.PARTIAL
    }
}

private fun DynamicTemplate.nameCoverage(): TemplateCoverage =
    if (match().isEmpty() || "*" in match()) TemplateCoverage.ALL else TemplateCoverage.PARTIAL

private fun DynamicTemplate.pathCoverage(path: String): TemplateCoverage = when {
    pathMatch().isEmpty() || pathMatch().any { it == "*" || it == "$path.*" } -> TemplateCoverage.ALL
    pathMatch().any { it.couldMatchChildOf(path) } -> TemplateCoverage.PARTIAL
    else -> TemplateCoverage.NONE
}

private fun DynamicTemplate.hasExclusions(): Boolean =
    unmatch().isNotEmpty() || pathUnmatch().isNotEmpty() || unmatchMappingType().isNotEmpty()

private fun String.couldMatchChildOf(path: String): Boolean {
    val childPrefix = "$path."
    val literalPrefix = takeWhile { it != '*' && it != '?' }
    return literalPrefix.isEmpty() || childPrefix.startsWith(literalPrefix) || literalPrefix.startsWith(childPrefix)
}

private enum class TemplateCoverage {
    NONE,
    PARTIAL,
    ALL,
}

private val DYNAMIC_EXACT_KINDS = setOf(
    Property.Kind.Keyword,
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    Property.Kind.IcuCollationKeyword,
    Property.Kind.Wildcard,
)

private fun Property.isAggregatable(): Boolean = when (_kind()) {
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    -> true
    Property.Kind.Flattened -> flattened().docValues() != false
    Property.Kind.Text -> false
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

private fun Property.isIndexed(): Boolean = when (val property = _get()) {
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
