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
 * A mandatory restriction of an aggregate's queries, evaluated at admission step 2 ([QueryAdmission]).
 *
 * Each policy reads the same query, the one every [QueryFilter][me.ahoo.wow.query.filter.QueryFilter] rewrite
 * produced with the caller's scope and route selection appended, and the captured Reactor context, and emits one
 * filter or an error. [MatchAllFilter][me.ahoo.wow.api.query.MatchAllFilter] adds no restriction; an empty publisher
 * is a server fault. Admission appends the policy filters (ANDed) after every rewrite, so no filter can remove them;
 * the model's default scope, validation and execution follow. Snapshot and EventStream gateways, and admitted point
 * reads, run the same stage. A policy uses the query context to decide whether it applies, emitting `MatchAllFilter`
 * when it does not. Policies do not replace queries, execute backends, or transform results.
 *
 * Policies run one after another in `@Order` order (`sortedByOrder`, like [QueryFilter]; unordered policies keep
 * their registration order). Their filters are ANDed, so the order decides only which policy's error, and which
 * audit entry, comes first.
 */
fun interface QueryPolicy {
    fun evaluate(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression>
}
