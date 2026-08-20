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

package me.ahoo.wow.elasticsearch.query.gateway

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.IndexSettings
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.query.backend.SecuredQuery
import org.springframework.data.elasticsearch.RestStatusException
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class ElasticsearchQueryBackendOptions(
    val exactSubfields: Map<LogicalField, String> = emptyMap(),
    val pitPageSize: Int = 256,
    val pitKeepAlive: String = "1m",
    val maxResultWindow: Int = 10_000,
    val mappingCacheTtl: Duration = Duration.ofSeconds(30)
) {
    init {
        require(exactSubfields.values.none(String::isBlank)) { "Exact subfield cannot be blank." }
        require(pitPageSize > 0) { "pitPageSize must be positive." }
        require(pitKeepAlive.isNotBlank()) { "pitKeepAlive cannot be blank." }
        require(maxResultWindow > 0) { "maxResultWindow must be positive." }
        require(!mappingCacheTtl.isNegative && !mappingCacheTtl.isZero) { "mappingCacheTtl must be positive." }
    }
}

internal data class ElasticsearchFieldBinding(
    val source: String,
    val exact: String?,
    val search: String?,
    val sort: String?,
    val nested: String?
)

internal data class ElasticsearchExecutionSnapshot(
    val indices: List<String>,
    val fields: Map<LogicalField, ElasticsearchFieldBinding>,
    val schema: me.ahoo.wow.query.schema.QuerySchema
) {
    fun field(logical: LogicalField): ElasticsearchFieldBinding = fields[logical] ?: notReady()
}

