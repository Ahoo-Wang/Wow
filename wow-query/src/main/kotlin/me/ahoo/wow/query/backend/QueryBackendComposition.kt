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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.backend

import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import java.util.Collections
import java.util.LinkedHashMap

@ExperimentalQueryBackendApi
enum class BackendStreamSupport {
    NONE,
    BOUNDED_ONLY,
}

/** One atomic, statically ready schema/backend registration. */
@ExperimentalQueryBackendApi
class RecordQueryBackendContribution(
    val schema: QueryDocumentSchema,
    val backendId: BackendId,
    supportedOperations: Set<QueryOperation>,
    val streamSupport: BackendStreamSupport,
    semanticTiers: Set<SemanticTier>,
    fieldCapabilities: Map<QueryFieldId, Set<FieldCapability>>,
    searchScopes: Set<SearchScopeId> = emptySet(),
    val backend: RecordQueryBackend,
    val analyticsBackend: AnalyticsQueryBackend? = null,
    val mappingGenerationDigest: String = schema.contractId.value,
) {
    val supportedOperations: Set<QueryOperation> = Collections.unmodifiableSet(
        LinkedHashSet(supportedOperations.sortedBy(QueryOperation::name)),
    )
    val semanticTiers: Set<SemanticTier> = Collections.unmodifiableSet(
        LinkedHashSet(semanticTiers.sortedBy(SemanticTier::name)),
    )
    val fieldCapabilities: Map<QueryFieldId, Set<FieldCapability>> = immutableCapabilities(fieldCapabilities)
    val searchScopes: Set<SearchScopeId> = Collections.unmodifiableSet(
        LinkedHashSet(searchScopes.sortedBy(SearchScopeId::value)),
    )

    init {
        require(this.supportedOperations.isNotEmpty()) { "Record backend operations must not be empty." }
        require(this.supportedOperations.all { operation -> operation in QUERY_OPERATIONS }) {
            "Query backend contribution supports record operations and ANALYZE only."
        }
        require((QueryOperation.ANALYZE in this.supportedOperations) == (analyticsBackend != null)) {
            "ANALYZE requires exactly one analytics backend."
        }
        require(this.semanticTiers.isNotEmpty()) { "Record backend semantic tiers must not be empty." }
        require(QueryOperation.STREAM in this.supportedOperations || streamSupport == BackendStreamSupport.NONE) {
            "Stream support must be NONE when STREAM is not registered."
        }
        require(QueryOperation.STREAM !in this.supportedOperations || streamSupport != BackendStreamSupport.NONE) {
            "STREAM requires an explicit stream support level."
        }
        require(
            mappingGenerationDigest.length == SHA_256_HEX_LENGTH &&
                mappingGenerationDigest.all { character -> character in HEX_CHARACTERS },
        ) {
            "Mapping generation digest must be lowercase SHA-256 hex."
        }
        this.fieldCapabilities.forEach { (field, capabilities) ->
            val schemaField = requireNotNull(schema.fields[field]) {
                "Backend field capability $field is not declared by the logical schema."
            }
            require(schemaField.capabilities.containsAll(capabilities)) {
                "Backend field capability $field exceeds the logical schema contract."
            }
        }
        require(schema.searchScopes.keys.containsAll(this.searchScopes)) {
            "Backend search scopes must be declared by the logical schema."
        }
    }

    private fun immutableCapabilities(
        capabilities: Map<QueryFieldId, Set<FieldCapability>>,
    ): Map<QueryFieldId, Set<FieldCapability>> {
        val copy = LinkedHashMap<QueryFieldId, Set<FieldCapability>>(capabilities.size)
        capabilities.entries.sortedWith(compareBy(QUERY_FIELD_ID_COMPARATOR) { entry -> entry.key }).forEach { entry ->
            copy[entry.key] = Collections.unmodifiableSet(
                LinkedHashSet(entry.value.sortedBy(FieldCapability::name)),
            )
        }
        return Collections.unmodifiableMap(copy)
    }

    private companion object {
        const val SHA_256_HEX_LENGTH = 64
        const val HEX_CHARACTERS = "0123456789abcdef"
        val QUERY_OPERATIONS = setOf(
            QueryOperation.SINGLE,
            QueryOperation.STREAM,
            QueryOperation.PAGE,
            QueryOperation.COUNT,
            QueryOperation.ANALYZE,
        )
    }
}

