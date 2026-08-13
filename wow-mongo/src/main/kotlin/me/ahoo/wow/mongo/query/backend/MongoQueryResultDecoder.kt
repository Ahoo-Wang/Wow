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

@file:JvmSynthetic

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.serialization.convert
import org.bson.BsonBinary
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.Decimal128
import java.util.Date

internal class MongoQueryResultDecoder {
    fun <R : Any> decode(
        source: Document,
        shape: QueryPlanResultShape,
        projection: Map<LogicalField, String>
    ): R {
        val values = LinkedHashMap<String, Any?>(projection.size)
        projection.forEach { (logical, physical) ->
            val resolved = resolve(source, physical)
            if (resolved !== MISSING) {
                values[logical.value] = normalize(resolved)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return when (shape) {
            is QueryPlanResultShape.Dynamic -> ImmutableDynamicDocument.copyOf(values) as R
            is QueryPlanResultShape.Typed -> values.convert(shape.resultType) as R
            QueryPlanResultShape.Count -> error("Count plans do not decode result documents.")
        }
    }

    private fun resolve(source: Any?, path: String): Any? {
        var current: Any? = source
        path.split('.').forEach { segment ->
            current = when (val value = current) {
                is Map<*, *> -> if (value.containsKey(segment)) value[segment] else return MISSING
                else -> return MISSING
            }
        }
        return current
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Document -> value.entries.associateTo(LinkedHashMap()) { (key, nested) -> key to normalize(nested) }
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (key, nested) ->
            require(key is String) { "Mongo result document keys must be strings." }
            key to normalize(nested)
        }
        is List<*> -> value.map(::normalize)
        is Decimal128 -> value.bigDecimalValue()
        is Date -> value.toInstant()
        is Binary -> value.data.copyOf()
        is BsonBinary -> value.data.copyOf()
        else -> value
    }

    private companion object {
        val MISSING: Any = Any()
    }
}
