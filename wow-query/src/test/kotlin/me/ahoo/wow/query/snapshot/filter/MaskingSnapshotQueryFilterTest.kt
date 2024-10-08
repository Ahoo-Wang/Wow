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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DataMasking
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class MaskingSnapshotQueryFilterTest {
    private val tailSnapshotQueryFilter = TailSnapshotQueryFilter<Any>(MockSnapshotQueryServiceFactory)
    private val snapshotQueryFilterChain = FilterChainBuilder<SnapshotQueryContext<*, *, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter, MaskingSnapshotQueryFilter))
        .filterCondition(SnapshotQueryHandler::class)
        .build()
    private val queryHandler = DefaultSnapshotQueryHandler(
        snapshotQueryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun single() {
        val query = singleQuery { }
        queryHandler.single<DataMaskable>(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                assertThat(it.state.pwd, equalTo("******"))
            }
            .verifyComplete()
    }

    @Test
    fun query() {
        val query = listQuery { }
        queryHandler.list<DataMaskable>(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                assertThat(it.state.pwd, equalTo("******"))
            }
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.paged<DataMaskable>(MockSnapshotQueryService.namedAggregate, pagedQuery)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(1))
                assertThat(it.list.first().state.pwd, equalTo("******"))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        queryHandler.count(MockSnapshotQueryService.namedAggregate, Condition.ALL)
            .test()
            .consumeNextWith {
                assertThat(it, equalTo(1L))
            }
            .verifyComplete()
    }

    data class DataMaskable(val pwd: String) : DataMasking<DataMaskable> {
        override fun mask(): DataMaskable {
            return copy(pwd = "******")
        }
    }

    object MockSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
            @Suppress("UNCHECKED_CAST")
            return MockSnapshotQueryService as SnapshotQueryService<S>
        }
    }

    object MockSnapshotQueryService : SnapshotQueryService<DataMaskable> {
        override val namedAggregate: NamedAggregate
            get() = "test.masking".toNamedAggregate()
        private val snapshot = MaterializedSnapshot(
            contextName = "contextName",
            aggregateName = "aggregateName",
            tenantId = "tenantId",
            aggregateId = "aggregateId",
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = DataMaskable("pwd"),
            snapshotTime = 1,
            deleted = false
        )

        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<DataMaskable>> {
            return snapshot.toMono()
        }

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
            return Mono.empty()
        }

        override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<DataMaskable>> {
            return Flux.just(snapshot)
        }

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
            return Flux.empty()
        }

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<DataMaskable>>> {
            return PagedList(1, listOf(snapshot)).toMono()
        }

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
            return PagedList.empty<DynamicDocument>().toMono()
        }

        override fun count(condition: Condition): Mono<Long> {
            return 1L.toMono()
        }
    }
}
