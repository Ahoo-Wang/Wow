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

import me.ahoo.wow.api.query.expression.LogicalField
import java.util.Collections

sealed interface QueryProjection {
    data object All : QueryProjection

    class Include(fields: Set<LogicalField>) : QueryProjection {
        val fields: Set<LogicalField> = immutableSet(fields)

        init {
            require(this.fields.isNotEmpty()) { "Included fields cannot be empty." }
        }

        operator fun component1(): Set<LogicalField> = fields

        fun copy(fields: Set<LogicalField> = this.fields): Include = Include(fields)

        override fun equals(other: Any?): Boolean = other is Include && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Include(fields=$fields)"
    }

    class Exclude(fields: Set<LogicalField>) : QueryProjection {
        val fields: Set<LogicalField> = immutableSet(fields)

        init {
            require(this.fields.isNotEmpty()) { "Excluded fields cannot be empty." }
        }

        operator fun component1(): Set<LogicalField> = fields

        fun copy(fields: Set<LogicalField> = this.fields): Exclude = Exclude(fields)

        override fun equals(other: Any?): Boolean = other is Exclude && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Exclude(fields=$fields)"
    }
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
