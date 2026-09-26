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

package me.ahoo.wow.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/**
 * A record's position in a cursor sort: one native value per sort field, in sort order, exactly as the storage
 * orders them (BSON values for MongoDB, `hit.sort()` values for Elasticsearch). Only the backend that produced it
 * interprets the values.
 */
class CursorPosition(values: List<Any?>) {
    val values: List<Any?> = Collections.unmodifiableList(ArrayList(values))

    override fun equals(other: Any?): Boolean = other is CursorPosition && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "CursorPosition($values)"
}

/**
 * The backend half of a cursor token: native position values to bytes and back. [decode] throws on any payload it
 * did not produce for a sort of [size] fields; the core reports every such failure as the invalid cursor error.
 */
interface CursorPositionCodec {
    fun encode(position: CursorPosition): ByteArray

    fun decode(payload: ByteArray, size: Int): CursorPosition

    companion object {
        /** Positions of JSON scalars: `null`, booleans, strings, integral numbers as longs and finite doubles. */
        @JvmField
        val JSON: CursorPositionCodec = JsonCursorPositionCodec
    }
}

private object JsonCursorPositionCodec : CursorPositionCodec {
    override fun encode(position: CursorPosition): ByteArray {
        require(position.values.all { it.isJsonCursorScalar() }) { "Cursor values must be JSON scalars." }
        return JsonSerializer.writeValueAsBytes(position.values)
    }

    override fun decode(payload: ByteArray, size: Int): CursorPosition {
        val values = JsonSerializer.readTree(payload)
        require(values.isArray && values.size() == size)
        return CursorPosition(values.asSequence().map { it.toCursorScalar() }.toList())
    }

    private fun Any?.isJsonCursorScalar(): Boolean = when (this) {
        null, is Boolean, is String, is Int, is Long -> true
        is Double -> isFinite()
        else -> false
    }

    private fun JsonNode.toCursorScalar(): Any? = when {
        isNull -> null
        isBoolean -> booleanValue()
        isString -> asString()
        isIntegralNumber && canConvertToLong() -> longValue()
        isFloatingPointNumber -> doubleValue().also { require(it.isFinite()) }
        else -> throw IllegalArgumentException()
    }
}

/**
 * The core half of a cursor token (design §6.5): a version, the query fingerprint and the backend's payload, in
 * unpadded Base64URL. The fingerprint is the model (aggregate and read model) with the effective sort's canonical
 * field names and directions; it deliberately leaves out the filter and the schema version, so a caller that
 * recomputes a time bound per page, or a periodic schema refresh, does not invalidate a cursor. The token is not
 * signed: every page is admitted again and the keyset condition is ANDed with the full admitted filter.
 */
internal object CursorTokens {
    private const val VERSION: Byte = 2
    private const val FINGERPRINT_BYTES = 16
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    @Suppress("TooGenericExceptionCaught")
    fun encode(
        position: CursorPosition,
        aggregate: NamedAggregate,
        schema: QueryModelSchema,
        sort: List<Sort>,
        codec: CursorPositionCodec,
    ): String {
        val payload = try {
            codec.encode(position)
        } catch (error: Exception) {
            throw QueryExecutionException("Cursor position could not be encoded.", error)
        }
        val token = ByteBuffer.allocate(1 + FINGERPRINT_BYTES + payload.size)
            .put(VERSION)
            .put(fingerprint(aggregate, schema, sort))
            .put(payload)
            .array()
        return encoder.encodeToString(token)
    }

    /** Decodes a token issued for the same model and effective sort, or throws the invalid cursor error. */
    @Suppress("TooGenericExceptionCaught")
    fun decode(
        token: String,
        aggregate: NamedAggregate,
        schema: QueryModelSchema,
        sort: List<Sort>,
        codec: CursorPositionCodec,
    ): CursorPosition = try {
        require(sort.size in 1..AggregationQuery.MAX_SORT_FIELDS)
        val bytes = decoder.decode(token)
        require(bytes.size > 1 + FINGERPRINT_BYTES && bytes[0] == VERSION)
        require(
            MessageDigest.isEqual(bytes.copyOfRange(1, 1 + FINGERPRINT_BYTES), fingerprint(aggregate, schema, sort))
        )
        codec.decode(bytes.copyOfRange(1 + FINGERPRINT_BYTES, bytes.size), sort.size).also {
            require(it.values.size == sort.size)
        }
    } catch (_: Exception) {
        throw QueryViolation.InvalidCursor.rejection()
    }

    private fun fingerprint(aggregate: NamedAggregate, schema: QueryModelSchema, sort: List<Sort>): ByteArray {
        val canonical = buildString {
            append(aggregate.contextName).append('\n')
            append(aggregate.aggregateName).append('\n')
            append(schema.model.value).append('\n')
            sort.forEach { append(it.field.path).append(' ').append(it.direction.name).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .copyOf(FINGERPRINT_BYTES)
    }
}
