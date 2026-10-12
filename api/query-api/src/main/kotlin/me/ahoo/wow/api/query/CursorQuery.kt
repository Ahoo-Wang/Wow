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

data class CursorQuery(
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

    override fun withFilter(newFilter: FilterExpression): ICursorQuery = copy(filter = newFilter)

    override fun withProjection(newProjection: Projection): ICursorQuery = copy(projection = newProjection)

    companion object {
        const val DEFAULT_SIZE: Int = 10
    }
}

interface ICursorQuery : Queryable<ICursorQuery> {
    val size: Int
    val cursor: String?
}
