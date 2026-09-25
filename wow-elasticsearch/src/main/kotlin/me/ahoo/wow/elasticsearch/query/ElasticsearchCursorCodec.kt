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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode

/** Cursor positions as the JSON array of a hit's `sort()` values, decoded back to [FieldValue]s for `search_after`. */
internal object ElasticsearchCursorCodec : CursorPositionCodec {
    override fun encode(position: CursorPosition): ByteArray {
        require(position.values.size <= AggregationQuery.MAX_SORT_FIELDS)
        return JsonSerializer.writeValueAsBytes(position.values.map { (it as FieldValue).toCursorValue() })
    }

    override fun decode(payload: ByteArray, size: Int): CursorPosition {
        require(size in 1..AggregationQuery.MAX_SORT_FIELDS)
        val values = JsonSerializer.readTree(payload)
        require(values.isArray && values.size() == size)
        return CursorPosition(values.asSequence().map(JsonNode::toFieldValue).toList())
    }
}

private fun FieldValue.toCursorValue(): Any? = when {
    isNull -> null
    isBoolean -> booleanValue()
    isString -> stringValue()
    isLong -> longValue()
    isDouble -> doubleValue().also { require(it.isFinite()) }
    else -> throw IllegalArgumentException()
}

private fun JsonNode.toFieldValue(): FieldValue = when {
    isNull -> FieldValue.NULL
    isBoolean -> FieldValue.of(booleanValue())
    isString -> FieldValue.of(asString())
    isIntegralNumber && canConvertToLong() -> FieldValue.of(longValue())
    isFloatingPointNumber -> FieldValue.of(doubleValue().also { require(it.isFinite()) })
    else -> throw IllegalArgumentException()
}
