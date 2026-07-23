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

import me.ahoo.wow.api.abac.AbacTagKey
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.wildcard
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.serialization.state.StateAggregateRecords.TAGS
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.util.context.ContextView

/**
 * Filters snapshot queries using attribute-based access control (ABAC).
 *
 * Principal tags from the current context are converted into query conditions and
 * appended to snapshot queries.
 *
 * ## Matching rules
 *
 * | Principal tags | Resource tags | Result |
 * |---------|---------|---------|
 * | wildcard (`["*"]`) | any | match |
 * | `["a", "b"]` | `["a"]` | match |
 * | `["a", "b"]` | `["c"]` | no match |
 * | any | key absent | match (public resource) |
 *
 * @see SnapshotQueryFilter
 */
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
abstract class AbacQueryFilter : SnapshotQueryFilter {
    companion object {
        /**
         * Converts one principal tag into a nested query condition.
         *
         * A wildcard requires only that the key exists. Other values match when the
         * key is absent or its value is in the principal tag value set.
         *
         * @return the nested query condition
         */
        fun Map.Entry<AbacTagKey, AbacTagValue>.toCondition(): Condition =
            condition {
                nested(TAGS)
                if (value.wildcard) {
                    key.exists(true)
                } else {
                    or {
                        key.exists(false)
                        key eq listOf<String>()
                        key isIn value
                    }
                }
            }

        /**
         * Combines all principal tags with AND semantics.
         *
         * @return the combined tag condition
         */
        fun AbacTags.toCondition(): Condition =
            condition {
                and {
                    for (tag in this@toCondition) {
                        condition(tag.toCondition())
                    }
                }
            }
    }

    /**
     * Resolves the principal's ABAC tags from the current context.
     *
     * @param contextView the Reactor context
     * @param context the query context used to resolve tag sources
     * @return the principal tag map
     */
    abstract fun getPrincipalTags(contextView: ContextView, context: QueryContext<*, *>): Mono<AbacTags>

    /**
     * Resolves the ABAC condition for the current context.
     *
     * @param contextView the Reactor context
     * @param context the query context
     * @return an unrestricted condition when no tags exist, otherwise the combined tag condition
     */
    open fun resolveCondition(contextView: ContextView, context: QueryContext<*, *>): Mono<Condition> {
        return getPrincipalTags(contextView, context).map {
            if (it.isEmpty()) {
                return@map Condition.all()
            }
            return@map it.toCondition()
        }.switchIfEmpty(Condition.all().toMono())
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>
    ): Mono<Void> {
        return Mono.deferContextual { contextView ->
            resolveCondition(contextView, context).flatMap { abacCondition ->
                if (abacCondition.operator == Operator.ALL) {
                    return@flatMap next.filter(context)
                }
                context.asRewritableQuery().rewriteQuery { query ->
                    query.appendCondition(abacCondition)
                }
                next.filter(context)
            }
        }
    }
}
