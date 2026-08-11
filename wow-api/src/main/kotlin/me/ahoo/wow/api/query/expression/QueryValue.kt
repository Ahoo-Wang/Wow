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

package me.ahoo.wow.api.query.expression

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(QueryValue.BooleanValue::class, name = "boolean"),
    JsonSubTypes.Type(QueryValue.IntegerValue::class, name = "integer"),
    JsonSubTypes.Type(QueryValue.FloatingValue::class, name = "floating"),
    JsonSubTypes.Type(QueryValue.DecimalValue::class, name = "decimal"),
    JsonSubTypes.Type(QueryValue.StringValue::class, name = "string"),
    JsonSubTypes.Type(QueryValue.InstantValue::class, name = "instant"),
    JsonSubTypes.Type(QueryValue.EnumValue::class, name = "enum"),
    JsonSubTypes.Type(QueryValue.ListValue::class, name = "list"),
    JsonSubTypes.Type(QueryValue.ObjectValue::class, name = "object"),
    JsonSubTypes.Type(QueryValue.BinaryValue::class, name = "binary"),
    JsonSubTypes.Type(QueryValue.NullValue::class, name = "null")
)
sealed interface QueryValue {
    data class BooleanValue(val value: Boolean) : QueryValue

    data class IntegerValue(val value: Long) : QueryValue

    data class FloatingValue(val value: Double) : QueryValue {
        init {
            require(value.isFinite()) { "value must be finite." }
        }
    }

    data class DecimalValue(val value: BigDecimal) : QueryValue

    data class StringValue(val value: String) : QueryValue

    data class InstantValue(val value: Instant) : QueryValue

    data class EnumValue(val value: String) : QueryValue {
        init {
            require(value.isNotBlank()) { "value cannot be blank." }
        }
    }

    class ListValue(values: List<QueryValue>) : QueryValue {
        val values: List<QueryValue> = immutableList(values)

        override fun equals(other: Any?): Boolean = other is ListValue && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "ListValue(values=$values)"
    }

    class ObjectValue(values: Map<String, QueryValue>) : QueryValue {
        val values: Map<String, QueryValue> = immutableMap(values) { key ->
            require(key.isNotBlank()) { "Object value key cannot be blank." }
        }

        override fun equals(other: Any?): Boolean = other is ObjectValue && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "ObjectValue(values=$values)"
    }

    class BinaryValue(value: ByteArray) : QueryValue {
        private val snapshot: ByteArray = value.copyOf()

        val value: ByteArray
            get() = snapshot.copyOf()

        override fun equals(other: Any?): Boolean = other is BinaryValue && snapshot.contentEquals(other.snapshot)

        override fun hashCode(): Int = snapshot.contentHashCode()

        override fun toString(): String = "BinaryValue(size=${snapshot.size})"
    }

    data object NullValue : QueryValue
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMap(values: Map<K, V>, validateKey: (K) -> Unit = {}): Map<K, V> {
    val snapshot = LinkedHashMap<K, V>(values.size)
    values.forEach { (key, value) ->
        validateKey(key)
        snapshot[key] = value
    }
    return Collections.unmodifiableMap(snapshot)
}
