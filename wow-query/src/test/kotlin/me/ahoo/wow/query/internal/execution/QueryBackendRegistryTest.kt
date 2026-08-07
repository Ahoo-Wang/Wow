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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.RecordResultShape
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.plan.EnforcedFilter
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.PlannedProjection
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.schema.FieldCapability
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.function.Consumer

class QueryBackendRegistryTest {
    private val backendId = BackendId("mongo")
    private val backend = EmptyRecordBackend()

    @Test
    fun `registry should defensively copy routes and capabilities and resolve exact plan`() {
        val operations = mutableSetOf(QueryOperation.SINGLE)
        val semanticTiers = mutableSetOf(SemanticTier.PORTABLE)
        val innerCapabilities = mutableSetOf(FieldCapability.EXACT)
        val searchScopes = mutableSetOf(PlanningFixtures.searchScopeId)
        val fieldCapabilities = mutableMapOf<me.ahoo.wow.query.internal.schema.QueryFieldId, Set<FieldCapability>>(
            PlanningFixtures.name to innerCapabilities,
        )
        val routes = mutableMapOf(PlanningFixtures.target to backendId)
        val registration = registration(
            fieldCapabilities,
            operations = operations,
            semanticTiers = semanticTiers,
            searchScopes = searchScopes,
        )
        val registry = QueryBackendRegistry(listOf(registration), routes)

        operations.clear()
        semanticTiers.clear()
        innerCapabilities.clear()
        searchScopes.clear()
        fieldCapabilities.clear()
        routes.clear()

        registry.resolve(plan()).assert().isSameAs(registration)
        registration.descriptor.supportedOperations.assert().containsExactly(QueryOperation.SINGLE)
        registration.descriptor.semanticTiers.assert().containsExactly(SemanticTier.PORTABLE)
        registration.descriptor.searchScopes.assert().containsExactly(PlanningFixtures.searchScopeId)
        registration.descriptor.fieldCapabilities[PlanningFixtures.name].assert()
            .containsExactly(FieldCapability.EXACT)
        registry.defaultRoutes.assert().hasSize(1)
        registry.registrations.assert().hasSize(1)
        assertThrownBy<UnsupportedOperationException> {
            (registration.descriptor.supportedOperations as MutableSet).clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            (registration.descriptor.fieldCapabilities[PlanningFixtures.name] as MutableSet).clear()
        }
    }

    @Test
    fun `registry should reject duplicate missing schema operation and capability routes`() {
        val registration = registration(mapOf(PlanningFixtures.name to setOf(FieldCapability.EXACT)))
        assertThrownBy<IllegalArgumentException> {
            QueryBackendRegistry(listOf(registration, registration), mapOf(PlanningFixtures.target to backendId))
        }

        assertRejected(QueryRejectionCode.BACKEND_NOT_REGISTERED) {
            QueryBackendRegistry(emptyList(), emptyMap()).resolve(plan())
        }
        assertRejected(QueryRejectionCode.BACKEND_SCHEMA_MISMATCH) {
            val wrongSchema = registration(
                mapOf(PlanningFixtures.name to setOf(FieldCapability.EXACT)),
                schemaContractId = me.ahoo.wow.query.internal.schema.SchemaContractId("0".repeat(64)),
            )
            QueryBackendRegistry(listOf(wrongSchema), mapOf(PlanningFixtures.target to backendId)).resolve(plan())
        }
        assertRejected(QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED) {
            val countOnly = registration(
                mapOf(PlanningFixtures.name to setOf(FieldCapability.EXACT)),
                operations = setOf(QueryOperation.COUNT),
            )
            QueryBackendRegistry(listOf(countOnly), mapOf(PlanningFixtures.target to backendId)).resolve(plan())
        }
        assertRejected(QueryRejectionCode.BACKEND_CAPABILITY_MISMATCH) {
            val missingCapability = registration(emptyMap())
            QueryBackendRegistry(listOf(missingCapability), mapOf(PlanningFixtures.target to backendId)).resolve(plan())
        }
    }

