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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.BackendStreamSupport
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class StorageRoutedQueryBackendCompositionTest {
    @Test
    fun `only the source selected by the aggregate storage route should be prepared`() {
        val mongoCalls = AtomicInteger()
        val elasticsearchCalls = AtomicInteger()
        val mongo = source(StorageType.MONGO, target, mongoCalls)
        val elasticsearch = source(StorageType.ELASTICSEARCH, target, elasticsearchCalls)

        val composition = StorageRoutedQueryBackendComposition.create(listOf(mongo, elasticsearch)) {
            StorageType.MONGO
        }

        composition.contributions.assert().hasSize(1)
        composition.defaultRoutes.getValue(target).assert().isEqualTo(BackendId("mongo"))
        mongoCalls.get().assert().isEqualTo(1)
        elasticsearchCalls.get().assert().isZero()
    }

    @Test
    fun `duplicate target and storage source should fail before preparation`() {
        val calls = AtomicInteger()

        assertThrownBy<IllegalArgumentException> {
            StorageRoutedQueryBackendComposition.create(
                listOf(source(StorageType.MONGO, target, calls), source(StorageType.MONGO, target, calls)),
            ) { StorageType.MONGO }
        }
        calls.get().assert().isZero()
    }

    @Test
    fun `legacy-only target should not inspect planned backend readiness`() {
        val calls = AtomicInteger()

        val composition = StorageRoutedQueryBackendComposition.create(
            listOf(source(StorageType.MONGO, target, calls)),
            storageResolver = { StorageType.MONGO },
            shouldPrepare = { false },
        )

        composition.contributions.assert().isEmpty()
        composition.notReadyBackends.assert().isEmpty()
        composition.defaultRoutes.assert().isEmpty()
        calls.get().assert().isZero()
    }

    @Test
    fun `configured but not ready source should preserve its exact route without a ready contribution`() {
        val source = object : StorageQueryBackendSource {
            override val storage: StorageType = StorageType.MONGO
            override val targets: Set<QueryTarget> = setOf(target)

            override fun prepare(target: QueryTarget): Mono<StorageQueryBackendPreparation> = Mono.just(
                StorageQueryBackendPreparation.NotReady(schema(target), BackendId("mongo")),
            )
        }

        val composition = StorageRoutedQueryBackendComposition.create(listOf(source)) { StorageType.MONGO }

        composition.contributions.assert().isEmpty()
        composition.notReadyBackends.assert().hasSize(1)
        composition.defaultRoutes.assert().containsEntry(target, BackendId("mongo"))
    }

    private fun source(
        storage: StorageType,
        target: QueryTarget,
        calls: AtomicInteger,
    ): StorageQueryBackendSource = object : StorageQueryBackendSource {
        override val storage: StorageType = storage
        override val targets: Set<QueryTarget> = setOf(target)

        override fun prepare(target: QueryTarget): Mono<StorageQueryBackendPreparation> = Mono.fromSupplier {
            calls.incrementAndGet()
            StorageQueryBackendPreparation.Ready(contribution(target, BackendId(storage.name.lowercase())))
        }
    }

    private fun contribution(target: QueryTarget, backendId: BackendId): RecordQueryBackendContribution {
        val schema = schema(target)
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        return RecordQueryBackendContribution(
            schema,
            backendId,
            setOf(QueryOperation.COUNT),
            BackendStreamSupport.NONE,
            setOf(SemanticTier.PORTABLE),
            mapOf(identity to setOf(FieldCapability.EXACT)),
            backend = object : RecordQueryBackend {
                override fun single(
                    plan: me.ahoo.wow.query.backend.BackendSingleQueryPlan,
                    options: QueryBackendExecutionOptions,
                ) = Mono.empty<me.ahoo.wow.query.backend.BackendRecord>()

                override fun stream(
                    plan: me.ahoo.wow.query.backend.BackendStreamQueryPlan,
                    options: QueryBackendExecutionOptions,
                ) = Flux.empty<me.ahoo.wow.query.backend.BackendRecord>()

                override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions) = Mono.just(0L)
            },
        )
    }

    private fun schema(target: QueryTarget): QueryDocumentSchema {
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        return QueryDocumentSchema(
            target,
            listOf(
                QueryFieldSchema(
                    identity,
                    LogicalFieldType.Text,
                    Presence.REQUIRED,
                    Nullability.NON_NULL,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT),
                ),
            ),
            emptyList(),
        )
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
}
