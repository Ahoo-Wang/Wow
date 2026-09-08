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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackendFactory
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import reactor.core.publisher.Mono

internal object RouteTestFixtures {
    val MOCK_AGGREGATE_ROUTE_METADATA =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
    val SNAPSHOT_QUERY_SCHEMA = routeSchema(QueryModel.SNAPSHOT)
    val SNAPSHOT_QUERY_SCHEMA_PROVIDER = SNAPSHOT_QUERY_SCHEMA.asProvider()
    val EVENT_STREAM_QUERY_SCHEMA_PROVIDER =
        routeSchema(QueryModel.EVENT_STREAM).asProvider()

    val snapshotQueryGateway = DefaultSnapshotQueryGateway<Any>(
        namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
        binding = QueryBackendBinding(
            NoOpSnapshotQueryBackendFactory.create(MOCK_AGGREGATE_METADATA.namedAggregate).backend,
            SNAPSHOT_QUERY_SCHEMA_PROVIDER,
        ),
        targetType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            Any::class.java,
        ),
    )

    val eventStreamQueryGateway = DefaultEventStreamQueryGateway(
        namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
        binding = QueryBackendBinding(
            NoOpEventStreamQueryBackendFactory.create(MOCK_AGGREGATE_METADATA.namedAggregate).backend,
            EVENT_STREAM_QUERY_SCHEMA_PROVIDER,
        ),
    )
}

private fun QueryModelSchema.asProvider(): QueryModelSchemaProvider = object : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> = Mono.just(this@asProvider)
    override fun refresh(): Mono<QueryModelSchema> = schema()
}

private fun routeSchema(model: QueryModel): QueryModelSchema {
    fun scalar(type: me.ahoo.wow.api.query.schema.QueryValueType) = me.ahoo.wow.query.schema.QueryValueSchema(
        me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR,
        valueTypes = setOf(type)
    )
    val string = scalar(me.ahoo.wow.api.query.schema.QueryValueType.STRING)
    val number = scalar(me.ahoo.wow.api.query.schema.QueryValueType.INTEGER)
    val state = me.ahoo.wow.query.schema.QueryValueSchema(
        me.ahoo.wow.api.query.schema.QueryValueKind.OBJECT,
        properties = mapOf(
            "tags" to me.ahoo.wow.query.schema.QueryValueSchema(me.ahoo.wow.api.query.schema.QueryValueKind.ARRAY, items = string),
            "name" to string,
            "data" to string,
            "status" to string,
            "price" to number,
            "quantity" to number
        )
    )
    val definition = me.ahoo.wow.query.schema.LogicalQuerySchema(
        me.ahoo.wow.query.schema.QueryValueSchema(
            me.ahoo.wow.api.query.schema.QueryValueKind.OBJECT,
            properties = mapOf(
                "id" to string,
                "aggregateId" to string,
                "version" to number,
                "tenantId" to string,
                "ownerId" to string,
                "spaceId" to string,
                "deleted" to scalar(me.ahoo.wow.api.query.schema.QueryValueType.BOOLEAN),
                "state" to state
            ),
        )
    )
    val capabilities =
        setOf(
            me.ahoo.wow.api.query.schema.QueryCapability.EXACT_MATCH,
            me.ahoo.wow.api.query.schema.QueryCapability.RANGE,
            me.ahoo.wow.api.query.schema.QueryCapability.SORT,
            me.ahoo.wow.api.query.schema.QueryCapability.CURSOR_SORT,
            me.ahoo.wow.api.query.schema.QueryCapability.PRESENCE,
            me.ahoo.wow.api.query.schema.QueryCapability.AGGREGATE_TERMS,
            me.ahoo.wow.api.query.schema.QueryCapability.AGGREGATE_NUMERIC
        )
    return QueryModelSchema(
        model,
        emptySet(),
        definition,
        definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            me.ahoo.wow.query.schema.QueryValueBindings(
                if (value.kind in setOf(me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR, me.ahoo.wow.api.query.schema.QueryValueKind.ARRAY)) {
                    capabilities.associateWith {
                        me.ahoo.wow.query.schema.QueryFieldBindingTemplate(path, null)
                    }
                } else {
                    emptyMap()
                },
                path,
                path
            )
        }
    )
}
