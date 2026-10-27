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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * The positions of the PostgreSQL snapshot backend: the sort values of a row as a JSON array of `null`s, strings,
 * booleans and numbers. A number keeps its exact value (`numeric` is a [BigDecimal], decision D122), so a keyset
 * condition compares a decimal sort key against exactly the value the row held; it decodes as a [BigDecimal] and is
 * bound as its field's type.
 */
internal object PostgresCursorPositionCodec : CursorPositionCodec {
    override fun encode(position: CursorPosition): ByteArray {
        val values = position.values.map { value ->
            when (value) {
                null, is String, is Boolean, is BigDecimal -> value
                is Int, is Long -> BigDecimal.valueOf((value as Number).toLong())
                else -> throw IllegalArgumentException("Cursor values must be JSON scalars.")
            }
        }
        return WowJson.mapper.writeValueAsBytes(values)
    }

    override fun decode(payload: ByteArray, size: Int): CursorPosition {
        val values = WowJson.mapper.readTree(payload)
        require(values.isArray && values.size() == size)
        return CursorPosition(values.asSequence().map { it.scalar() }.toList())
    }

    private fun JsonNode.scalar(): Any? = when {
        isNull -> null
        isBoolean -> booleanValue()
        isString -> stringValue()
        isIntegralNumber -> BigDecimal(bigIntegerValue())
        isNumber -> decimalValue()
        else -> throw IllegalArgumentException("Cursor values must be JSON scalars.")
    }
}
