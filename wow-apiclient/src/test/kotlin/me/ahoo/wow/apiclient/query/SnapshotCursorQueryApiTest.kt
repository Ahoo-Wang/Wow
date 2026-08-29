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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import org.junit.jupiter.api.Test
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method

class SnapshotCursorQueryApiTest {
    @Test
    fun `legacy composite implementations should not require cursor methods`() {
        val reactive: ReactiveSnapshotQueryApi<Any> = LegacyReactiveSnapshotQueryApi()
        val synchronous: SynchronousSnapshotQueryApi<Any> = LegacySynchronousSnapshotQueryApi()

        reactive.count(MatchAllFilter).block()!!.assert().isZero()
        synchronous.count(MatchAllFilter).assert().isZero()
    }

    @Test
    fun `cursor methods should use cursor resources`() {
        val methods = SnapshotCursorQueryApi::class.java.methods.associateBy(Method::getName)
        methods.getValue("cursor").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor")
        methods.getValue("dynamicCursor").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor")
        methods.getValue("cursorState").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor/state")

        val api = object : ReactiveSnapshotCursorQueryApi<Any> {
            override fun cursor(query: ICursorQuery): Mono<CursorPage<MaterializedSnapshot<Any>>> = Mono.empty()
            override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<Map<String, Any>>> = Mono.empty()
            override fun cursorState(query: ICursorQuery): Mono<CursorPage<Any>> = Mono.empty()
        }
        val typed: Mono<CursorPage<MaterializedSnapshot<Any>>> = api.cursor(CursorQuery(MatchAllFilter))
        typed.assert().isNotNull()
    }

    private class LegacyReactiveSnapshotQueryApi : ReactiveSnapshotQueryApi<Any> {
        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> = Mono.empty()
        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<Map<String, Any>> = Mono.empty()
        override fun singleState(singleQuery: ISingleQuery): Mono<Any> = Mono.empty()
        override fun list(query: IListQuery): Flux<MaterializedSnapshot<Any>> = Flux.empty()
        override fun dynamicList(query: IListQuery): Flux<Map<String, Any>> = Flux.empty()
        override fun listState(query: IListQuery): Flux<Any> = Flux.empty()
        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> = Mono.empty()
        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<Map<String, Any>>> = Mono.empty()
        override fun pagedState(pagedQuery: IPagedQuery): Mono<PagedList<Any>> = Mono.empty()
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
    }

    private class LegacySynchronousSnapshotQueryApi : SynchronousSnapshotQueryApi<Any> {
        override fun single(singleQuery: ISingleQuery): MaterializedSnapshot<Any>? = null
        override fun dynamicSingle(singleQuery: ISingleQuery): Map<String, Any>? = null
        override fun singleState(singleQuery: ISingleQuery): Any? = null
        override fun list(query: IListQuery): List<MaterializedSnapshot<Any>> = emptyList()
        override fun dynamicList(query: IListQuery): List<Map<String, Any>> = emptyList()
        override fun listState(query: IListQuery): List<Any> = emptyList()
        override fun paged(pagedQuery: IPagedQuery): PagedList<MaterializedSnapshot<Any>> = PagedList(0, emptyList())
        override fun dynamicPaged(pagedQuery: IPagedQuery): PagedList<Map<String, Any>> = PagedList(0, emptyList())
        override fun pagedState(pagedQuery: IPagedQuery): PagedList<Any> = PagedList(0, emptyList())
        override fun count(filter: FilterExpression): Long = 0
    }
}
