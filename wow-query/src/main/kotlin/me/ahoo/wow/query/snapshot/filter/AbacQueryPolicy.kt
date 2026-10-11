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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.DatasetQueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.serialization.state.StateAggregateRecords.TAGS
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.util.context.ContextView

/**
 * Resolves terminal access conditions using attribute-based access control (ABAC).
 *
 * Principal tags from the current context are converted into query conditions and appended to snapshot queries, whose
 * resource tags are the `tags` system field, and to the queries of a dataset that binds tag columns
 * ([DatasetQueryModelProfile.tags]): a string column holds one tag value per record and a string-array column several,
 * and an empty string or array is a record without the tag. Other query models, and datasets that bind no tag, are
 * unrestricted by this policy and do not resolve principal tags.
 *
 * ## Matching rules
 *
 * | Principal tags | Resource tags | Result |
 * |---------|---------|---------|
 * | wildcard (`["*"]`) | any | match |
 * | `["a", "b"]` | `["a"]` | match |
 * | `["a", "b"]` | `["c"]` | no match |
 * | any | key absent | match (public resource) |
 * | no tags | any | match |
 *
 * The last two rows are the defaults; [AbacQueryOptions] tightens each.
 */
abstract class AbacQueryPolicy(
    val options: AbacQueryOptions = AbacQueryOptions.DEFAULT,
) : QueryPolicy {
    companion object {
        /**
         * Converts one principal tag into a nested query condition.
         *
         * A wildcard requires only that the key exists. Other values match when the
         * key's value is in the principal tag value set, or, with [matchMissingTagKey], when the key is absent or
         * empty.
         *
         * @return the nested query condition
         */
        fun Map.Entry<AbacTagKey, AbacTagValue>.toFilterExpression(
            matchMissingTagKey: Boolean = true
        ): FilterExpression {
            return me.ahoo.wow.query.dsl.filter {
                TAGS.path {
                    if (value.wildcard) {
                        key.exists()
                    } else if (!matchMissingTagKey) {
                        key isIn value
                    } else {
                        or {
                            key.notExists()
                            key.isEmptyCollection()
                            key isIn value
                        }
                    }
                }
            }
        }

        /**
         * Combines all principal tags with AND semantics.
         *
         * @return the combined tag condition
         */
        /**
         * Combines all principal tags with AND semantics over a dataset's tag columns ([columns], by tag key), with
         * the rules of the `tags` system field: a key the dataset binds no column for is a tag no record carries.
         *
         * @return the combined tag condition
         */
        fun AbacTags.toFilterExpression(
            columns: Map<AbacTagKey, QueryField>,
            schema: QueryModelSchema,
            matchMissingTagKey: Boolean = true,
        ): FilterExpression {
            val conditions = mapNotNull { (key, value) ->
                val column = columns[key]
                when {
                    column != null -> value.toFilterExpression(column, schema, matchMissingTagKey)
                    value.wildcard || !matchMissingTagKey -> MatchNoneFilter
                    else -> null
                }
            }
            return when (conditions.size) {
                0 -> MatchAllFilter
                1 -> conditions.single()
                else -> AndFilter(conditions)
            }
        }

        /** One principal tag over its dataset column: a string column, or a string-array one ([collection]). */
        @OptIn(InternalWowApi::class)
        private fun AbacTagValue.toFilterExpression(
            column: QueryField,
            schema: QueryModelSchema,
            matchMissingTagKey: Boolean,
        ): FilterExpression {
            val values = this
            val field = column.path
            val collection = schema.field(column)?.effective?.collection == true
            return me.ahoo.wow.query.dsl.filter {
                when {
                    // Not EXISTS: an empty array is the record without the tag, on every storage.
                    values.wildcard && collection -> nor { field.isEmptyCollection() }
                    values.wildcard -> field ne EMPTY_TAG
                    !matchMissingTagKey -> field isIn values
                    collection -> or {
                        field.isEmptyCollection()
                        field isIn values
                    }
                    else -> field isIn values + EMPTY_TAG
                }
            }
        }

        private const val EMPTY_TAG = ""

        fun AbacTags.toFilterExpression(matchMissingTagKey: Boolean = true): FilterExpression =
            if (isEmpty()) {
                MatchAllFilter
            } else {
                me.ahoo.wow.query.dsl.filter {
                    and {
                        for (tag in this@toFilterExpression) {
                            expression(tag.toFilterExpression(matchMissingTagKey))
                        }
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
    abstract fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags>

    /**
     * Resolves the ABAC condition for the current context.
     *
     * @param contextView the Reactor context
     * @param context the query context
     * @return an unrestricted condition for other models or when no tags exist, otherwise the combined tag condition;
     * with [AbacQueryOptions.requirePrincipalTags], an [AbacPrincipalTagsRequiredException] for an HTTP query whose
     * principal has no tags
     */
    override fun evaluate(
        contextView: ContextView,
        context: QueryContext<*>
    ): Mono<FilterExpression> {
        val profile = context.schema.profile
        val condition: (AbacTags) -> FilterExpression = when {
            context.schema.model == QueryModel.SNAPSHOT -> { tags ->
                tags.toFilterExpression(
                    options.matchMissingTagKey
                )
            }
            profile is DatasetQueryModelProfile && profile.tags.isNotEmpty() -> { tags ->
                tags.toFilterExpression(profile.tags, context.schema, options.matchMissingTagKey)
            }
            else -> return Mono.just(MatchAllFilter)
        }
        return getPrincipalTags(contextView, context)
            .filter { it.isNotEmpty() }
            .map(condition)
            .switchIfEmpty(
                Mono.defer {
                    if (options.requirePrincipalTags && context.entry == QueryEntry.HTTP) {
                        Mono.error(AbacPrincipalTagsRequiredException())
                    } else {
                        MatchAllFilter.toMono()
                    }
                }
            )
    }
}
