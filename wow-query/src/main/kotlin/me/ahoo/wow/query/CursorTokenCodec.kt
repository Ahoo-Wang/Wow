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

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CursorTokenCodec private constructor(
    key: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val key = SecretKeySpec(key, "AES")

    fun encode(payload: ByteArray): String {
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(byteArrayOf(VERSION))
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(VERSION) + nonce + cipher.doFinal(payload))
    }

    fun decode(cursor: String): ByteArray = try {
        val token = Base64.getUrlDecoder().decode(cursor)
        require(token.size >= MIN_TOKEN_SIZE && token[0] == VERSION)
        val nonce = token.copyOfRange(1, 1 + NONCE_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(byteArrayOf(token[0]))
        }
        cipher.doFinal(token, 1 + NONCE_SIZE, token.size - 1 - NONCE_SIZE)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE_BITS = 128
        private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / Byte.SIZE_BITS
        private const val MIN_TOKEN_SIZE = 1 + NONCE_SIZE + TAG_SIZE_BYTES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val INVALID_KEY = "Cursor encryption key must be a Base64URL-encoded 32-byte key."

        fun fromBase64Url(encodedKey: String): CursorTokenCodec = try {
            Base64.getUrlDecoder().decode(encodedKey).let { key ->
                require(key.size == KEY_SIZE)
                CursorTokenCodec(key)
            }
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_KEY)
        }
    }
}
