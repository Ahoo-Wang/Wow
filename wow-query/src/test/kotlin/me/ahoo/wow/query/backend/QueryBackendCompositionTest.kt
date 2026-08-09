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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@OptIn(
    ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)
class QueryBackendCompositionTest {
    @Test
    fun `composition should defensively copy every registration boundary`() {
        val capabilities = linkedSetOf(FieldCapability.EXACT)
        val fieldCapabilities = linkedMapOf<QueryFieldId, Set<FieldCapability>>(identity to capabilities)
        val operations = linkedSetOf(QueryOperation.SINGLE)
        val tiers = linkedSetOf(SemanticTier.PORTABLE)
        val contribution = RecordQueryBackendContribution(
            schema,
            backendId,
            operations,
            BackendStreamSupport.NONE,
            tiers,
            fieldCapabilities,
            backend = NO_OP_BACKEND,
        )
        val contributions = mutableListOf(contribution)
        val routes = linkedMapOf(target to backendId)
        val composition = QueryBackendComposition(contributions, routes)

        capabilities += FieldCapability.SORTABLE
        fieldCapabilities.clear()
        operations += QueryOperation.COUNT
        tiers.clear()
        contributions.clear()
        routes.clear()

        composition.contributions.assert().hasSize(1)
        composition.defaultRoutes.assert().containsEntry(target, backendId)
        contribution.supportedOperations.assert().containsExactly(QueryOperation.SINGLE)
        contribution.semanticTiers.assert().containsExactly(SemanticTier.PORTABLE)
        contribution.fieldCapabilities[identity]!!.assert().containsExactly(FieldCapability.EXACT)

        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (composition.contributions as MutableList<RecordQueryBackendContribution>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (composition.defaultRoutes as MutableMap<QueryTarget, BackendId>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (contribution.fieldCapabilities[identity] as MutableSet<FieldCapability>).clear()
        }
    }

    @Test
    fun `composition should reject duplicate and dangling routes`() {
        val contribution = contribution()

        assertThrownBy<IllegalArgumentException> {
            QueryBackendComposition(listOf(contribution, contribution), mapOf(target to backendId))
        }
        assertThrownBy<IllegalArgumentException> {
            QueryBackendComposition(listOf(contribution), mapOf(target to BackendId("missing")))
        }
        assertThrownBy<IllegalArgumentException> {
            QueryBackendComposition(
                listOf(contribution),
                listOf(RecordQueryBackendNotReady(schema, backendId)),
                mapOf(target to backendId),
            )
        }
    }

    @Test
    fun `composition should retain a configured but not ready route without claiming a backend capability`() {
        val notReady = mutableListOf(RecordQueryBackendNotReady(schema, backendId))
        val routes = linkedMapOf(target to backendId)

        val composition = QueryBackendComposition(emptyList(), notReady, routes)
        notReady.clear()
        routes.clear()

        composition.contributions.assert().isEmpty()
        composition.notReadyBackends.assert().hasSize(1)
        composition.notReadyBackends.single().schema.assert().isSameAs(schema)
        composition.defaultRoutes.assert().containsEntry(target, backendId)
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (composition.notReadyBackends as MutableList<RecordQueryBackendNotReady>).clear()
        }
    }

    @Test
    fun `contribution should reject capability overclaim and ambiguous stream support`() {
        assertThrownBy<IllegalArgumentException> {
            contribution(fieldCapabilities = mapOf(identity to setOf(FieldCapability.SORTABLE)))
        }
        assertThrownBy<IllegalArgumentException> {
            contribution(
                operations = setOf(QueryOperation.SINGLE),
                streamSupport = BackendStreamSupport.BOUNDED_ONLY,
            )
        }
        assertThrownBy<IllegalArgumentException> {
            contribution(
                operations = setOf(QueryOperation.SINGLE, QueryOperation.STREAM),
                streamSupport = BackendStreamSupport.NONE,
            )
        }
        contribution(operations = setOf(QueryOperation.PAGE)).supportedOperations.assert()
            .containsExactly(QueryOperation.PAGE)
    }

    private fun contribution(
        operations: Set<QueryOperation> = setOf(QueryOperation.SINGLE),
        streamSupport: BackendStreamSupport = BackendStreamSupport.NONE,
        fieldCapabilities: Map<QueryFieldId, Set<FieldCapability>> = mapOf(
            identity to setOf(FieldCapability.EXACT),
        ),
    ): RecordQueryBackendContribution = RecordQueryBackendContribution(
        schema,
        backendId,
        operations,
        streamSupport,
        setOf(SemanticTier.PORTABLE),
        fieldCapabilities,
        backend = NO_OP_BACKEND,
    )

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val backendId = BackendId("test")
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                identity,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                listOf(PredicateOperator.EQ),
                listOf(FieldCapability.EXACT),
            ),
        ),
        emptyList(),
    )

    private companion object {
        val NO_OP_BACKEND = object : RecordQueryBackend {
            override fun single(
                plan: BackendSingleQueryPlan,
                options: QueryBackendExecutionOptions,
            ): Mono<BackendRecord> = Mono.empty()

            override fun stream(
                plan: BackendStreamQueryPlan,
                options: QueryBackendExecutionOptions,
            ): Flux<BackendRecord> = Flux.empty()

            override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long> =
                Mono.just(0)
        }
    }
}
