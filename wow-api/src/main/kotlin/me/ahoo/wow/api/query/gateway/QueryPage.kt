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

package me.ahoo.wow.api.query.gateway

import java.util.Collections

data class QueryPageSpec(
    val index: Int,
    val size: Int
) {
    init {
        require(index >= 1) { "Page index must be at least 1." }
        require(size >= 1) { "Page size must be at least 1." }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

enum class QueryConsistency {
    EXACT
}

class QueryPage<R : Any>(
    items: List<R>,
    val total: Long,
    val consistency: QueryConsistency
) {
    val items: List<R> = immutableList(items)

    init {
        require(total >= 0) { "total cannot be negative." }
        require(total >= items.size) { "total cannot be less than the number of page items." }
    }

    operator fun component1(): List<R> = items

    operator fun component2(): Long = total

    operator fun component3(): QueryConsistency = consistency

    fun copy(
        items: List<R> = this.items,
        total: Long = this.total,
        consistency: QueryConsistency = this.consistency
    ): QueryPage<R> = QueryPage(items, total, consistency)

    override fun equals(other: Any?): Boolean = other is QueryPage<*> &&
        items == other.items && total == other.total && consistency == other.consistency

    override fun hashCode(): Int = 31 * (31 * items.hashCode() + total.hashCode()) + consistency.hashCode()

    override fun toString(): String = "QueryPage(items=$items, total=$total, consistency=$consistency)"
}
