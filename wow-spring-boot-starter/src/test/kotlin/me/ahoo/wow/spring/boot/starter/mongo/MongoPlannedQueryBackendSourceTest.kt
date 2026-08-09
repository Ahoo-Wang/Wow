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

package me.ahoo.wow.spring.boot.starter.mongo

import com.mongodb.MongoNamespace
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.planned.MongoEventStreamQueryBinding
import me.ahoo.wow.mongo.query.planned.MongoFieldBinding
import me.ahoo.wow.mongo.query.planned.MongoSnapshotQueryBinding
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.query.StorageRoutedQueryBackendComposition
import org.junit.jupiter.api.Test

class MongoPlannedQueryBackendSourceTest {
    @Test
    fun `binding for a target routed elsewhere should not inspect the Mongo database`() {
        val database = mockk<MongoDatabase>()
        val source = MongoPlannedQueryBackendSource.snapshot(database, listOf(binding))

        val composition = StorageRoutedQueryBackendComposition.create(listOf(source)) { StorageType.ELASTICSEARCH }

        composition.contributions.assert().isEmpty()
        composition.defaultRoutes.assert().isEmpty()
        verify(exactly = 0) { database.name }
    }

    @Test
    fun `selected binding should require the exact Mongo database`() {
        val database = mockk<MongoDatabase>()
        every { database.name } returns "other"
        val source = MongoPlannedQueryBackendSource.snapshot(database, listOf(binding))

        assertThrownBy<IllegalArgumentException> {
            StorageRoutedQueryBackendComposition.create(listOf(source)) { StorageType.MONGO }
        }
    }

    @Test
    fun `event stream bindings should retain an independent target and database owner`() {
        val database = mockk<MongoDatabase>()
        val source = MongoPlannedQueryBackendSource.eventStream(database, listOf(eventBinding))

        source.targets.assert().containsExactly(eventTarget)
        verify(exactly = 0) { database.name }
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val schema = QueryDocumentSchema(
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
    private val binding = MongoSnapshotQueryBinding(
        schema,
        MongoNamespace("sales", "order_snapshot"),
        mapOf(identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT))),
    )
    private val eventTarget = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.EVENT_STREAM,
    )
    private val eventSchema = QueryDocumentSchema(
        eventTarget,
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
    private val eventBinding = MongoEventStreamQueryBinding(
        eventSchema,
        MongoNamespace("events", "order_event_stream"),
        mapOf(identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT))),
    )
}
