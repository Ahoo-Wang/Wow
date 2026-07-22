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

package me.ahoo.wow.redis

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object RedisKeyComponentCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: String): String {
        validateUnicode(value)
        return encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(value: String): String {
        require(value.all(::isBase64UrlCharacter)) { "Invalid Base64URL component:$value" }
        val bytes = try {
            decoder.decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64URL component:$value", error)
        }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 component:$value", error)
        }
        require(encode(decoded) == value) { "Non-canonical Base64URL component:$value" }
        return decoded
    }

    fun validateUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "String contains an unpaired surrogate at index $index."
                    }
                    index += 2
                }

                current.isLowSurrogate() -> throw IllegalArgumentException(
                    "String contains an unpaired surrogate at index $index."
                )

                else -> index++
            }
        }
    }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_'
}
