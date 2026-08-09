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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import io.mockk.confirmVerified
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.elasticsearch.query.planned.ElasticsearchFieldBinding
import me.ahoo.wow.elasticsearch.query.planned.ElasticsearchSnapshotQueryBinding
import me.ahoo.wow.modeling.MaterializedNamedAggregate
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
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.query.StorageRoutedQueryBackendComposition
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchPlannedQueryBackendSourceTest {
    @Test
    fun `binding routed elsewhere should not inspect Elasticsearch`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val source = ElasticsearchPlannedQueryBackendSource(client, listOf(binding))

        val composition = StorageRoutedQueryBackendComposition.create(listOf(source)) { StorageType.MONGO }

        composition.contributions.assert().isEmpty()
        composition.defaultRoutes.assert().isEmpty()
        confirmVerified(client)
    }

    @Test
    fun `bindings should be unique for each target`() {
        val client = mockk<ReactiveElasticsearchClient>()

        assertThrownBy<IllegalArgumentException> {
            ElasticsearchPlannedQueryBackendSource(client, listOf(binding, binding))
        }
        confirmVerified(client)
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
    private val binding = ElasticsearchSnapshotQueryBinding(
        schema,
        "wow.sales.order.snapshot",
        "order-query-v1",
        mapOf(
            identity to ElasticsearchFieldBinding(
                MessageRecords.AGGREGATE_ID,
                setOf(FieldCapability.EXACT),
                exactField = "_id",
            ),
        ),
    )
}
