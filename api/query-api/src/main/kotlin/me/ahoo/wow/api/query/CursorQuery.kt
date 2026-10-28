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
 * A query for the page of results after a [cursor], in the order of [sort], with filtering and projection
 * ([Queryable]).
 *
 * @property filter The filter expression the results match.
 * @property projection The fields the results include or exclude.
 * @property sort The order of the results, which the cursor follows.
 * @property size The most items on the page, at least 1.
 * @property cursor Where the page starts: the previous page's next cursor, or `null` for the first page.
 */
class CursorQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    val size: Int = DEFAULT_SIZE,
    val cursor: String? = null,
) : Queryable<CursorQuery> {
    init {
        require(size in 1 until Int.MAX_VALUE) {
            "size must be between 1 and ${Int.MAX_VALUE - 1}."
        }
        require(sort.size <= Sort.MAX_FIELDS) {
            "sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
    }

    override fun withFilter(newFilter: FilterExpression): CursorQuery = CursorQuery(
        newFilter,
        projection,
        sort,
        size,
        cursor
    )

    override fun withProjection(newProjection: Projection): CursorQuery = CursorQuery(
        filter,
        newProjection,
        sort,
        size,
        cursor
    )

    companion object {
        const val DEFAULT_SIZE: Int = 10

        /** A [CursorQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            filter: FilterExpression,
            projection: Projection = Projection.ALL,
            sort: List<Sort> = emptyList(),
            size: Int = DEFAULT_SIZE,
            cursor: String? = null,
        ): CursorQuery = CursorQuery(filter, projection, sort, size, cursor)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CursorQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CursorQuery(filter=$filter, projection=$projection, sort=$sort, size=$size, cursor=$cursor)"

    private fun valueFields(): List<Any?> = listOf(filter, projection, sort, size, cursor)
}
