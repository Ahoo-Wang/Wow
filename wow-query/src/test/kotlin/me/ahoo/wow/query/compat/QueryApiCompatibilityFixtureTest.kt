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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.toState
import me.ahoo.wow.query.snapshot.toStateCursorPage
import me.ahoo.wow.query.snapshot.toStatePagedList
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import me.ahoo.wow.query.event.count as eventCount
import me.ahoo.wow.query.event.dynamicQuery as eventDynamicQuery
import me.ahoo.wow.query.event.query as eventQuery
import me.ahoo.wow.query.snapshot.count as snapshotCount
import me.ahoo.wow.query.snapshot.dynamicQuery as snapshotDynamicQuery
import me.ahoo.wow.query.snapshot.query as snapshotQuery

/**
 * The compatibility fixture of the frozen query surface (design §1, §10): the ten QueryGateway methods, the query
 * DSL and the gateway query extensions, written the way callers write them. Every reference carries its full
 * expected type, so a changed parameter or return type, a renamed function or a removed overload fails to compile.
 * Changing this file means changing the guarantee.
 */
class QueryApiCompatibilityFixtureTest {
    private data class State(val id: String)

    @Test
    fun `the ten QueryGateway methods keep their signatures`() {
        val gateway: List<Any> = listOf(
            QueryGateway<Any>::single as QueryGateway<Any>.(ISingleQuery) -> Mono<Any>,
            QueryGateway<Any>::dynamicSingle as QueryGateway<Any>.(ISingleQuery) -> Mono<ObjectNode>,
            QueryGateway<Any>::list as QueryGateway<Any>.(IListQuery) -> Flux<Any>,
            QueryGateway<Any>::dynamicList as QueryGateway<Any>.(IListQuery) -> Flux<ObjectNode>,
            QueryGateway<Any>::paged as QueryGateway<Any>.(IPagedQuery) -> Mono<PagedList<Any>>,
            QueryGateway<Any>::dynamicPaged as QueryGateway<Any>.(IPagedQuery) -> Mono<PagedList<ObjectNode>>,
            QueryGateway<Any>::cursor as QueryGateway<Any>.(ICursorQuery) -> Mono<CursorPage<Any>>,
            QueryGateway<Any>::dynamicCursor as QueryGateway<Any>.(ICursorQuery) -> Mono<CursorPage<ObjectNode>>,
            QueryGateway<Any>::count as QueryGateway<Any>.(FilterExpression) -> Mono<Long>,
            QueryGateway<Any>::aggregate as QueryGateway<Any>.(AggregationQuery) -> Flux<ObjectNode>,
        )
        gateway.assert().hasSize(10)
    }

    @Test
    fun `the snapshot query extensions keep their signatures`() {
        val single: ISingleQuery.(SnapshotQueryGateway<State>) -> Mono<MaterializedSnapshot<State>> =
            ISingleQuery::snapshotQuery
        val list: IListQuery.(SnapshotQueryGateway<State>) -> Flux<MaterializedSnapshot<State>> =
            IListQuery::snapshotQuery
        val paged: IPagedQuery.(SnapshotQueryGateway<State>) -> Mono<PagedList<MaterializedSnapshot<State>>> =
            IPagedQuery::snapshotQuery
        val cursor: ICursorQuery.(SnapshotQueryGateway<State>) -> Mono<CursorPage<MaterializedSnapshot<State>>> =
            ICursorQuery::snapshotQuery
        val dynamicSingle: ISingleQuery.(
            SnapshotQueryGateway<*>
        ) -> Mono<ObjectNode> = ISingleQuery::snapshotDynamicQuery
        val dynamicList: IListQuery.(SnapshotQueryGateway<*>) -> Flux<ObjectNode> = IListQuery::snapshotDynamicQuery
        val dynamicPaged: IPagedQuery.(SnapshotQueryGateway<*>) -> Mono<PagedList<ObjectNode>> =
            IPagedQuery::snapshotDynamicQuery
        val dynamicCursor: ICursorQuery.(SnapshotQueryGateway<*>) -> Mono<CursorPage<ObjectNode>> =
            ICursorQuery::snapshotDynamicQuery
        val count: FilterExpression.(SnapshotQueryGateway<*>) -> Mono<Long> = FilterExpression::snapshotCount
        val legacyCount: Condition.(SnapshotQueryGateway<*>) -> Mono<Long> = Condition::snapshotCount
        val aggregate: AggregationQuery.(SnapshotQueryGateway<*>) -> Flux<ObjectNode> = AggregationQuery::snapshotQuery
        val toState: Mono<MaterializedSnapshot<State>>.() -> Mono<State> = Mono<MaterializedSnapshot<State>>::toState
        val toStates: Flux<MaterializedSnapshot<State>>.() -> Flux<State> = Flux<MaterializedSnapshot<State>>::toState
        val toPage: Mono<PagedList<MaterializedSnapshot<State>>>.() -> Mono<PagedList<State>> =
            Mono<PagedList<MaterializedSnapshot<State>>>::toStatePagedList
        val toCursorPage: Mono<CursorPage<MaterializedSnapshot<State>>>.() -> Mono<CursorPage<State>> =
            Mono<CursorPage<MaterializedSnapshot<State>>>::toStateCursorPage
        listOf(
            single, list, paged, cursor, dynamicSingle, dynamicList, dynamicPaged, dynamicCursor,
            count, legacyCount, aggregate, toState, toStates, toPage, toCursorPage,
        ).assert().hasSize(15)
    }

