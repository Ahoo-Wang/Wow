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

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.modeling.TypedAggregate
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.QueryResultPolicy
import me.ahoo.wow.query.schema.QuerySchemaProvider
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.ZoneId

interface SnapshotQueryGateway<S : Any> :
    NamedAggregateDecorator,
    TypedAggregate<S> {
    fun first(query: Query = Query()): Mono<MaterializedSnapshot<S>>

    fun firstRecord(query: Query = Query()): Mono<ObjectNode>

    fun stream(query: Query = Query()): Flux<MaterializedSnapshot<S>>

    fun stream(query: Query, limit: Int): Flux<MaterializedSnapshot<S>>

    fun streamRecords(query: Query = Query()): Flux<ObjectNode>

    fun streamRecords(query: Query, limit: Int): Flux<ObjectNode>

    fun page(query: Query, page: Int, size: Int): Mono<QueryPage<MaterializedSnapshot<S>>>

    fun pageRecords(query: Query, page: Int, size: Int): Mono<QueryPage<ObjectNode>>

    fun count(
        filter: QueryExpression = MatchAll,
        scope: QueryScope = QueryScope(),
        budget: QueryBudget = QueryBudget()
    ): Mono<Long>
}

interface SnapshotQueryGatewayFactory {
    fun <S : Any> create(metadata: AggregateMetadata<*, S>): SnapshotQueryGateway<S>

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            schemaProvider: QuerySchemaProvider,
            router: QueryRouter,
            objectMapper: ObjectMapper,
            policies: List<QueryPolicy> = emptyList(),
            resultPolicies: List<QueryResultPolicy> = emptyList(),
            limits: QueryLimits = QueryLimits(),
            clock: Clock = Clock.systemUTC(),
            zoneId: ZoneId = ZoneId.systemDefault()
        ): SnapshotQueryGatewayFactory = DefaultSnapshotQueryGatewayFactory(
            schemaProvider,
            router,
            objectMapper,
            policies,
            resultPolicies,
            limits,
            clock,
            zoneId
        )
    }
}
