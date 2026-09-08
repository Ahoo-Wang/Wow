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

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.query.filter.QueryContext
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Supplies mandatory access conditions after request preparation.
 *
 * Each configured policy reads the same prepared query and captured identity and emits
 * one filter or an error. MatchAllFilter adds no restriction; an empty publisher is a
 * protocol error. The gateway combines access filters with AND before validation and execution.
 * Policies do not replace queries, execute backends, or transform results.
 */
fun interface QueryPolicy {
    fun resolveFilter(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression>
}
