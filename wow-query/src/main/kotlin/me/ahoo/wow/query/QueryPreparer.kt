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

import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.profile
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Turns a caller's query into the query a backend may execute.
 *
 * The steps run in a fixed order: [QueryFilter]s rewrite the query, the caller's
 * [identity scope][queryScope] is appended, [QueryPolicy] restrictions are appended, and
 * finally the model's [default scope][me.ahoo.wow.query.schema.QueryModelProfile.defaultScope].
 * Policies therefore see the scoped query but not the model default.
 */
internal class QueryPreparer(
    private val namedAggregate: NamedAggregate,
    filters: List<QueryFilter>,
    policies: List<QueryPolicy>,
) {
    private val filters = filters.sortedByOrder()
    private val policies = policies.toList()

    fun <Q : RewritableFilter<Q>> prepare(query: Q, schema: QueryModelSchema, identity: ContextView): Mono<Q> =
        rewrite(query, schema).flatMap { rewritten ->
            val scoped = rewritten.restrict(identity.queryScope())
            restriction(identity, QueryContext(scoped, namedAggregate, schema)).map { scoped.restrict(it) }
        }.map { it.withDefaultScope(schema) }

    private fun <Q : RewritableFilter<Q>> rewrite(query: Q, schema: QueryModelSchema): Mono<Q> =
        filters.fold(Mono.just(query)) { pending, filter ->
            pending.flatMap { current ->
                Mono.defer { filter.prepare(QueryContext(current, namedAggregate, schema)) }
                    .switchIfEmpty(
                        Mono.error { IllegalStateException("QueryFilter.prepare must emit exactly one query.") }
                    )
            }
        }

    private fun restriction(identity: ContextView, context: QueryContext<*>): Mono<FilterExpression> =
        policies.fold(Mono.just<FilterExpression>(MatchAllFilter)) { pending, policy ->
            pending.flatMap { combined ->
                Mono.defer { policy.evaluate(identity, context) }
                    .switchIfEmpty(Mono.error { IllegalStateException("QueryPolicy must emit one filter.") })
                    .map { combined.restrict(it) }
            }
        }

    private fun <Q : RewritableFilter<Q>> Q.withDefaultScope(schema: QueryModelSchema): Q {
        val profile = schema.profile ?: return this
        val filter = when (this) {
            is FilterExpression -> this
            is FilterCapable<*> -> filter
            else -> error("Unsupported query filter contract.")
        }
        return restrict(profile.defaultScope(filter))
    }

    private fun <Q : RewritableFilter<Q>> Q.restrict(restriction: FilterExpression): Q =
        if (restriction === MatchAllFilter) this else appendFilter(restriction)
}
