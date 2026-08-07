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

package me.ahoo.wow.query.internal.execution

import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.QUERY_FIELD_ID_COMPARATOR
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.SchemaContractId
import java.util.Collections
import java.util.LinkedHashMap

internal data class QueryBackendKey(
    val target: QueryTarget,
    val backendId: BackendId,
)

internal class QueryBackendDescriptor(
    val key: QueryBackendKey,
    val schemaContractId: SchemaContractId,
    supportedOperations: Set<QueryOperation>,
    semanticTiers: Set<SemanticTier>,
    fieldCapabilities: Map<QueryFieldId, Set<FieldCapability>>,
    searchScopes: Set<SearchScopeId> = emptySet(),
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
        require(this.supportedOperations.isNotEmpty()) {
            "Query backend must support at least one operation."
        }
        require(this.semanticTiers.isNotEmpty()) {
            "Query backend must support at least one semantic tier."
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
}

internal data class QueryBackendRegistration(
    val descriptor: QueryBackendDescriptor,
    val recordBackend: RecordQueryBackend? = null,
    val analyticsBackend: AnalyticsQueryBackend? = null,
) {
    init {
        val recordOperations = descriptor.supportedOperations - QueryOperation.ANALYZE
        require(recordOperations.isEmpty() || recordBackend != null) {
            "Record operations require a record query backend."
        }
        require(QueryOperation.ANALYZE !in descriptor.supportedOperations || analyticsBackend != null) {
            "ANALYZE requires an analytics query backend."
        }
    }
}

internal class QueryBackendRegistry(
    registrations: Iterable<QueryBackendRegistration>,
    defaultRoutes: Map<QueryTarget, BackendId>,
) {
    val registrations: Map<QueryBackendKey, QueryBackendRegistration>
    val defaultRoutes: Map<QueryTarget, BackendId>

    init {
        val registrationList = registrations.toList()
        require(
            registrationList.map { registration -> registration.descriptor.key }.distinct().size ==
                registrationList.size,
        ) {
            "Query backend registration keys must be unique."
        }
        val registrationCopy = LinkedHashMap<QueryBackendKey, QueryBackendRegistration>(registrationList.size)
        registrationList.sortedWith(QUERY_BACKEND_REGISTRATION_COMPARATOR).forEach { registration ->
            registrationCopy[registration.descriptor.key] = registration
        }
        this.registrations = Collections.unmodifiableMap(registrationCopy)
        val routeCopy = LinkedHashMap<QueryTarget, BackendId>(defaultRoutes.size)
        defaultRoutes.entries.sortedWith(QUERY_BACKEND_ROUTE_COMPARATOR).forEach { entry ->
            routeCopy[entry.key] = entry.value
        }
        this.defaultRoutes = Collections.unmodifiableMap(routeCopy)
    }

    fun resolve(plan: QueryPlan): QueryBackendRegistration {
        val backendId = plan.requiredCapabilities.nativeBackend ?: defaultRoutes[plan.target]
            ?: rejectBackend(QueryRejectionCode.BACKEND_NOT_REGISTERED)
        val registration = registrations[QueryBackendKey(plan.target, backendId)]
            ?: rejectBackend(QueryRejectionCode.BACKEND_NOT_REGISTERED)
        validate(plan, registration.descriptor)
        return registration
    }

    private fun validate(plan: QueryPlan, descriptor: QueryBackendDescriptor) {
        if (plan.schemaContractId != descriptor.schemaContractId) {
            rejectBackend(QueryRejectionCode.BACKEND_SCHEMA_MISMATCH)
        }
        if (plan.operation !in descriptor.supportedOperations) {
            rejectBackend(QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED)
        }
        if (plan.semanticTier !in descriptor.semanticTiers) {
            rejectBackend(QueryRejectionCode.BACKEND_CAPABILITY_MISMATCH)
        }
        val missingFieldCapability = plan.requiredCapabilities.fieldRequirements.any { (field, required) ->
            !descriptor.fieldCapabilities[field].orEmpty().containsAll(required)
        }
        if (missingFieldCapability || !descriptor.searchScopes.containsAll(plan.requiredCapabilities.searchRequirements)) {
            rejectBackend(QueryRejectionCode.BACKEND_CAPABILITY_MISMATCH)
        }
        val nativeBackend = plan.requiredCapabilities.nativeBackend
        if (nativeBackend != null && nativeBackend != descriptor.key.backendId) {
            rejectBackend(QueryRejectionCode.BACKEND_CAPABILITY_MISMATCH)
        }
    }

    private fun rejectBackend(code: QueryRejectionCode): Nothing = rejectQuery(
        QueryRejectionCategory.BACKEND_UNAVAILABLE,
        BACKEND_PATH,
        code,
    )
}

private val QUERY_BACKEND_REGISTRATION_COMPARATOR: Comparator<QueryBackendRegistration> =
    compareBy<QueryBackendRegistration> { registration -> registration.descriptor.key.target.namedAggregate.contextName }
        .thenBy { registration -> registration.descriptor.key.target.namedAggregate.aggregateName }
        .thenBy { registration -> registration.descriptor.key.target.documentKind.name }
        .thenBy { registration -> registration.descriptor.key.backendId.value }

private val QUERY_BACKEND_ROUTE_COMPARATOR: Comparator<Map.Entry<QueryTarget, BackendId>> =
    compareBy<Map.Entry<QueryTarget, BackendId>> { entry -> entry.key.namedAggregate.contextName }
        .thenBy { entry -> entry.key.namedAggregate.aggregateName }
        .thenBy { entry -> entry.key.documentKind.name }

private val BACKEND_PATH = QueryRejectionPath.ROOT.property("backend")
