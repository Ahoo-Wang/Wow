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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.schema.QueryModel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/** The cursor token shell the core owns and the JSON position codec (9.x covered them in `BackendQueriesTest`). */
class CursorPositionsTest {
    private val aggregate = AggregateType("context", "aggregate")
    private val schema = gatewaySchema()
    private val sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))
    private val codec = CursorPositionCodec.JSON

    @Test
    fun `a position is a value of its native values`() {
        val values = mutableListOf<Any?>("a", 1L)
        val position = CursorPosition(values)
        values += "changed"
        position.values.assert().containsExactly("a", 1L)
        position.assert().isEqualTo(CursorPosition(listOf("a", 1L)))
        position.hashCode().assert().isEqualTo(CursorPosition(listOf("a", 1L)).hashCode())
        position.assert().isNotEqualTo(CursorPosition(listOf("a")))
        position.equals("a").assert().isFalse()
        position.toString().assert().isEqualTo("CursorPosition([a, 1])")
        assertThrows<UnsupportedOperationException> { (position.values as MutableList<Any?>).add("x") }
    }

    @Test
    fun `the JSON codec round-trips every JSON scalar`() {
        val position = CursorPosition(listOf(null, true, "text", 7L, 2.5))
        codec.decode(codec.encode(position), 5).assert().isEqualTo(position)
        // An Int is written as a JSON integer and read back as a long.
        codec.decode(codec.encode(CursorPosition(listOf(7))), 1).assert().isEqualTo(CursorPosition(listOf(7L)))
    }

    @Test
    fun `the JSON codec refuses what it did not produce`() {
        listOf(CursorPosition(listOf(Any())), CursorPosition(listOf(Double.NaN)), CursorPosition(listOf(1.5f)))
            .forEach { position -> assertThrows<IllegalArgumentException> { codec.encode(position) } }
        assertThrows<IllegalArgumentException> { codec.decode(codec.encode(CursorPosition(listOf("a"))), 2) }
        assertThrows<IllegalArgumentException> { codec.decode("""{"a":1}""".toByteArray(), 1) }
        assertThrows<IllegalArgumentException> { codec.decode("""[{"a":1}]""".toByteArray(), 1) }
        assertThrows<IllegalArgumentException> { codec.decode("[18446744073709551616]".toByteArray(), 1) }
    }

    @Test
    fun `a token decodes for the same model and effective sort`() {
        val position = CursorPosition(listOf("aggregate-1"))
        val token = CursorTokens.encode(position, aggregate, schema, sort, codec)
        token.assert().doesNotContain("=", "+", "/")
        CursorTokens.decode(token, aggregate, schema, sort, codec).assert().isEqualTo(position)
    }

    @Test
    fun `a token for another aggregate, model, sort or in another version is the invalid cursor`() {
        val token = CursorTokens.encode(CursorPosition(listOf("aggregate-1")), aggregate, schema, sort, codec)
        val bytes = Base64.getUrlDecoder().decode(token)
        val otherVersion = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bytes.copyOf().also { it[0] = 1 })
        val tooShort = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf(17))
        listOf(
            { CursorTokens.decode(token, AggregateType("context", "other"), schema, sort, codec) },
            { CursorTokens.decode(token, AggregateType("other", "aggregate"), schema, sort, codec) },
            { CursorTokens.decode(token, aggregate, gatewaySchema(QueryModel("CUSTOM")), sort, codec) },
            {
                CursorTokens.decode(
                    token,
                    aggregate,
                    schema,
                    listOf(Sort(QueryField("aggregateId"), Sort.Direction.DESC)),
                    codec,
                )
            },
            {
                CursorTokens.decode(
                    token,
                    aggregate,
                    schema,
                    listOf(Sort(QueryField("tenantId"), Sort.Direction.ASC)) + sort,
                    codec,
                )
            },
            { CursorTokens.decode(token, aggregate, schema, emptyList(), codec) },
            { CursorTokens.decode(otherVersion, aggregate, schema, sort, codec) },
            { CursorTokens.decode(tooShort, aggregate, schema, sort, codec) },
            { CursorTokens.decode("not base64 !", aggregate, schema, sort, codec) },
        ).forEach { decode ->
            assertThrows<QueryRequestException> { decode() }.apply {
                code.assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
                message.assert().isEqualTo("Invalid cursor.")
            }
        }
    }

    @Test
    fun `a codec that answers a position of another size is the invalid cursor`() {
        val lying = object : CursorPositionCodec {
            override fun encode(position: CursorPosition): ByteArray = byteArrayOf(1)
            override fun decode(payload: ByteArray, size: Int): CursorPosition = CursorPosition(listOf(1, 2))
        }
        val token = CursorTokens.encode(CursorPosition(listOf("a")), aggregate, schema, sort, lying)
        assertThrows<QueryRequestException> { CursorTokens.decode(token, aggregate, schema, sort, lying) }
            .code.assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
    }

    @Test
    fun `a position the codec cannot encode is a server fault`() {
        assertThrows<QueryExecutionException> {
            CursorTokens.encode(CursorPosition(listOf(Any())), aggregate, schema, sort, codec)
        }.apply {
            message.assert().isEqualTo("Cursor position could not be encoded.")
            errorCode.assert().isEqualTo(QueryErrors.FAILED)
            cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