    @Test
    fun `native requirement should pin the backend instead of using the default route`() {
        val elasticsearchId = BackendId("elasticsearch")
        val defaultRegistration = QueryBackendRegistration(
            QueryBackendDescriptor(
                QueryBackendKey(PlanningFixtures.target, elasticsearchId),
                PlanningFixtures.schema.contractId,
                setOf(QueryOperation.SINGLE),
                setOf(SemanticTier.PORTABLE),
                emptyMap(),
            ),
            recordBackend = backend,
        )
        val nativeRegistration = QueryBackendRegistration(
            QueryBackendDescriptor(
                QueryBackendKey(PlanningFixtures.target, backendId),
                PlanningFixtures.schema.contractId,
                setOf(QueryOperation.SINGLE),
                setOf(SemanticTier.NATIVE),
                emptyMap(),
            ),
            recordBackend = backend,
        )
        val nativePlan = SingleQueryPlan.create(
            PlanningFixtures.target,
            PlanningFixtures.schema.contractId,
            EnforcedFilter(
                PlannedCondition.Native(backendId, Utf8Json("{}")),
                PlannedCondition.All,
            ),
            RecordResultShape.DYNAMIC,
            PlannedProjection.All,
            emptyList(),
            RequiredCapabilities(nativeBackend = backendId),
            SemanticTier.NATIVE,
        )
        val registry = QueryBackendRegistry(
            listOf(defaultRegistration, nativeRegistration),
            mapOf(PlanningFixtures.target to elasticsearchId),
        )

        registry.resolve(nativePlan).assert().isSameAs(nativeRegistration)
    }

    private fun registration(
        capabilities: Map<me.ahoo.wow.query.internal.schema.QueryFieldId, Set<FieldCapability>>,
        schemaContractId: me.ahoo.wow.query.internal.schema.SchemaContractId = PlanningFixtures.schema.contractId,
        operations: Set<QueryOperation> = setOf(QueryOperation.SINGLE),
        semanticTiers: Set<SemanticTier> = setOf(SemanticTier.PORTABLE),
        searchScopes: Set<me.ahoo.wow.query.internal.normalization.SearchScopeId> = emptySet(),
    ): QueryBackendRegistration = QueryBackendRegistration(
        QueryBackendDescriptor(
            QueryBackendKey(PlanningFixtures.target, backendId),
            schemaContractId,
            operations,
            semanticTiers,
            capabilities,
            searchScopes,
        ),
        recordBackend = backend,
    )

    private fun plan(): SingleQueryPlan = SingleQueryPlan.create(
        PlanningFixtures.target,
        PlanningFixtures.schema.contractId,
        EnforcedFilter(PlannedCondition.All, PlannedCondition.All),
        RecordResultShape.DYNAMIC,
        PlannedProjection.All,
        emptyList(),
        RequiredCapabilities(mapOf(PlanningFixtures.name to setOf(FieldCapability.EXACT))),
        SemanticTier.PORTABLE,
    )

    private fun assertRejected(code: QueryRejectionCode, action: () -> Any?) {
        assertThrownBy<QueryRejectedException> { action() }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
                error.rejection.path.toString().assert().isEqualTo("$.backend")
                error.rejection.code.assert().isEqualTo(code)
            },
        )
    }

    private class EmptyRecordBackend : RecordQueryBackend {
        override fun single(
            plan: SingleQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<BackendRecord> = Mono.empty()

        override fun stream(
            plan: me.ahoo.wow.query.internal.plan.StreamQueryPlan,
            options: QueryExecutionOptions,
        ): Flux<BackendRecord> = Flux.empty()

        override fun page(
            plan: me.ahoo.wow.query.internal.plan.PageQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<BackendPage> = Mono.empty()

        override fun count(
            plan: me.ahoo.wow.query.internal.plan.CountQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<Long> = Mono.empty()
    }
}
