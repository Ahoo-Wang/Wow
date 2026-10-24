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

package me.ahoo.wow.mongo

import me.ahoo.test.asserts.assert
import org.bson.BsonDocument
import org.bson.RawBsonDocument
import org.bson.codecs.BsonDocumentCodec
import org.bson.codecs.DocumentCodec
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException

class ExactBsonTest {
    /** The bytes the driver writes for [document]. */
    private fun bytesOf(document: BsonDocument) = RawBsonDocument(document, BsonDocumentCodec()).byteBuffer.array()

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{}""",
            """{"a":1,"b":"x","c":true,"d":false,"e":null}""",
            """{"nested":{"list":[1,[2,3],{"k":"v"},[],{}],"empty":{}}}""",
            """{"int":2147483647,"long":2147483648,"negative":-9223372036854775808}""",
            """{"big":123456789012345678901234567890,"bigNegative":-99999999999999999999}""",
            """{"scale":2.50,"zero":0.0,"exponent":1e3,"small":-1.5E-7,"digits":1.234567890123456789012345678901234}""",
            """{"unicode":"é中文\uD83D\uDE00","escaped":"a\"b\\c\n"}""",
            """{"twice":1,"other":2,"twice":3}""",
        ],
    )
    fun `the streamed BSON is the one the tree gives, byte for byte`(text: String) {
        val streamed = ExactBson.bson(text)
        val tree = RawBsonDocument(ExactBson.document(text), DocumentCodec())
        bytesOf(streamed).assert().isEqualTo(tree.byteBuffer.array())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"decimal":1.234567890123456789012345678901234567890}""",
            """{"integer":12345678901234567890123456789012345678}""",
            """{"exponent":1e9999}""",
            """{"a\u0000b":1}""",
            """{"nested":{"a\u0000":1}}""",
            """[1,2]""",
            """"text"""",
            """{"a":1} {"b":2}""",
        ],
    )
    fun `what the tree refuses, the stream refuses`(text: String) {
        val tree = runCatching { ExactBson.document(text) }.exceptionOrNull()
        val streamed = runCatching { ExactBson.bson(text) }.exceptionOrNull()
        tree.assert().describedAs("the tree's refusal of $text").isNotNull()
        streamed.assert().describedAs("the stream's refusal of $text").isNotNull()
        // Both a value refused, or both a malformed document.
        (streamed is IllegalArgumentException).assert().isEqualTo(tree is IllegalArgumentException)
        (streamed is JacksonException).assert().isEqualTo(tree is JacksonException)
    }
}
