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
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import java.util.Base64

internal object ElasticsearchCursorCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(values: List<FieldValue>): String = invalidCursor {
        require(values.size <= AggregationQuery.MAX_SORT_FIELDS)
        encoder.encodeToString(JsonSerializer.writeValueAsBytes(values.map(FieldValue::toCursorValue)))
    }

    fun decode(cursor: String, expectedSize: Int): List<FieldValue> = invalidCursor {
        require(expectedSize in 1..AggregationQuery.MAX_SORT_FIELDS)
        val values = JsonSerializer.readTree(decoder.decode(cursor))
        require(values.isArray && values.size() == expectedSize)
        values.asSequence().map(JsonNode::toFieldValue).toList()
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

private inline fun <T> invalidCursor(block: () -> T): T = try {
    block()
} catch (_: Exception) {
    throw IllegalArgumentException("Invalid cursor.")
}
