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

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

data class Sort(val field: String, val direction: Direction) {
    enum class Direction {
        ASC, DESC
    }
}

data class Pagination(
    @field:Schema(defaultValue = "1")
    val index: Int = 1,
    @field:Schema(defaultValue = "10")
    val size: Int = 10
) {
    companion object {
        val DEFAULT = Pagination(1, 10)
        fun offset(index: Int, size: Int) = (index - 1) * size
    }

    fun offset() = offset(index, size)
}

data class Projection(
    @field:Schema(defaultValue = "[]")
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val include: List<String> = emptyList(),
    @field:Schema(defaultValue = "[]")
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val exclude: List<String> = emptyList()
) {
    companion object {
        val ALL = Projection()
    }
}

fun Projection.isEmpty(): Boolean {
    return include.isEmpty() && exclude.isEmpty()
}

interface Queryable<Q : Queryable<Q>> : ConditionCapable<Q>, ProjectionCapable<Q>, SortCapable
