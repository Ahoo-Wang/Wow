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

package me.ahoo.wow.runtime.admission

import java.security.SecureRandom
import java.util.UUID

/** Generates the ids admission gives commands and new aggregates (decision D114). */
fun interface IdGenerator {
    fun next(): String

    companion object {
        /** UUIDs of version 7 (RFC 9562): time-ordered by the millisecond, then random. */
        @JvmField
        val UUID_V7: IdGenerator = UuidV7Generator(System::currentTimeMillis)
    }
}

/**
 * Version 7 UUIDs (RFC 9562 §5.7): 48 bits of Unix epoch milliseconds from [clock], the version, 12 random bits, the
 * variant and 62 random bits.
 */
internal class UuidV7Generator(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) :
    IdGenerator {
    override fun next(): String {
        val randomA = random.nextInt() and RAND_A_MASK
        val msb = (clock() shl TIMESTAMP_SHIFT) or VERSION or randomA.toLong()
        val lsb = (random.nextLong() and RAND_B_MASK) or VARIANT
        return UUID(msb, lsb).toString()
    }

    private companion object {
        const val TIMESTAMP_SHIFT = 16
        const val VERSION = 0x7000L
        const val RAND_A_MASK = 0x0FFF
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT = Long.MIN_VALUE
    }
}
