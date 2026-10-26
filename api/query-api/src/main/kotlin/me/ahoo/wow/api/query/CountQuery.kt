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

package me.ahoo.wow.api.query

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A bounded count of the matches of [filter]; the result is a [CountResult].
 */
interface ICountQuery : FilterCapable<ICountQuery> {
    /**
     * The most matches to count: the count is exact below it; otherwise it is [limit] with [CountResult.exact]
     * `false`. `null` uses the budget's default limit.
     */
    val limit: Int?
}

/**
 * A bounded count of the matches of [filter].
 *
 * @property filter The filter expression the counted items match.
 * @property limit The most matches to count, at least 1; `null`, the default, uses the budget's default limit.
 *
 * ```
 * val query = CountQuery(EqualFilter(QueryField("state.status"), value), limit = 1000)
 * ```
 */
class CountQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val limit: Int? = null,
) : ICountQuery {
    init {
        require(limit == null || limit >= 1) {
            "limit must be at least 1."
        }
    }

    override fun withFilter(newFilter: FilterExpression): ICountQuery = CountQuery(newFilter, limit)

    override fun equals(other: Any?): Boolean =
        this === other || other is CountQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CountQuery(filter=$filter, limit=$limit)"

    private fun valueFields(): List<Any?> = listOf(filter, limit)

    companion object {
        /** A [CountQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(filter: FilterExpression, limit: Int? = null): CountQuery = CountQuery(filter, limit)
    }
}
