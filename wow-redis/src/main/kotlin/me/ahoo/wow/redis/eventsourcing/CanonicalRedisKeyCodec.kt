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

package me.ahoo.wow.redis.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.redis.RedisKeyComponentCodec

internal object CanonicalRedisKeyCodec {
    private const val TUPLE_DELIMITER = '.'
    private const val INDEX_AFTER_ID_DELIMITER = '/'
    private const val HEX_RADIX = 16
    private const val HEX_CHARS_PER_CODE_UNIT = 4
    fun encodeScope(namedAggregate: NamedAggregate): String {
        return "${RedisKeyComponentCodec.encode(namedAggregate.getContextAlias())}$TUPLE_DELIMITER" +
            RedisKeyComponentCodec.encode(namedAggregate.aggregateName)
    }

    fun encodeIdentity(aggregateId: AggregateId): String {
        require(aggregateId.id.isNotEmpty()) { "Aggregate id must not be empty." }
        return "${RedisKeyComponentCodec.encode(aggregateId.id)}$TUPLE_DELIMITER" +
            RedisKeyComponentCodec.encode(aggregateId.tenantId)
    }

    fun encodeIndexMember(aggregateId: AggregateId): String {
        return "${encodeSortableId(aggregateId.id)}$TUPLE_DELIMITER" +
            RedisKeyComponentCodec.encode(aggregateId.tenantId)
    }

    fun decodeIndexMember(namedAggregate: NamedAggregate, member: String): AggregateId {
        val delimiterIndex = member.indexOf(TUPLE_DELIMITER)
        require(delimiterIndex > 0 && delimiterIndex == member.lastIndexOf(TUPLE_DELIMITER)) {
            "Invalid aggregate id index member:$member"
        }
        val id = decodeSortableId(member.substring(0, delimiterIndex), member)
        val tenantId = try {
            RedisKeyComponentCodec.decode(member.substring(delimiterIndex + 1))
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid aggregate id index member:$member", error)
        }
        return namedAggregate.aggregateId(id, tenantId)
    }

    fun indexMemberPrefix(id: String): String = "${encodeSortableId(id)}$TUPLE_DELIMITER"

    fun indexMemberAfterId(id: String): String = "${encodeSortableId(id)}$INDEX_AFTER_ID_DELIMITER"

    private fun encodeSortableId(id: String): String {
        require(id.isNotEmpty()) { "Aggregate id must not be empty." }
        RedisKeyComponentCodec.validateUnicode(id)
        return buildString(id.length * HEX_CHARS_PER_CODE_UNIT) {
            id.forEach { codeUnit ->
                append(codeUnit.code.toString(HEX_RADIX).padStart(HEX_CHARS_PER_CODE_UNIT, '0'))
            }
        }
    }

    private fun decodeSortableId(token: String, member: String): String {
        require(token.isNotEmpty() && token.length % HEX_CHARS_PER_CODE_UNIT == 0) {
            "Invalid aggregate id index member:$member"
        }
        val decoded = buildString(token.length / HEX_CHARS_PER_CODE_UNIT) {
            token.chunked(HEX_CHARS_PER_CODE_UNIT).forEach { codeUnit ->
                require(codeUnit.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "Invalid aggregate id index member:$member"
                }
                append(codeUnit.toInt(HEX_RADIX).toChar())
            }
        }
        try {
            RedisKeyComponentCodec.validateUnicode(decoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid aggregate id index member:$member", error)
        }
        return decoded
    }
}
