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

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class QueryCursorSigningKey(
    val id: Int,
    secret: ByteArray,
) {
    private val key = secret.copyOf().also { frozen ->
        require(frozen.size >= MINIMUM_SECRET_BYTES) { "Cursor HMAC secret must contain at least 256 bits." }
    }

    init {
        require(id in 1..UByte.MAX_VALUE.toInt()) { "Cursor signing key id must fit one non-zero byte." }
    }

    fun sign(payload: ByteArray): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(SecretKeySpec(key, HMAC_ALGORITHM))
        doFinal(payload)
    }

    private companion object {
        const val MINIMUM_SECRET_BYTES = 32
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

internal class QueryCursorSigningKeyRing(
    val current: QueryCursorSigningKey,
    previous: Iterable<QueryCursorSigningKey> = emptyList(),
) {
    private val keys: Map<Int, QueryCursorSigningKey>

    init {
        val all = listOf(current) + previous.toList()
        require(all.size <= MAX_SIGNING_KEYS) { "Cursor signing key ring exceeds its bounded key count." }
        require(all.map(QueryCursorSigningKey::id).distinct().size == all.size) {
            "Cursor signing key ids must be unique."
        }
        keys = Collections.unmodifiableMap(
            LinkedHashMap<Int, QueryCursorSigningKey>(all.size).also { copy ->
                all.forEach { key -> copy[key.id] = key }
            },
        )
    }

    fun resolve(id: Int): QueryCursorSigningKey? = keys[id]

    private companion object {
        const val MAX_SIGNING_KEYS = 4
    }
}

internal class QueryCursorTokenCodec(
    private val keyRing: QueryCursorSigningKeyRing,
    private val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {
    init {
        require(formatVersion in 1..UByte.MAX_VALUE.toInt())
    }

    fun encode(id: String, expiresAt: Instant): QueryCursorToken {
        val idBytes = try {
            URL_DECODER.decode(id)
        } catch (error: IllegalArgumentException) {
            rejectInvalidCursor(error)
        }
        if (idBytes.size != CURSOR_ID_BYTES) rejectInvalidCursor()
        val payload = ByteArrayOutputStream(PAYLOAD_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(formatVersion)
                output.writeByte(keyRing.current.id)
                output.write(idBytes)
                output.writeLong(expiresAt.epochSecond)
                output.writeInt(expiresAt.nano)
            }
            bytes.toByteArray()
        }
        val signature = keyRing.current.sign(payload)
        return QueryCursorToken("${URL_ENCODER.encodeToString(payload)}.${URL_ENCODER.encodeToString(signature)}")
    }

    fun decode(token: QueryCursorToken): TokenClaims = parseClaims(verifiedPayload(token))

    private fun verifiedPayload(token: QueryCursorToken): ByteArray {
        if (token.value.length > MAX_TOKEN_LENGTH) rejectInvalidCursor()
        val separator = token.value.indexOf('.')
        if (separator <= 0 || separator != token.value.lastIndexOf('.')) rejectInvalidCursor()
        val payload = decodePart(token.value.substring(0, separator))
        val signature = decodePart(token.value.substring(separator + 1))
        val signingKey = signingKey(payload)
        if (payload.size != PAYLOAD_BYTES || signature.size != HMAC_BYTES ||
            !MessageDigest.isEqual(signature, signingKey.sign(payload))
        ) {
            rejectInvalidCursor()
        }
        return payload
    }

    private fun signingKey(payload: ByteArray): QueryCursorSigningKey {
        if (payload.size != PAYLOAD_BYTES) rejectInvalidCursor()
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != formatVersion) rejectInvalidCursor()
                keyRing.resolve(input.readUnsignedByte()) ?: rejectInvalidCursor()
            }
        } catch (error: QueryRejectedException) {
            throw error
        } catch (error: IOException) {
            rejectInvalidCursor(error)
        }
    }

    private fun parseClaims(payload: ByteArray): TokenClaims = try {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != MAGIC || input.readUnsignedByte() != formatVersion) rejectInvalidCursor()
            if (keyRing.resolve(input.readUnsignedByte()) == null) rejectInvalidCursor()
            val idBytes = ByteArray(CURSOR_ID_BYTES)
            input.readFully(idBytes)
            val expiresAt = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
            if (input.available() != 0) rejectInvalidCursor()
            TokenClaims(URL_ENCODER.encodeToString(idBytes), expiresAt)
        }
    } catch (error: QueryRejectedException) {
        throw error
    } catch (error: DateTimeException) {
        rejectInvalidCursor(error)
    } catch (error: ArithmeticException) {
        rejectInvalidCursor(error)
    } catch (error: IOException) {
        rejectInvalidCursor(error)
    }

    private fun decodePart(value: String): ByteArray = try {
        URL_DECODER.decode(value)
    } catch (error: IllegalArgumentException) {
        rejectInvalidCursor(error)
    }

    data class TokenClaims(val id: String, val expiresAt: Instant)

    private companion object {
        const val MAGIC = 0x57514352
        const val CURRENT_FORMAT_VERSION = 1
        const val CURSOR_ID_BYTES = 32
        const val HMAC_BYTES = 32
        const val PAYLOAD_BYTES =
            Int.SIZE_BYTES + Byte.SIZE_BYTES + Byte.SIZE_BYTES + CURSOR_ID_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES
        const val MAX_TOKEN_LENGTH = 256
        val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