internal class ElasticsearchQueryMapping(
    private val client: ReactiveElasticsearchClient,
    private val options: ElasticsearchQueryBackendOptions
) {
    private val viewsByIndex = ConcurrentHashMap<String, Mono<List<MappingView>>>()
    internal var onLoaded: () -> Unit = {}

    fun snapshot(query: SecuredQuery): Mono<ElasticsearchExecutionSnapshot> {
        val index = query.target.toSnapshotIndexName()
        return views(index).map { views ->
            val indices = views.map(MappingView::name)
            val required = requiredFields(query)
            val fields = required.associateWith { field -> bind(field, query, views) }
            ElasticsearchExecutionSnapshot(indices, fields, query.schema)
        }
    }

    private fun views(index: String): Mono<List<MappingView>> = viewsByIndex.computeIfAbsent(index) {
        loadViews(index)
            .doOnSuccess { onLoaded() }
            .doOnError { viewsByIndex.remove(index) }
            .cache(options.mappingCacheTtl)
    }

    private fun loadViews(index: String): Mono<List<MappingView>> =
        client.indices().getMapping(GetMappingRequest.of { request -> request.index(index) })
            .flatMap { mappings ->
                if (mappings.mappings().isEmpty()) {
                    return@flatMap Mono.error(notReadyException())
                }
                client.indices().getSettings(
                    GetIndicesSettingsRequest.of { request -> request.index(index).includeDefaults(true) }
                ).map { settings ->
                    val indices = mappings.mappings().keys.sorted()
                    indices.map { name ->
                        val mapping = mappings.mappings()[name]?.mappings() ?: notReady()
                        val indexSettings = settings.settings()[name]?.settings() ?: notReady()
                        MappingView(name, mapping, indexSettings)
                    }
                }
            }
            .onErrorMap(::mapMappingError)

    private fun bind(
        logical: LogicalField,
        query: SecuredQuery,
        views: List<MappingView>
    ): ElasticsearchFieldBinding {
        val sorted = query.sort.any { sort -> sort.field == logical }
        val usages = fieldUsages(query.filter, logical) + if (sorted) {
            setOf(Usage.SORT)
        } else {
            emptySet()
        }
        val bindings = views.map { view -> bind(logical, usages, view) }.distinct()
        return bindings.singleOrNull() ?: notReady()
    }

    private fun bind(logical: LogicalField, usages: Set<Usage>, view: MappingView): ElasticsearchFieldBinding {
        val path = logical.value
        val property = propertyAt(view.mapping, path)
        if (property == null && usages == setOf(Usage.PRESENCE)) {
            return ElasticsearchFieldBinding(path, null, null, null, null)
        }
        property ?: notReady()
        val requiresExact = usages.any { usage -> usage == Usage.EXACT || usage == Usage.SORT }
        val exact = if (requiresExact) {
            exact(path, logical, property, view.mapping)
        } else {
            null
        }
        val search = if (Usage.SEARCH in usages) {
            if (!property.hasSearchSemantics(view.settings)) notReady()
            path
        } else {
            null
        }
        val sort = if (Usage.SORT in usages) {
            val sortField = exact ?: notReady()
            val sortProperty = propertyAt(view.mapping, sortField) ?: notReady()
            if (!sortProperty.hasDocValues()) notReady()
            sortField
        } else {
            null
        }
        val nested = if (Usage.NESTED in usages) {
            if (!property.isNested) notReady()
            path
        } else {
            null
        }
        return ElasticsearchFieldBinding(path, exact, search, sort, nested)
    }

    private fun exact(path: String, logical: LogicalField, property: Property, mapping: TypeMapping): String {
        if (property.hasExactSemantics()) return path
        if (!property.isText) notReady()
        val configured = options.exactSubfields[logical]
        if (configured != null) {
            val configuredPath = "$path.$configured"
            if (propertyAt(mapping, configuredPath)?.hasExactSemantics() != true) notReady()
            return configuredPath
        }
        val candidates = property.text().fields().filterValues { candidate -> candidate.hasExactSemantics() }.keys.sorted()
        if (candidates.size != 1) notReady()
        return "$path.${candidates.single()}"
    }

    private fun requiredFields(query: SecuredQuery): Set<LogicalField> = linkedSetOf<LogicalField>().apply {
        addAll(expressionFields(query.filter))
        addAll(query.sort.map { it.field })
    }

    private fun expressionFields(expression: QueryExpression, prefix: String? = null): Set<LogicalField> =
        linkedSetOf<LogicalField>().apply {
            when (expression) {
                is PredicateExpression -> add(effective(expression.field, prefix))
                is SearchExpression -> expression.fields.mapTo(this) { effective(it, prefix) }
                is LogicalExpression -> expression.operands.forEach { addAll(expressionFields(it, prefix)) }
                is ElementMatchExpression -> {
                    val nested = effective(expression.field, prefix)
                    add(nested)
                    addAll(expressionFields(expression.predicate, nested.value))
                }

                else -> Unit
            }
        }

    private fun fieldUsages(expression: QueryExpression, target: LogicalField, prefix: String? = null): Set<Usage> =
        linkedSetOf<Usage>().apply {
            when (expression) {
                is PredicateExpression -> if (effective(expression.field, prefix) == target) add(expression.usage())
                is SearchExpression -> if (expression.fields.any { effective(it, prefix) == target }) add(Usage.SEARCH)
                is LogicalExpression -> expression.operands.forEach { addAll(fieldUsages(it, target, prefix)) }
                is ElementMatchExpression -> {
                    val nested = effective(expression.field, prefix)
                    if (nested == target) add(Usage.NESTED)
                    addAll(fieldUsages(expression.predicate, target, nested.value))
                }

                else -> Unit
            }
        }

    private fun PredicateExpression.usage(): Usage = when (operator) {
        PredicateOperator.IS_NULL,
        PredicateOperator.IS_NOT_NULL,
        PredicateOperator.EXISTS,
        PredicateOperator.IS_EMPTY -> Usage.PRESENCE

        PredicateOperator.EQ,
        PredicateOperator.NE -> if (values.single().isNull) Usage.PRESENCE else Usage.EXACT

        PredicateOperator.IN,
        PredicateOperator.NOT_IN -> if (values.all { it.isNull }) Usage.PRESENCE else Usage.EXACT

        else -> Usage.EXACT
    }

    private fun effective(field: LogicalField, prefix: String?): LogicalField =
        if (prefix == null) field else LogicalField("$prefix.${field.value}")

    private fun propertyAt(mapping: TypeMapping, path: String): Property? {
        var properties = mapping.properties()
        var current: Property? = null
        path.split('.').forEachIndexed { index, segment ->
            current = properties[segment] ?: return null
            if (index < path.count { it == '.' }) {
                val resolved = current
                properties = when {
                    resolved.isObject -> resolved.`object`().properties()
                    resolved.isNested -> resolved.nested().properties()
                    resolved.isText -> resolved.text().fields()
                    else -> emptyMap()
                }
            }
        }
        return current
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Property.hasExactSemantics(): Boolean = when {
        isKeyword -> keyword().index() != false && keyword().normalizer() == null &&
            keyword().nullValue() == null && keyword().ignoreAbove() == null
        isConstantKeyword -> true
        isBoolean -> boolean_().index() != false && boolean_().nullValue() == null
        isInteger -> integer().index() != false && integer().nullValue() == null
        isLong -> long_().index() != false && long_().nullValue() == null
        isDouble -> double_().index() != false && double_().nullValue() == null
        isDate -> date().index() != false && date().nullValue() == null
        else -> false
    }

    private fun Property.hasDocValues(): Boolean = when {
        isKeyword -> keyword().docValues() != false
        isConstantKeyword -> true
        isBoolean -> boolean_().docValues() != false
        isInteger -> integer().docValues() != false
        isLong -> long_().docValues() != false
        isDouble -> double_().docValues() != false
        isDate -> date().docValues() != false
        else -> false
    }

    private fun Property.hasSearchSemantics(settings: IndexSettings): Boolean {
        if (!isText || text().index() == false) return false
        val configured = settings.analysis()?.analyzer().orEmpty()
        val indexAnalyzer = text().analyzer()
        val searchAnalyzer = text().searchAnalyzer()
        return (indexAnalyzer == null || indexAnalyzer == STANDARD || configured[indexAnalyzer]?.isStandard == true) &&
            (searchAnalyzer == null || searchAnalyzer == STANDARD || configured[searchAnalyzer]?.isStandard == true)
    }

    private data class MappingView(val name: String, val mapping: TypeMapping, val settings: IndexSettings)

    private enum class Usage {
        EXACT,
        SEARCH,
        SORT,
        NESTED,
        PRESENCE
    }

    private companion object {
        const val STANDARD = "standard"
    }
}

internal fun mapMappingError(error: Throwable): Throwable = when {
    error is QueryException -> error
    error is RestStatusException && error.status == NOT_FOUND -> notReadyException()
    error is ElasticsearchException && error.response().status() == NOT_FOUND -> notReadyException()
    else -> QueryException(QueryErrorCode.BACKEND_FAILURE, QueryStage.BACKEND)
}

internal fun notReady(): Nothing = throw QueryException(QueryErrorCode.BACKEND_NOT_READY, QueryStage.BACKEND)

private fun notReadyException(): QueryException = QueryException(QueryErrorCode.BACKEND_NOT_READY, QueryStage.BACKEND)

private const val NOT_FOUND = 404
