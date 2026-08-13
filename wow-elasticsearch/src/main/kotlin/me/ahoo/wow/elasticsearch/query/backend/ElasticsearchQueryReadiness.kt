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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.Collections

internal class ElasticsearchQueryReadinessRequirements(
    val configurationValid: Boolean,
    exactFields: Set<String>,
    searchFields: Set<String>,
    sortFields: Set<String>,
    nestedFields: Set<String>,
    val presenceVersion: Int,
    presenceFields: Set<String> = emptySet(),
) {
    val exactFields: Set<String> = immutable(exactFields)
    val searchFields: Set<String> = immutable(searchFields)
    val sortFields: Set<String> = immutable(sortFields)
    val nestedFields: Set<String> = immutable(nestedFields)
    val presenceFields: Set<String> = immutable(presenceFields)

    private fun immutable(source: Set<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(source))
}

internal class ElasticsearchQueryReadiness(
    private val client: ReactiveElasticsearchClient,
    private val index: String,
    private val requirements: ElasticsearchQueryReadinessRequirements,
) {
    fun inspect(): Mono<QueryBackendReadiness> = Mono.defer {
        if (!requirements.configurationValid) {
            return@defer Mono.just(
                QueryBackendReadiness.NotReady(QueryBackendReadinessReason.CONFIGURATION_INVALID),
            )
        }
        client.indices().exists(ExistsRequest.of { request -> request.index(index) })
            .flatMap<QueryBackendReadiness> { response ->
                if (!response.value()) {
                    return@flatMap Mono.just(
                        QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING),
                    )
                }
                client.indices().getMapping(GetMappingRequest.of { request -> request.index(index) })
                    .map<QueryBackendReadiness> { mapping ->
                        val compatible = mapping.mappings().isNotEmpty() &&
                            mapping.mappings().values.all { record -> compatible(record.mappings()) }
                        if (compatible) {
                            QueryBackendReadiness.Ready
                        } else {
                            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)
                        }
                    }
            }
            .onErrorResume {
                Mono.just(
                    QueryBackendReadiness.NotReady(
                        QueryBackendReadinessReason.DEPENDENCY_UNAVAILABLE,
                    ),
                )
            }
    }

    private fun compatible(mapping: TypeMapping): Boolean {
        val version = runCatching {
            mapping.meta()[PRESENCE_VERSION_META]?.to(Int::class.javaObjectType)
        }.getOrNull()
        if (version != requirements.presenceVersion) {
            return false
        }
        return requirements.exactFields.all { path -> propertyAt(mapping, path)?.isExact() == true } &&
            requirements.searchFields.all { path -> propertyAt(mapping, path)?.isSearch() == true } &&
            requirements.sortFields.all { path -> propertyAt(mapping, path)?.isSortable() == true } &&
            requirements.nestedFields.all { path -> propertyAt(mapping, path)?.isNested == true } &&
            requirements.presenceFields.all { path -> propertyAt(mapping, path)?.isKeyword == true }
    }

    private fun propertyAt(mapping: TypeMapping, path: String): Property? {
        var properties = mapping.properties()
        var current: Property? = null
        path.split('.').forEachIndexed { index, segment ->
            current = properties[segment] ?: return null
            if (index < path.count { it == '.' }) {
                properties = childProperties(current)
            }
        }
        return current
    }

    private fun childProperties(property: Property): Map<String, Property> = when {
        property.isObject -> property.`object`().properties()
        property.isNested -> property.nested().properties()
        property.isText -> property.text().fields()
        else -> emptyMap()
    }

    private fun Property.isExact(): Boolean = when (_kind()) {
        Property.Kind.Keyword,
        Property.Kind.ConstantKeyword,
        Property.Kind.IcuCollationKeyword,
        Property.Kind.Boolean,
        Property.Kind.Byte,
        Property.Kind.Short,
        Property.Kind.Integer,
        Property.Kind.Long,
        Property.Kind.HalfFloat,
        Property.Kind.Float,
        Property.Kind.Double,
        Property.Kind.ScaledFloat,
        Property.Kind.Date,
        Property.Kind.DateNanos,
        Property.Kind.Ip,
        -> true
        else -> false
    }

    private fun Property.isSortable(): Boolean = isExact()

    private fun Property.isSearch(): Boolean = when (_kind()) {
        Property.Kind.Text,
        Property.Kind.MatchOnlyText,
        Property.Kind.SearchAsYouType,
        -> true
        else -> false
    }

    companion object {
        internal const val PRESENCE_VERSION_META = "wow_query_presence_version"
    }
}
