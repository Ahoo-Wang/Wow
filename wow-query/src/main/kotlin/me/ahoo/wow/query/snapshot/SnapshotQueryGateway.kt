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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.query.AbstractQueryGateway
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import tools.jackson.databind.JavaType

interface SnapshotQueryGateway<S : Any> : QueryGateway<MaterializedSnapshot<S>>

class DefaultSnapshotQueryGateway<S : Any>(
    namedAggregate: NamedAggregate,
    binding: QueryBackendBinding<SnapshotQueryBackend>,
    targetType: JavaType,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    observer: QueryObserver = QueryLogObserver(),
) : SnapshotQueryGateway<S>,
    AbstractQueryGateway<MaterializedSnapshot<S>>(
        namedAggregate,
        binding,
        targetType,
        filters,
        SnapshotQueryGateway::class,
        observer,
    ) {
    private val policies = policies.toList()

    override fun policyFilter(identity: ContextView, context: QueryContext<*>): Mono<FilterExpression> =
        policies.fold(Mono.just<FilterExpression>(MatchAllFilter)) { pending, policy ->
            pending.flatMap { combined ->
                Mono.defer { policy.evaluate(identity, context) }
                    .switchIfEmpty(
                        Mono.error { IllegalStateException("QueryPolicy must emit one filter.") }
                    )
                    .map { if (it === MatchAllFilter) combined else combined.appendFilter(it) }
            }
        }
}
