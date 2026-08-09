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
@JvmInline
value class PlanFingerprint(val value: String) {
    init {
        require(value.matches(HEX_PATTERN)) { "Plan fingerprint must be a SHA-256 hex string." }
    }

    private companion object {
        val HEX_PATTERN = Regex("[0-9a-f]{64}")
    }
}

@ExperimentalQueryBackendApi
enum class SemanticTier {
    PORTABLE,
    SEARCH,
    NATIVE,
}

@ExperimentalQueryBackendApi
sealed interface BackendPlannedCondition {
    data object All : BackendPlannedCondition

    data object None : BackendPlannedCondition

    class Junction(
        val operator: JunctionOperator,
        children: Iterable<BackendPlannedCondition>,
    ) : BackendPlannedCondition {
        val children: List<BackendPlannedCondition> = Collections.unmodifiableList(children.toList())

        init {
            require(this.children.isNotEmpty()) { "Backend junction children must not be empty." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Junction && operator == other.operator && children == other.children

        override fun hashCode(): Int = 31 * operator.hashCode() + children.hashCode()
    }

    data class Predicate(
        val field: QueryFieldId,
        val operator: PredicateOperator,
        val value: NormalizedValue? = null,
        val options: NormalizedPredicateOptions = NormalizedPredicateOptions(),
    ) : BackendPlannedCondition {
        init {
            require(operator.requiresValue == (value != null)) { "Predicate value does not match operator $operator." }
        }
    }

    data class ElementMatch(
        val field: QueryFieldId.Path,
        val condition: BackendPlannedCondition,
    ) : BackendPlannedCondition

    data class Search(val scope: SearchScopeId, val text: String) : BackendPlannedCondition {
        init {
            require(text.isNotBlank()) { "Search text must not be blank." }
        }
    }

    data class Native(val backendId: BackendId, val payload: Utf8Json) : BackendPlannedCondition
}

@ExperimentalQueryBackendApi
data class BackendEnforcedFilter(
    val user: BackendPlannedCondition,
    val mandatory: BackendPlannedCondition,
) {
    val condition: BackendPlannedCondition = BackendPlannedCondition.Junction(
        JunctionOperator.AND,
        listOf(user, mandatory),
    )
}

@ExperimentalQueryBackendApi
class BackendRequiredCapabilities(
    fieldRequirements: Map<QueryFieldId, Set<FieldCapability>> = emptyMap(),
    searchRequirements: Set<SearchScopeId> = emptySet(),
    val nativeBackend: BackendId? = null,
) {
    val fieldRequirements: Map<QueryFieldId, Set<FieldCapability>> = immutableRequirements(fieldRequirements)
    val searchRequirements: Set<SearchScopeId> = Collections.unmodifiableSet(
        LinkedHashSet(searchRequirements.sortedBy(SearchScopeId::value)),
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BackendRequiredCapabilities &&
            fieldRequirements == other.fieldRequirements &&
            searchRequirements == other.searchRequirements &&
            nativeBackend == other.nativeBackend

    override fun hashCode(): Int =
        31 * (31 * fieldRequirements.hashCode() + searchRequirements.hashCode()) + (nativeBackend?.hashCode() ?: 0)

    private fun immutableRequirements(
        requirements: Map<QueryFieldId, Set<FieldCapability>>,
    ): Map<QueryFieldId, Set<FieldCapability>> {
        val copy = LinkedHashMap<QueryFieldId, Set<FieldCapability>>(requirements.size)
        requirements.entries.sortedBy { entry -> entry.key.stableKey() }.forEach { (field, capabilities) ->
            copy[field] = Collections.unmodifiableSet(LinkedHashSet(capabilities.sortedBy(FieldCapability::name)))
        }
        return Collections.unmodifiableMap(copy)
    }
}

@ExperimentalQueryBackendApi
sealed interface BackendProjection {
    data object All : BackendProjection

    class Include(fields: Iterable<QueryFieldId>) : BackendProjection {
        val fields: List<QueryFieldId> = immutableNonEmptyFields(fields)

        override fun equals(other: Any?): Boolean = this === other || other is Include && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()
    }

    class Exclude(fields: Iterable<QueryFieldId>) : BackendProjection {
        val fields: List<QueryFieldId> = immutableNonEmptyFields(fields)

        override fun equals(other: Any?): Boolean = this === other || other is Exclude && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()
    }
}

@ExperimentalQueryBackendApi
enum class BackendSortOrigin {
    USER,
    STABILITY_TIE_BREAKER,
}

@ExperimentalQueryBackendApi
data class BackendSort(
    val field: QueryFieldId,
    val direction: NormalizedSortDirection,
    val origin: BackendSortOrigin,
)

@ExperimentalQueryBackendApi
sealed interface BackendRecordQueryPlan {
    val target: QueryTarget
    val operation: QueryOperation
    val schemaContractId: SchemaContractId
    val filter: BackendEnforcedFilter
    val requiredCapabilities: BackendRequiredCapabilities
    val semanticTier: SemanticTier
    val fingerprint: PlanFingerprint
}

@ExperimentalQueryBackendApi
sealed interface BackendRecordResultPlan : BackendRecordQueryPlan {
    val resultShape: RecordResultShape
    val projection: BackendProjection
    val sort: List<BackendSort>
}

@ExperimentalQueryBackendApi
class BackendSingleQueryPlan(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: BackendEnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: BackendProjection,
    sort: Iterable<BackendSort>,
    override val requiredCapabilities: BackendRequiredCapabilities,
    override val semanticTier: SemanticTier,
    override val fingerprint: PlanFingerprint,
) : BackendRecordResultPlan {
    override val operation: QueryOperation = QueryOperation.SINGLE
    override val sort: List<BackendSort> = Collections.unmodifiableList(sort.toList())
}

@ExperimentalQueryBackendApi
class BackendStreamQueryPlan(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: BackendEnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: BackendProjection,
    sort: Iterable<BackendSort>,
    val limit: Int,
    override val requiredCapabilities: BackendRequiredCapabilities,
    override val semanticTier: SemanticTier,
    override val fingerprint: PlanFingerprint,
) : BackendRecordResultPlan {
    override val operation: QueryOperation = QueryOperation.STREAM
    override val sort: List<BackendSort> = Collections.unmodifiableList(sort.toList())

    init {
        require(limit > 0) { "Backend stream limit must be positive." }
    }
}

@ExperimentalQueryBackendApi
data class BackendPageWindow(
    val offset: Long,
    val size: Int,
) {
    init {
        require(offset >= 0) { "Backend page offset must not be negative." }
        require(size > 0) { "Backend page size must be positive." }
    }
}

@ExperimentalQueryBackendApi
enum class BackendTotalMode {
    EXACT,
}

@ExperimentalQueryBackendApi
enum class BackendRequiredConsistency {
    SAME_INPUT,
}

@ExperimentalQueryBackendApi
class BackendPageQueryPlan(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: BackendEnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: BackendProjection,
    sort: Iterable<BackendSort>,
    val page: BackendPageWindow,
    val totalMode: BackendTotalMode,
    val requiredConsistency: BackendRequiredConsistency,
    override val requiredCapabilities: BackendRequiredCapabilities,
    override val semanticTier: SemanticTier,
    override val fingerprint: PlanFingerprint,
) : BackendRecordResultPlan {
    override val operation: QueryOperation = QueryOperation.PAGE
    override val sort: List<BackendSort> = Collections.unmodifiableList(sort.toList())
}

@ExperimentalQueryBackendApi
class BackendCountQueryPlan(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: BackendEnforcedFilter,
    override val requiredCapabilities: BackendRequiredCapabilities,
    override val semanticTier: SemanticTier,
    override val fingerprint: PlanFingerprint,
) : BackendRecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.COUNT
}

private fun immutableNonEmptyFields(fields: Iterable<QueryFieldId>): List<QueryFieldId> =
    Collections.unmodifiableList(fields.toList()).also { copy ->
        require(copy.isNotEmpty()) { "Backend projection fields must not be empty." }
    }

private fun QueryFieldId.stableKey(): String =
    when (this) {
        is QueryFieldId.System -> "0:${kind.name}"
        is QueryFieldId.Path -> "1:${segments.joinToString("\u0000") { segment -> "${segment.length}:$segment" }}"
    }
