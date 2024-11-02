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

package me.ahoo.wow.example.api.client

import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.apiclient.query.ReactiveSnapshotQueryApi
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.api.cart.CartData
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@CoApi(baseUrl = "http://localhost:8080")
@HttpExchange(ExampleService.CART_AGGREGATE_NAME)
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData> {
    override fun count(@RequestBody condition: Condition): Mono<Long>

    override fun pagedState(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<CartData>>

    override fun dynamicPaged(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<Map<String, Any>>>

    override fun paged(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<CartData>>>

    override fun listState(@RequestBody query: IListQuery): Flux<CartData>

    override fun dynamicList(@RequestBody query: IListQuery): Flux<Map<String, Any>>

    override fun list(@RequestBody query: IListQuery): Flux<MaterializedSnapshot<CartData>>

    override fun singleState(@RequestBody singleQuery: ISingleQuery): Mono<CartData>

    override fun dynamicSingle(@RequestBody singleQuery: ISingleQuery): Mono<Map<String, Any>>

    override fun single(@RequestBody singleQuery: ISingleQuery): Mono<MaterializedSnapshot<CartData>>
}
