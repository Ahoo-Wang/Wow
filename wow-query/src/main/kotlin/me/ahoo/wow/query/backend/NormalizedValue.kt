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

package me.ahoo.wow.query.backend

import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/** Backend-neutral, deeply immutable value admitted into a validated Query Plan. */
@ExperimentalQueryBackendApi
sealed interface NormalizedValue {
    data object Null : NormalizedValue

    data class BooleanValue(val value: Boolean) : NormalizedValue

    data class Text(val value: String) : NormalizedValue

    data class Int64(val value: Long) : NormalizedValue

    class Decimal(value: BigDecimal) : NormalizedValue {
        val value: BigDecimal = value.stripTrailingZeros()

        override fun equals(other: Any?): Boolean =
            this === other || other is Decimal && value == other.value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Decimal(value=$value)"
    }

    data class InstantValue(val value: Instant) : NormalizedValue

    class Bytes(value: ByteArray) : NormalizedValue {
        private val value: ByteArray = value.copyOf()

        fun toByteArray(): ByteArray = value.copyOf()

        override fun equals(other: Any?): Boolean =
            this === other || other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(size=${value.size})"
    }

    class ListValue(values: Iterable<NormalizedValue>) : NormalizedValue {
        val values: List<NormalizedValue> = Collections.unmodifiableList(values.toList())

        override fun equals(other: Any?): Boolean =
            this === other || other is ListValue && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = values.toString()
    }

    class ObjectValue(values: Map<String, NormalizedValue>) : NormalizedValue {
        val values: Map<String, NormalizedValue> = Collections.unmodifiableMap(LinkedHashMap(values))
        private val orderedEntries: List<Pair<String, NormalizedValue>> =
            this.values.map { entry -> entry.key to entry.value }

        override fun equals(other: Any?): Boolean =
            this === other || other is ObjectValue && orderedEntries == other.orderedEntries

        override fun hashCode(): Int = orderedEntries.hashCode()

        override fun toString(): String = values.toString()
    }
}