    @Test
    fun `the event stream query extensions keep their signatures`() {
        val single: ISingleQuery.(EventStreamQueryGateway) -> Mono<DomainEventStream> = ISingleQuery::eventQuery
        val list: IListQuery.(EventStreamQueryGateway) -> Flux<DomainEventStream> = IListQuery::eventQuery
        val paged: IPagedQuery.(EventStreamQueryGateway) -> Mono<PagedList<DomainEventStream>> = IPagedQuery::eventQuery
        val cursor: ICursorQuery.(EventStreamQueryGateway) -> Mono<CursorPage<DomainEventStream>> =
            ICursorQuery::eventQuery
        val dynamicSingle: ISingleQuery.(EventStreamQueryGateway) -> Mono<ObjectNode> = ISingleQuery::eventDynamicQuery
        val dynamicList: IListQuery.(EventStreamQueryGateway) -> Flux<ObjectNode> = IListQuery::eventDynamicQuery
        val count: FilterExpression.(EventStreamQueryGateway) -> Mono<Long> = FilterExpression::eventCount
        val legacyCount: Condition.(EventStreamQueryGateway) -> Mono<Long> = Condition::eventCount
        val aggregate: AggregationQuery.(EventStreamQueryGateway) -> Flux<ObjectNode> = AggregationQuery::eventQuery
        listOf(single, list, paged, cursor, dynamicSingle, dynamicList, count, legacyCount, aggregate)
            .assert().hasSize(9)
    }

    @Test
    fun `the query DSL builds every query shape, with filter and with legacy condition`() {
        val single: ISingleQuery = singleQuery {
            filter {
                tenantId("tenant")
                id("order")
            }
        }
        val legacySingle: ISingleQuery = singleQuery { condition { "state.status" eq "PAID" } }
        val list: IListQuery = listQuery {
            filter { "state.status" eq "PAID" }
            sort { "version".desc() }
            projection { include("state.status") }
            limit(10)
        }
        val paged: IPagedQuery = pagedQuery {
            condition { "state.amount" gt 100 }
            pagination {
                index(2)
                size(20)
            }
        }
        val cursor: ICursorQuery = cursorQuery {
            filter { "state.status" eq "PAID" }
            size(20)
            cursor("token")
        }
        val filterExpression: FilterExpression = filter { "state.status" eq "PAID" }
        val legacy: Condition = condition {
            "state.status" eq "PAID"
            or { "state.amount" gt 100 }
        }
        val aggregation: AggregationQuery = aggregation {
            filter { "state.status" eq "PAID" }
            terms("state.status", alias = "status")
            count(alias = "count")
            sum("state.amount", alias = "total")
            limit(20)
        }
        listOf(single, legacySingle, list, paged, cursor, filterExpression, legacy, aggregation)
            .forEach { it.assert().isNotNull() }
        paged.pagination.index.assert().isEqualTo(2)
    }
}
