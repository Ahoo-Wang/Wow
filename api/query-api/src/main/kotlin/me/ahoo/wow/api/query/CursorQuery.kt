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

class CursorQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val size: Int = DEFAULT_SIZE,
    override val cursor: String? = null,
) : ICursorQuery {
    init {
        require(size in 1 until Int.MAX_VALUE) {
            "size must be between 1 and ${Int.MAX_VALUE - 1}."
        }
        require(sort.size <= Sort.MAX_FIELDS) {
            "sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
    }

    override fun withFilter(newFilter: FilterExpression): ICursorQuery = CursorQuery(
        newFilter,
        projection,
        sort,
        size,
        cursor
    )

    override fun withProjection(newProjection: Projection): ICursorQuery = CursorQuery(
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

interface ICursorQuery : Queryable<ICursorQuery> {
    val size: Int
    val cursor: String?
}
