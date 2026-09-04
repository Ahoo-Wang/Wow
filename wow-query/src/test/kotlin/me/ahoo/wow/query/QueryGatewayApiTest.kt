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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationMode.COMPATIBLE
import me.ahoo.wow.query.schema.UnavailableQueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Modifier

class QueryGatewayApiTest {
    @Suppress("DEPRECATION")
    @Test
    fun `published gateway constructors should remain source compatible`() {
        val schemaProvider = UnavailableQueryModelSchemaProvider("test")
        val targetType = JsonSerializer.typeFactory.constructType(Any::class.java)
        val snapshotBackend = NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA)

        listOf(
            object : AbstractQueryGateway<Any>(
                MOCK_AGGREGATE_METADATA,
                snapshotBackend,
                schemaProvider,
                COMPATIBLE,
                targetType,
                emptyList(),
                QueryGateway::class,
                QueryLogErrorHandler(),
            ) {},
            DefaultSnapshotQueryGateway<Any>(
                MOCK_AGGREGATE_METADATA,
                snapshotBackend,
                schemaProvider,
                COMPATIBLE,
                targetType,
            ),
            DefaultEventStreamQueryGateway(
                MOCK_AGGREGATE_METADATA,
                NoOpEventStreamQueryBackend(MOCK_AGGREGATE_METADATA),
                schemaProvider,
                COMPATIBLE,
            ),
        ).assert().hasSize(3)
    }

    @Test
    fun `resolved query should retain query and schema identities`() {
        val query = SingleQuery(MatchAllFilter)
        val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())

        val resolved = ResolvedQuery(query, schema)

        resolved.query.assert().isSameAs(query)
        resolved.schema.assert().isSameAs(schema)
    }

    @Test
    fun `gateway should be aggregate bound without exposing handler contract`() {
        NamedAggregateDecorator::class.java.isAssignableFrom(QueryGateway::class.java).assert().isTrue()
        Handler::class.java.isAssignableFrom(QueryGateway::class.java).assert().isFalse()
        QueryGateway::class.java.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }
            .flatMap { it.parameterTypes.asIterable() }
            .none { it == NamedAggregate::class.java }
            .assert().isTrue()
    }

    @Test
    fun `gateway should expose the five object-node operations and typed variants`() {
        QueryGateway::class.java.getMethod(
            "single",
            ISingleQuery::class.java
        ).returnType.assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicSingle", ISingleQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod(
            "list",
            IListQuery::class.java
        ).returnType.assert().isEqualTo(Flux::class.java)
        QueryGateway::class.java.getMethod("dynamicList", IListQuery::class.java).returnType
            .assert().isEqualTo(Flux::class.java)
        QueryGateway::class.java.getMethod(
            "paged",
            IPagedQuery::class.java
        ).returnType.assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicPaged", IPagedQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("cursor", ICursorQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("dynamicCursor", ICursorQuery::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("count", FilterExpression::class.java).returnType
            .assert().isEqualTo(Mono::class.java)
        QueryGateway::class.java.getMethod("aggregate", AggregationQuery::class.java).returnType
            .assert().isEqualTo(Flux::class.java)
    }

    @Test
    fun `backend should accept only resolved queries`() {
        listOf("single", "list", "paged", "cursor", "count", "aggregate").forEach { method ->
            QueryBackend::class.java.getMethod(method, ResolvedQuery::class.java)
                .parameterTypes.assert().containsExactly(ResolvedQuery::class.java)
        }
    }

    @Test
    fun `cursor should be a required backend and gateway contract`() {
        listOf(
            QueryBackend::class.java.getMethod("cursor", ResolvedQuery::class.java),
            QueryGateway::class.java.getMethod("cursor", ICursorQuery::class.java),
            QueryGateway::class.java.getMethod("dynamicCursor", ICursorQuery::class.java),
        ).all { Modifier.isAbstract(it.modifiers) }.assert().isTrue()
    }
}
