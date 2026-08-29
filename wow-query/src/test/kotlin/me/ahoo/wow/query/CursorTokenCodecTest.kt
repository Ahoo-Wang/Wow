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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class CursorTokenCodecTest {
    private val codec = CursorTokenCodec.fromBase64Url(encodedKey(1))

    @Test
    fun `cursor token should encrypt opaque payload with a fresh nonce`() {
        val rawValue = "masked-sort-value-should-never-appear"
        val payload = rawValue.toByteArray()

        val first = codec.encode(payload)
        val second = codec.encode(payload)

        codec.decode(first).contentEquals(payload).assert().isTrue()
        (first != second).assert().isTrue()
        first.contains(rawValue).assert().isFalse()
        Base64.getUrlDecoder().decode(first).toString(Charsets.ISO_8859_1)
            .contains(rawValue).assert().isFalse()
    }

    @Test
    fun `cursor token should reject malformed tampered wrong key short and unsupported tokens`() {
        val tokenBytes = Base64.getUrlDecoder().decode(codec.encode("payload".toByteArray()))
        val tampered = tokenBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val unsupported = tokenBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val wrongKeyCodec = CursorTokenCodec.fromBase64Url(encodedKey(2))
        val invalidTokens = listOf(
            "not-base64!",
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3)),
            Base64.getUrlEncoder().withoutPadding().encodeToString(tampered),
            Base64.getUrlEncoder().withoutPadding().encodeToString(unsupported),
        )

        invalidTokens.forEach { token ->
            assertThrows<IllegalArgumentException> { codec.decode(token) }
                .message.assert().isEqualTo("Invalid cursor.")
        }
        assertThrows<IllegalArgumentException> { wrongKeyCodec.decode(codec.encode("payload".toByteArray())) }
            .message.assert().isEqualTo("Invalid cursor.")
    }

    @Test
    fun `cursor encryption key should be Base64URL encoded and exactly 32 bytes`() {
        listOf(
            "not-base64!",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(33)),
        ).forEach { key ->
            assertThrows<IllegalArgumentException> { CursorTokenCodec.fromBase64Url(key) }
        }
    }

    private fun encodedKey(seed: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { index -> (seed + index).toByte() })
}
