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
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class ElasticsearchCursorCodecTest {
    @Test
    fun `cursor codec should round trip scalar field values without a key`() {
        val values = listOf(
            FieldValue.NULL,
            FieldValue.of(true),
            FieldValue.of("x"),
            FieldValue.of(1L),
            FieldValue.of(1.5),
        )

        val decoded = ElasticsearchCursorCodec.decode(ElasticsearchCursorCodec.encode(values), values.size)

        decoded.map(FieldValue::_kind).assert().containsExactly(
            FieldValue.Kind.Null,
            FieldValue.Kind.Boolean,
            FieldValue.Kind.String,
            FieldValue.Kind.Long,
            FieldValue.Kind.Double,
        )
        decoded[1].booleanValue().assert().isTrue()
        decoded[2].stringValue().assert().isEqualTo("x")
        decoded[3].longValue().assert().isEqualTo(1L)
        decoded[4].doubleValue().assert().isEqualTo(1.5)
    }

    @Test
    fun `cursor codec should reject malformed arity and non scalar values`() {
        assertInvalid { ElasticsearchCursorCodec.decode("not-base64", 1) }
        assertInvalid { ElasticsearchCursorCodec.decode(encoded("not-json"), 1) }
        assertInvalid { ElasticsearchCursorCodec.decode(encoded("{}"), 1) }
        assertInvalid { ElasticsearchCursorCodec.decode(encoded("[1]"), 2) }
        assertInvalid { ElasticsearchCursorCodec.decode(encoded("[{\"nested\":1}]"), 1) }
        assertInvalid {
            ElasticsearchCursorCodec.encode(listOf(FieldValue.of(JsonData.of(mapOf("nested" to 1)))))
        }
    }

    private fun encoded(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun assertInvalid(block: () -> Unit) {
        assertThrows<IllegalArgumentException>(block).message.assert().isEqualTo("Invalid cursor.")
    }
}
