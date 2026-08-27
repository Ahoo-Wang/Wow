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

            fun visit(path: String, property: Property, projectionPath: String = path) {
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
                    aggregatable = property.isAggregatable(),
                    multiFields = multiFields,
                    projectionPath = projectionPath,
                )
                propertyBase?.fields().orEmpty().forEach { (name, field) ->
                    visit("$path.$name", field, projectionPath)
                }
                propertyBase?.properties().orEmpty().forEach { (name, field) ->
                    visit("$path.$name", field, "$projectionPath.$name")
                }
            }

            typeMapping.properties().forEach { (name, property) -> visit(name, property) }
            typeMapping.runtime().forEach { (name, runtimeField) ->
                if (runtimeField.type() == RuntimeFieldType.Composite) {
                    runtimeField.fields().forEach { (fieldName, field) ->
                        val path = "$name.$fieldName"
                        field.type().toMappedField()?.let { fields[path] = it }
                    }
                } else {
                    runtimeField.type().toMappedField()?.let { fields[name] = it }
                }
            }
            aliases.forEach { (name, target) ->
                fields[target]?.let {
                    fields[name] = it.copy(multiFields = emptySet())
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
    val multiFields: Set<String>,
    val projectionPath: String?,
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
        multiFields = emptySet(),
        projectionPath = null,
    )
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