/** A configured Backend route whose logical schema is known but whose static readiness has not been attested. */
@ExperimentalQueryBackendApi
data class RecordQueryBackendNotReady(
    val schema: QueryDocumentSchema,
    val backendId: BackendId,
)

@ExperimentalQueryBackendApi
class QueryBackendComposition(
    contributions: Iterable<RecordQueryBackendContribution>,
    notReadyBackends: Iterable<RecordQueryBackendNotReady>,
    defaultRoutes: Map<QueryTarget, BackendId>,
) {
    val contributions: List<RecordQueryBackendContribution>
    val notReadyBackends: List<RecordQueryBackendNotReady>
    val defaultRoutes: Map<QueryTarget, BackendId>

    init {
        val contributionList = contributions.toList()
        val notReadyList = notReadyBackends.toList()
        require(
            contributionList.map { contribution -> contribution.schema.target to contribution.backendId }
                .distinct().size == contributionList.size,
        ) {
            "Query backend contribution keys must be unique."
        }
        require(
            notReadyList.map { backend -> backend.schema.target to backend.backendId }.distinct().size ==
                notReadyList.size,
        ) {
            "Not-ready Query backend keys must be unique."
        }
        require(
            contributionList.map { contribution -> contribution.schema.target to contribution.backendId }.toSet()
                .intersect(notReadyList.map { backend -> backend.schema.target to backend.backendId }.toSet())
                .isEmpty(),
        ) {
            "A Query backend key cannot be ready and not-ready at the same time."
        }
        this.contributions = Collections.unmodifiableList(
            contributionList.sortedWith(
                compareBy<RecordQueryBackendContribution> { it.schema.target.namedAggregate.contextName }
                    .thenBy { it.schema.target.namedAggregate.aggregateName }
                    .thenBy { it.schema.target.documentKind.name }
                    .thenBy { it.backendId.value },
            ),
        )
        this.notReadyBackends = Collections.unmodifiableList(
            notReadyList.sortedWith(
                compareBy<RecordQueryBackendNotReady> { it.schema.target.namedAggregate.contextName }
                    .thenBy { it.schema.target.namedAggregate.aggregateName }
                    .thenBy { it.schema.target.documentKind.name }
                    .thenBy { it.backendId.value },
            ),
        )
        val routes = LinkedHashMap<QueryTarget, BackendId>(defaultRoutes.size)
        defaultRoutes.entries.sortedWith(
            compareBy<Map.Entry<QueryTarget, BackendId>> { it.key.namedAggregate.contextName }
                .thenBy { it.key.namedAggregate.aggregateName }
                .thenBy { it.key.documentKind.name },
        ).forEach { entry -> routes[entry.key] = entry.value }
        this.defaultRoutes = Collections.unmodifiableMap(routes)
        this.defaultRoutes.forEach { (target, backendId) ->
            require(
                this.contributions.any { it.schema.target == target && it.backendId == backendId } ||
                    this.notReadyBackends.any { it.schema.target == target && it.backendId == backendId },
            ) {
                "Default query backend route $target/$backendId is not registered."
            }
        }
    }

    constructor(
        contributions: Iterable<RecordQueryBackendContribution>,
        defaultRoutes: Map<QueryTarget, BackendId>,
    ) : this(contributions, emptyList(), defaultRoutes)

    companion object {
        val EMPTY = QueryBackendComposition(emptyList(), emptyList(), emptyMap())
    }
}
