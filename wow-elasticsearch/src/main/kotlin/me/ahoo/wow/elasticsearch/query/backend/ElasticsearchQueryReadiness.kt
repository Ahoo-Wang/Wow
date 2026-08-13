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

import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
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
    val maxStringLength: Int? = null,
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

internal data class ElasticsearchIndexMappingSnapshot(
    val mapping: co.elastic.clients.elasticsearch._types.mapping.TypeMapping,
    val settings: co.elastic.clients.elasticsearch.indices.IndexSettings,
)

internal class ElasticsearchQueryMappingSnapshot {
    private val mappings = AtomicReference<List<ElasticsearchIndexMappingSnapshot>>()

    fun get(): List<ElasticsearchIndexMappingSnapshot>? = mappings.get()

    fun set(mappings: List<ElasticsearchIndexMappingSnapshot>?) {
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
                    .flatMap { mappingResponse ->
                        client.indices().getSettings(
                            GetIndicesSettingsRequest.of { request ->
                                request.index(index).includeDefaults(true)
                            },
                        ).map<QueryBackendReadiness> { settingsResponse ->
                            val loadedMappings = mappingResponse.mappings().mapNotNull { (indexName, record) ->
                                settingsResponse.settings()[indexName]?.settings()?.let { settings ->
                                    ElasticsearchIndexMappingSnapshot(record.mappings(), settings)
                                }
                            }
                            val complete = loadedMappings.size == mappingResponse.mappings().size
                            val compatible = complete && loadedMappings.isNotEmpty() && loadedMappings.all(::compatible)
                            if (compatible) mappingSnapshot.set(Collections.unmodifiableList(loadedMappings))
                            readiness(loadedMappings, complete)
                        }
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

    private fun readiness(
        snapshot: List<ElasticsearchIndexMappingSnapshot>,
        complete: Boolean = true,
    ): QueryBackendReadiness =
        if (complete && snapshot.isNotEmpty() && snapshot.all(::compatible)) {
            QueryBackendReadiness.Ready
        } else {
            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)
        }

    private fun compatible(snapshot: ElasticsearchIndexMappingSnapshot): Boolean {
        val mapping = snapshot.mapping
        val version = runCatching {
            mapping.meta()[PRESENCE_VERSION_META]?.to(Int::class.javaObjectType)
        }.getOrNull()
        val managedPresenceTemplates = mapping.hasManagedPresenceTemplateContract()
        return version == requirements.presenceVersion &&
            requirements.fields.all { requirement -> compatible(snapshot, requirement) } &&
            requirements.presenceFields.all { path ->
                propertyAt(mapping, path)?.hasManagedKeywordSemantics() ?: managedPresenceTemplates
            }
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.TypeMapping.hasManagedPresenceTemplateContract(): Boolean {
        val contractVersion = runCatching {
            meta()[PRESENCE_TEMPLATE_VERSION_META]?.to(Int::class.javaObjectType)
        }.getOrNull()
        if (contractVersion != requirements.presenceVersion) return false
        val templates = dynamicTemplates()
        if (templates.size < PRESENCE_TEMPLATE_NAMES.size) return false
        return PRESENCE_TEMPLATE_NAMES.entries.withIndex().all { (index, expected) ->
            val actual = templates[index]
            actual.name() == expected.key && actual.value().matchesPresenceTemplate(expected.value)
        }
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate.matchesPresenceTemplate(
        marker: String,
    ): Boolean =
        pathMatch() == listOf(
            "${ElasticsearchQueryPresenceEncoder.NAMESPACE}.$marker",
            "*.${ElasticsearchQueryPresenceEncoder.NAMESPACE}.$marker",
        ) &&
            matchMappingType() == listOf("string") &&
            match().isEmpty() &&
            unmatch().isEmpty() &&
            pathUnmatch().isEmpty() &&
            unmatchMappingType().isEmpty() &&
            matchPattern() == null &&
            isMapping &&
            mapping().hasManagedKeywordSemantics()

    private fun compatible(
        snapshot: ElasticsearchIndexMappingSnapshot,
        requirement: ElasticsearchMappingFieldRequirement,
    ): Boolean {
        val property = propertyAt(snapshot.mapping, requirement.path) ?: return false
        return when (requirement.usage) {
            ElasticsearchMappingUsage.SEARCH -> property.hasDefaultSearchSemantics(snapshot.settings)
            ElasticsearchMappingUsage.NESTED -> property.isNested
            ElasticsearchMappingUsage.EXACT -> property.hasManagedExactSemantics(requirement)
            ElasticsearchMappingUsage.SOURCE -> property.matchesSource(requirement)
            ElasticsearchMappingUsage.SORT -> property.hasManagedExactSemantics(requirement) &&
                property.hasDocValues()
        }
    }

    private fun propertyAt(
        mapping: co.elastic.clients.elasticsearch._types.mapping.TypeMapping,
        path: String,
    ): co.elastic.clients.elasticsearch._types.mapping.Property? {
        var properties = mapping.properties()
        var current: co.elastic.clients.elasticsearch._types.mapping.Property? = null
        val segments = path.split('.')
        segments.forEachIndexed { index, segment ->
            current = properties[segment] ?: return null
            if (index < segments.lastIndex) {
                properties = childProperties(current)
            }
        }
        return current
    }

    private fun childProperties(
        property: co.elastic.clients.elasticsearch._types.mapping.Property,
    ): Map<String, co.elastic.clients.elasticsearch._types.mapping.Property> = when {
        property.isObject -> property.`object`().properties()
        property.isNested -> property.nested().properties()
        property.isText -> property.text().fields()
        else -> emptyMap()
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.matches(
        kind: QueryFieldValueKind,
        system: Boolean,
    ): Boolean = when (kind) {
        QueryFieldValueKind.BOOLEAN -> isBoolean
        QueryFieldValueKind.INTEGER -> isLong
        QueryFieldValueKind.DECIMAL -> isDouble
        QueryFieldValueKind.STRING,
        QueryFieldValueKind.ENUM,
        -> isKeyword
        QueryFieldValueKind.TIME -> if (system) isLong else isDate
        QueryFieldValueKind.BINARY -> isBinary
        QueryFieldValueKind.OBJECT -> isObject || isNested
        QueryFieldValueKind.MAP -> isObject
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.matchesSource(
        requirement: ElasticsearchMappingFieldRequirement,
    ): Boolean = when {
        requirement.valueKind == QueryFieldValueKind.OBJECT -> isObject || isNested
        requirement.valueKind == QueryFieldValueKind.STRING ->
            isText || isKeyword || isConstantKeyword
        requirement.valueKind == QueryFieldValueKind.ENUM -> isKeyword || isConstantKeyword
        else -> matches(requirement.valueKind, requirement.system)
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.hasManagedExactSemantics(
        requirement: ElasticsearchMappingFieldRequirement,
    ): Boolean {
        if (!matches(requirement.valueKind, requirement.system) || !isIndexed() || hasNullSentinel()) {
            return false
        }
        return when {
            isKeyword -> hasManagedKeywordSemantics(requirement.maxStringLength)
            isDate -> date().format().isCompatibleApplicationTimeFormat()
            isBinary -> false
            else -> true
        }
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.hasManagedKeywordSemantics(
        maxStringLength: Int? = null,
    ): Boolean {
        if (!isKeyword) return false
        val keyword = keyword()
        if (keyword.index() == false || keyword.normalizer() != null || keyword.nullValue() != null) {
            return false
        }
        val ignoreAbove = keyword.ignoreAbove() ?: return true
        return maxStringLength != null && ignoreAbove.toLong() >= maxStringLength.toLong() * MAX_UTF8_BYTES_PER_CHAR
    }

    private fun String?.isCompatibleApplicationTimeFormat(): Boolean =
        this == null || split("||").any { format -> format == DEFAULT_DATE_FORMAT }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.isIndexed(): Boolean = when (_kind()) {
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Keyword -> keyword().index() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Boolean -> boolean_().index() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Long -> long_().index() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Double -> double_().index() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Date -> date().index() != false
        else -> false
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.hasDefaultSearchSemantics(
        settings: co.elastic.clients.elasticsearch.indices.IndexSettings,
    ): Boolean {
        if (!isText) {
            return false
        }
        val text = text()
        if (text.index() == false) {
            return false
        }
        val analyzers = settings.analysis()?.analyzer().orEmpty()
        val fieldAnalyzer = text.analyzer()
        val fieldSearchAnalyzer = text.searchAnalyzer()
        val indexStandard = fieldAnalyzer?.let { name -> analyzers.isManagedStandard(name) }
            ?: analyzers.defaultIsManagedStandard()
        val searchStandard = when {
            fieldSearchAnalyzer != null -> analyzers.isManagedStandard(fieldSearchAnalyzer)
            fieldAnalyzer != null -> analyzers.isManagedStandard(fieldAnalyzer)
            analyzers.containsKey(INDEX_DEFAULT_SEARCH_ANALYZER) ->
                analyzers.getValue(INDEX_DEFAULT_SEARCH_ANALYZER).isManagedStandard()
            else -> analyzers.defaultIsManagedStandard()
        }
        return indexStandard && searchStandard
    }

    private fun Map<String, co.elastic.clients.elasticsearch._types.analysis.Analyzer>.defaultIsManagedStandard(): Boolean =
        get(INDEX_DEFAULT_ANALYZER)?.isManagedStandard() ?: true

    private fun Map<String, co.elastic.clients.elasticsearch._types.analysis.Analyzer>.isManagedStandard(
        name: String,
    ): Boolean = get(name)?.isManagedStandard() ?: (name == DEFAULT_ANALYZER)

    private fun co.elastic.clients.elasticsearch._types.analysis.Analyzer.isManagedStandard(): Boolean {
        if (!isStandard) return false
        val standard = standard()
        return standard.maxTokenLength() == null && standard.stopwordsPath() == null && standard.stopwords().isEmpty()
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.hasNullSentinel(): Boolean = when (_kind()) {
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Keyword -> keyword().nullValue() != null
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Boolean -> boolean_().nullValue() != null
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Long -> long_().nullValue() != null
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Double -> double_().nullValue() != null
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Date -> date().nullValue() != null
        else -> false
    }

    private fun co.elastic.clients.elasticsearch._types.mapping.Property.hasDocValues(): Boolean = when (_kind()) {
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Keyword -> keyword().docValues() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Boolean -> boolean_().docValues() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Long -> long_().docValues() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Double -> double_().docValues() != false
        co.elastic.clients.elasticsearch._types.mapping.Property.Kind.Date -> date().docValues() != false
        else -> false
    }

    private fun mappingInvalid(): Nothing = throw me.ahoo.wow.api.query.error.QueryException(
        me.ahoo.wow.api.query.error.QueryErrorCode.BACKEND_NOT_READY,
        me.ahoo.wow.api.query.error.QueryStage.EXECUTION,
        me.ahoo.wow.api.query.error.QueryErrorReason.BACKEND_UNAVAILABLE,
    )

    companion object {
        internal const val PRESENCE_VERSION_META = "wow_query_presence_version"
        internal const val PRESENCE_TEMPLATE_VERSION_META = "wow_query_presence_template_version"
        private val PRESENCE_TEMPLATE_NAMES = linkedMapOf(
            "wow_query_present_keyword" to ElasticsearchQueryPresenceEncoder.PRESENT,
            "wow_query_null_keyword" to ElasticsearchQueryPresenceEncoder.NULL,
        )
        private const val DEFAULT_ANALYZER = "standard"
        private const val INDEX_DEFAULT_ANALYZER = "default"
        private const val INDEX_DEFAULT_SEARCH_ANALYZER = "default_search"
        private const val DEFAULT_DATE_FORMAT = "strict_date_optional_time"
        private const val MAX_UTF8_BYTES_PER_CHAR = 4L
    }
}
