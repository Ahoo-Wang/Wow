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
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldValueKind
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

internal enum class ElasticsearchMappingUsage {
    EXACT,
    SEARCH,
    SORT,
    NESTED,
    SOURCE,
}

internal data class ElasticsearchMappingFieldRequirement(
    val path: String,
    val valueKind: QueryFieldValueKind,
    val collectionKind: QueryCollectionKind,
    val system: Boolean,
    val usage: ElasticsearchMappingUsage,
)

internal class ElasticsearchQueryReadinessRequirements(
    val configurationValid: Boolean,
    fields: Set<ElasticsearchMappingFieldRequirement>,
    val presenceVersion: Int,
    presenceFields: Set<String> = emptySet(),
) {
    val fields: Set<ElasticsearchMappingFieldRequirement> = immutable(fields)
    val presenceFields: Set<String> = immutable(presenceFields)

    private fun <T> immutable(source: Set<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(source))
}

internal interface ElasticsearchQueryMappingGuard {
    fun inspect(): Mono<QueryBackendReadiness>

    fun requireFields(fields: Set<ElasticsearchMappingFieldRequirement>)
}

internal class ElasticsearchQueryMappingSnapshot {
    private val mappings = AtomicReference<List<TypeMapping>>()

    fun get(): List<TypeMapping>? = mappings.get()

    fun set(mappings: List<TypeMapping>?) {
        this.mappings.set(mappings)
    }
}

internal class ElasticsearchQueryReadiness(
    private val client: ReactiveElasticsearchClient,
    private val index: String,
    private val requirements: ElasticsearchQueryReadinessRequirements,
    internal val mappingSnapshot: ElasticsearchQueryMappingSnapshot = ElasticsearchQueryMappingSnapshot(),
) : ElasticsearchQueryMappingGuard {
    override fun inspect(): Mono<QueryBackendReadiness> = Mono.defer {
        if (!requirements.configurationValid) {
            return@defer Mono.just(
                QueryBackendReadiness.NotReady(QueryBackendReadinessReason.CONFIGURATION_INVALID),
            )
        }
        mappingSnapshot.get()?.let { snapshot ->
            return@defer Mono.just(readiness(snapshot))
        }
        client.indices().exists(ExistsRequest.of { request -> request.index(index) })
            .flatMap<QueryBackendReadiness> { response ->
                if (!response.value()) {
                    return@flatMap Mono.just(
                        QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING),
                    )
                }
                client.indices().getMapping(GetMappingRequest.of { request -> request.index(index) })
                    .map<QueryBackendReadiness> { response ->
                        val loadedMappings = response.mappings().values.map { record -> record.mappings() }
                        val compatible = loadedMappings.isNotEmpty() && loadedMappings.all(::compatible)
                        if (compatible) mappingSnapshot.set(Collections.unmodifiableList(loadedMappings))
                        readiness(loadedMappings)
                    }
            }
            .onErrorResume {
                mappingSnapshot.set(null)
                Mono.just(
                    QueryBackendReadiness.NotReady(QueryBackendReadinessReason.DEPENDENCY_UNAVAILABLE),
                )
            }
    }

    override fun requireFields(fields: Set<ElasticsearchMappingFieldRequirement>) {
        val snapshot = mappingSnapshot.get() ?: mappingInvalid()
        if (snapshot.any { mapping -> fields.any { requirement -> !compatible(mapping, requirement) } }) {
            mappingInvalid()
        }
    }

    private fun readiness(snapshot: List<TypeMapping>): QueryBackendReadiness =
        if (snapshot.isNotEmpty() && snapshot.all(::compatible)) {
            QueryBackendReadiness.Ready
        } else {
            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)
        }

    private fun compatible(mapping: TypeMapping): Boolean {
        val version = runCatching {
            mapping.meta()[PRESENCE_VERSION_META]?.to(Int::class.javaObjectType)
        }.getOrNull()
        return version == requirements.presenceVersion &&
            requirements.fields.all { requirement -> compatible(mapping, requirement) } &&
            requirements.presenceFields.all { path -> propertyAt(mapping, path)?.isKeyword == true }
    }

    private fun compatible(mapping: TypeMapping, requirement: ElasticsearchMappingFieldRequirement): Boolean {
        val property = propertyAt(mapping, requirement.path) ?: return false
        return when (requirement.usage) {
            ElasticsearchMappingUsage.SEARCH -> property.hasDefaultSearchSemantics()
            ElasticsearchMappingUsage.NESTED -> property.isNested
            ElasticsearchMappingUsage.EXACT -> property.matches(requirement.valueKind, requirement.system)
            ElasticsearchMappingUsage.SOURCE -> property.matchesSource(requirement)
            ElasticsearchMappingUsage.SORT -> property.matches(requirement.valueKind, requirement.system) &&
                property.hasDocValues()
        }
    }

    private fun propertyAt(mapping: TypeMapping, path: String): Property? {
        var properties = mapping.properties()
        var current: Property? = null
        val segments = path.split('.')
        segments.forEachIndexed { index, segment ->
            current = properties[segment] ?: return null
            if (index < segments.lastIndex) {
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

    private fun Property.matches(kind: QueryFieldValueKind, system: Boolean): Boolean = when (kind) {
        QueryFieldValueKind.BOOLEAN -> isBoolean
        QueryFieldValueKind.INTEGER -> isLong
        QueryFieldValueKind.DECIMAL -> isDouble
        QueryFieldValueKind.STRING,
        QueryFieldValueKind.ENUM,
        -> isKeyword || isConstantKeyword
        QueryFieldValueKind.TIME -> if (system) isLong else isDate
        QueryFieldValueKind.BINARY -> isBinary
        QueryFieldValueKind.OBJECT -> isObject || isNested
        QueryFieldValueKind.MAP -> isObject
    }

    private fun Property.matchesSource(requirement: ElasticsearchMappingFieldRequirement): Boolean = when {
        requirement.collectionKind == QueryCollectionKind.OBJECT -> isNested
        requirement.valueKind == QueryFieldValueKind.STRING ->
            isText || matches(requirement.valueKind, requirement.system)
        else -> matches(requirement.valueKind, requirement.system)
    }

    private fun Property.hasDefaultSearchSemantics(): Boolean {
        if (!isText) {
            return false
        }
        val text = text()
        return text.analyzer() in setOf(null, DEFAULT_ANALYZER) &&
            text.searchAnalyzer() in setOf(null, DEFAULT_ANALYZER)
    }

    private fun Property.hasDocValues(): Boolean = when (_kind()) {
        Property.Kind.Keyword -> keyword().docValues() != false
        Property.Kind.ConstantKeyword -> true
        Property.Kind.Boolean -> boolean_().docValues() != false
        Property.Kind.Long -> long_().docValues() != false
        Property.Kind.Double -> double_().docValues() != false
        Property.Kind.Date -> date().docValues() != false
        else -> false
    }

    private fun mappingInvalid(): Nothing = throw me.ahoo.wow.api.query.error.QueryException(
        me.ahoo.wow.api.query.error.QueryErrorCode.BACKEND_NOT_READY,
        me.ahoo.wow.api.query.error.QueryStage.EXECUTION,
        me.ahoo.wow.api.query.error.QueryErrorReason.BACKEND_UNAVAILABLE,
    )

    companion object {
        internal const val PRESENCE_VERSION_META = "wow_query_presence_version"
        private const val DEFAULT_ANALYZER = "standard"
    }
}
