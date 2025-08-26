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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.DataMasking
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilterTest.DataMaskable.Companion.MASKED_PWD
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class MaskingSnapshotQueryFilterTest {
    private val tailSnapshotQueryFilter = TailSnapshotQueryFilter<Any>(MockSnapshotQueryServiceFactory)
    private val stateDataMaskerRegistry = StateDataMaskerRegistry()
    private val snapshotQueryFilterChain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter, MaskingSnapshotQueryFilter(stateDataMaskerRegistry)))
        .filterCondition(SnapshotQueryHandler::class)
        .build()
    private val queryHandler = DefaultSnapshotQueryHandler(
        snapshotQueryFilterChain,
        LogErrorHandler()
    )

    init {
        stateDataMaskerRegistry.register(MockSnapshotMasker)
    }

    @Test
    fun single() {
        val query = singleQuery { }
        queryHandler.single(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                val state = it.state as DataMaskable
                state.pwd.assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        val query = singleQuery { }
        queryHandler.dynamicSingle(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                it.getNestedDocument("state").getValue<String>("pwd").assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun list() {
        val query = listQuery { }
        queryHandler.list(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                val state = it.state as DataMaskable
                state.pwd.assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        val query = listQuery { }
        queryHandler.dynamicList(MockSnapshotQueryService.namedAggregate, query)
            .test()
            .consumeNextWith {
                it.getNestedDocument("state").getValue<String>("pwd").assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.paged(MockSnapshotQueryService.namedAggregate, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isOne()
                val state = it.list.first().state as DataMaskable
                state.pwd.assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.dynamicPaged(MockSnapshotQueryService.namedAggregate, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isOne()
                it.list.first().getNestedDocument("state").getValue<String>("pwd").assert().isEqualTo(MASKED_PWD)
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        queryHandler.count(MockSnapshotQueryService.namedAggregate, Condition.ALL)
            .test()
            .consumeNextWith {
                it.assert().isOne()
            }
            .verifyComplete()
    }

    data class DataMaskable(val pwd: String) : DataMasking<DataMaskable> {
        companion object {
            const val MASKED_PWD = "******"
        }

        override fun mask(): DataMaskable {
            return copy(pwd = MASKED_PWD)
        }
    }

    object MockSnapshotMasker : StateDynamicDocumentMasker {
        override val namedAggregate: NamedAggregate
            get() = MockSnapshotQueryService.namedAggregate

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            dynamicDocument.getNestedDocument("state").put("pwd", MASKED_PWD)
            return dynamicDocument
        }
    }

    object MockSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
            @Suppress("UNCHECKED_CAST")
            return MockSnapshotQueryService as SnapshotQueryService<S>
        }
    }

    object MockSnapshotQueryService : SnapshotQueryService<DataMaskable> {
        override val name: String
            get() = "mock"
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

        private val dynamicDocument = snapshot.toJsonString().toObject<MutableMap<String, Any>>().toDynamicDocument()

        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<DataMaskable>> {
            return snapshot.toMono()
        }

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
            return dynamicDocument.toMono()
        }

        override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<DataMaskable>> {
            return Flux.just(snapshot)
        }

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
            return Flux.just(dynamicDocument)
        }

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<DataMaskable>>> {
            return PagedList(1, listOf(snapshot)).toMono()
        }

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
            return PagedList(1, listOf(dynamicDocument)).toMono()
        }

        override fun count(condition: Condition): Mono<Long> {
            return 1L.toMono()
        }
    }
}
