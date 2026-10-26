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

class CursorPage<out T> @InternalWowApi constructor(
    val list: List<T>,
    val nextCursor: String?,
) {
    /** This CursorPage with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        list: List<@UnsafeVariance T> = this.list,
        nextCursor: String? = this.nextCursor,
    ): CursorPage<T> = CursorPage(list, nextCursor)

    override fun equals(other: Any?): Boolean =
        this === other || other is CursorPage<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CursorPage(list=$list, nextCursor=$nextCursor)"

    private fun valueFields(): List<Any?> = listOf(list, nextCursor)

    companion object {
        /** A [CursorPage] of the given values. */
        @JvmStatic
        fun <T> of(list: List<T>, nextCursor: String?): CursorPage<T> = CursorPage(list, nextCursor)
    }
}
