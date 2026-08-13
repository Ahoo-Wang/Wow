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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

class MongoQueryBackendTest {
    @Test
    fun `bind is synchronous no-io and descriptor is truthful`() {
        val database = mockk<MongoDatabase>()
        every { database.getCollection(any()) } throws AssertionError("bind performed Mongo I/O")
        val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
        val context = QueryBackendResolutionContext(
            target,
            PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
            PortableQueryDataset.vectors.first().expression
        )
        val factory = MongoQueryBackendFactory(
            database,
            MongoNativeQueryTemplateRegistry(),
            QueryBudgetLimit(maxResults = 1_000)
        )

        val backend = factory.bind(context)

        backend.descriptor.backendId.assert().isEqualTo("mongo")
        backend.descriptor.documentKinds.assert().isEqualTo(QueryDocumentKind.entries.toSet())
        backend.descriptor.planVersions.assert().isEqualTo(setOf(QueryPlanVersion.V1))
        backend.descriptor.portableOperators.assert()
            .isEqualTo(me.ahoo.wow.api.query.expression.PortableOperator.entries.toSet())
        backend.descriptor.portableFeatures.assert().isEqualTo(setOf(QueryPortableFeature.ELEMENT_MATCH))
        backend.descriptor.stringComparisonModes.assert()
            .isEqualTo(me.ahoo.wow.api.query.expression.StringComparisonMode.entries.toSet())
        backend.descriptor.capabilities.assert().isEqualTo(
            setOf(
                QueryCapabilityId("full-text"),
                QueryCapabilityId("x-wow:mongo-native")
            )
        )
        backend.descriptor.maxBudget.assert().isEqualTo(QueryBudgetLimit(maxResults = 1_000))
        verify(exactly = 0) { database.getCollection(any()) }
    }

    @Test
    fun `readiness reports missing collection instead of an empty backend`() {
        val database = mockk<MongoDatabase>()
        every { database.codecRegistry } returns MongoClientSettings.getDefaultCodecRegistry()
        val collectionNames = mockk<com.mongodb.reactivestreams.client.ListCollectionNamesPublisher>()
        every { database.listCollectionNames() } returns collectionNames
        every { collectionNames.subscribe(any()) } answers {
            Flux.empty<String>().subscribe(firstArg<org.reactivestreams.Subscriber<in String>>())
        }
        val backend = MongoQueryBackendFactory(database).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.vectors.first().expression
            )
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.INDEX_MISSING
                )
            )
            .verifyComplete()
    }

    @Test
    fun `readiness rejects an incomplete system binding before collection io`() {
        val database = mockk<MongoDatabase>()
        every { database.codecRegistry } returns MongoClientSettings.getDefaultCodecRegistry()
        every { database.listCollectionNames() } throws AssertionError("readiness performed collection I/O")
        val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
        val backend = MongoQueryBackendFactory(database).bind(
            QueryBackendResolutionContext(target, QuerySchema(target, emptyList()), MatchAll)
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID
                )
            )
            .verifyComplete()
        verify(exactly = 0) { database.listCollectionNames() }
    }

    @Test
    fun `readiness rejects a missing document codec before collection io`() {
        val database = mockk<MongoDatabase>()
        every { database.codecRegistry } throws IllegalStateException("Document codec is unavailable")
        every { database.listCollectionNames() } throws AssertionError("readiness performed collection I/O")
        val backend = MongoQueryBackendFactory(database).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                MatchAll
            )
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID
                )
            )
            .verifyComplete()
        verify(exactly = 0) { database.listCollectionNames() }
    }

    @Test
    fun `readiness rejects non-authoritative system field bindings before collection io`() {
        QueryDocumentKind.entries.forEach { documentKind ->
            val database = mockk<MongoDatabase>()
            every { database.codecRegistry } returns MongoClientSettings.getDefaultCodecRegistry()
            every { database.listCollectionNames() } throws AssertionError("readiness performed collection I/O")
            val target = PortableQueryDataset.target(documentKind)
            val identity = when (documentKind) {
                QueryDocumentKind.SNAPSHOT -> me.ahoo.wow.api.query.expression.LogicalField("aggregateId")
                QueryDocumentKind.EVENT_STREAM -> me.ahoo.wow.api.query.expression.LogicalField("id")
            }
            listOf(QueryFieldUsage.EXACT, QueryFieldUsage.SORT).forEach { maliciousUsage ->
                val fields = QuerySystemFields.fields(documentKind).map { field ->
                    if (field.path == identity) {
                        field.copy(
                            bindings = setOf(
                                QueryCapabilityBinding(
                                    QueryBackendId("mongo"),
                                    maliciousUsage,
                                    QueryBackendFieldPath("wrong.path")
                                )
                            )
                        )
                    } else {
                        field
                    }
                }
                val backend = MongoQueryBackendFactory(database).bind(
                    QueryBackendResolutionContext(target, QuerySchema(target, fields), MatchAll)
                )

                StepVerifier.create(backend.readiness())
                    .expectNext(
                        QueryBackendReadiness.NotReady(
                            me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID
                        )
                    )
                    .verifyComplete()
            }
            val nonSystemFields = QuerySystemFields.fields(documentKind).map { field ->
                if (field.path == identity) field.copy(system = false) else field
            }
            val nonSystemBackend = MongoQueryBackendFactory(database).bind(
                QueryBackendResolutionContext(target, QuerySchema(target, nonSystemFields), MatchAll)
            )
            StepVerifier.create(nonSystemBackend.readiness())
                .expectNext(
                    QueryBackendReadiness.NotReady(
                        me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID
                    )
                ).verifyComplete()
            verify(exactly = 0) { database.listCollectionNames() }
        }
    }
}
