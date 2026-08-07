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

package me.ahoo.wow.query.internal.value

import java.util.Collections

internal class NonEmptyList<T> private constructor(values: List<T>) {
    val values: List<T> = Collections.unmodifiableList(values)

    val first: T
        get() = values.first()

    override fun equals(other: Any?): Boolean =
        this === other || other is NonEmptyList<*> && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    companion object {
        fun <T> of(
            first: T,
            vararg rest: T,
        ): NonEmptyList<T> = NonEmptyList(listOf(first, *rest))

        fun <T> from(values: Iterable<T>): NonEmptyList<T>? {
            val materialized = values.toList()
            if (materialized.isEmpty()) {
                return null
            }
            return NonEmptyList(materialized)
        }
    }
}
