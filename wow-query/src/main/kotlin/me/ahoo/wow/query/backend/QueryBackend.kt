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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface QueryBackend {
    val descriptor: QueryBackendDescriptor

    fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R>

    fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R>

    fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>>

    fun count(plan: CountQueryPlanV1): Mono<Long>

    fun readiness(): Mono<QueryBackendReadiness>
}
