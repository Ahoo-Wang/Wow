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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.id.Ids
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ids one handling by a saga takes (decision D171): name-based UUIDs (version 5, RFC 9562 §5.5) of [seed], what
 * identifies the handling, and the call's index within it, counted across [next] and [next] of a type. Handling the
 * same commit, or the same rejection, again (a redelivery after a crash, a handover, a parked entry resumed) takes the
 * same ids, so a derived command that creates an aggregate of a new id names the same aggregate every time. They are
 * not the node's generators': a saga's ids are name-based UUIDs whatever the deployment generates.
 */
class HandlingIds(private val seed: String) : Ids {
    init {
        require(seed.isNotBlank()) { "A handling's ids need a seed." }
    }

    private val calls = AtomicInteger()

    override fun next(): String = idOf(seed, null, calls.getAndIncrement())

    override fun next(type: AggregateType): String = idOf(seed, type, calls.getAndIncrement())

    companion object {
        /** The namespace of the saga ids: no other name-based UUID of Wow shares it. */
        private val NAMESPACE: UUID = UUID.fromString("6086f10d-62b6-40aa-96ce-22ca4a5608f4")
        private const val VERSION_BYTE = 6
        private const val VARIANT_BYTE = 8
        private const val UUID_BYTES = 16
        private const val LOW_NIBBLE = 0x0f
        private const val VERSION_5 = 0x50
        private const val LOW_SIX_BITS = 0x3f
        private const val VARIANT_RFC = 0x80
        private const val BITS_PER_BYTE = 8
        private const val HALF = 8

        /**
         * The [index]-th id (from 0) of the handling [seed]: of [type] when it is an aggregate id, a global one when
         * [type] is null. A version 5 UUID of `seed~type#index` (`seed~id#index` for a global one).
         */
        @JvmStatic
        fun idOf(seed: String, type: AggregateType?, index: Int): String {
            require(index >= 0) { "A call index is never negative." }
            return uuidV5("$seed~${type ?: "id"}#$index").toString()
        }

        private fun uuidV5(name: String): UUID {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(NAMESPACE.mostSignificantBits.toBytes())
            digest.update(NAMESPACE.leastSignificantBits.toBytes())
            val hash = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(UUID_BYTES)
            hash[VERSION_BYTE] = ((hash[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_5).toByte()
            hash[VARIANT_BYTE] = ((hash[VARIANT_BYTE].toInt() and LOW_SIX_BITS) or VARIANT_RFC).toByte()
            return UUID(hash.longAt(0), hash.longAt(HALF))
        }

        private fun Long.toBytes(): ByteArray =
            ByteArray(Long.SIZE_BYTES) { (this ushr (BITS_PER_BYTE * (Long.SIZE_BYTES - 1 - it))).toByte() }

        private fun ByteArray.longAt(offset: Int): Long = (0 until Long.SIZE_BYTES).fold(0L) { value, i ->
            (value shl BITS_PER_BYTE) or (this[offset + i].toLong() and 0xff)
        }
    }
}
