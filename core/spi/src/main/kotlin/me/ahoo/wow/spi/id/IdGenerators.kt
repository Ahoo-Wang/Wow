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

package me.ahoo.wow.spi.id

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.id.Ids
import java.security.SecureRandom
import java.util.UUID

/**
 * Generates the message-level ids of a node (decision D171): command ids, the request id of a request that names
 * none, wait ids, and [Ids.next] of domain code. UUIDv7 by default; wow-cosid adapts CosId.
 */
fun interface GlobalIdGenerator {
    fun next(): String

    companion object {
        /** UUIDs of version 7 (RFC 9562): time-ordered by the millisecond, then random. */
        @JvmField
        val UUID_V7: GlobalIdGenerator = UuidV7Generator(System::currentTimeMillis)
    }
}

/**
 * Generates the id of a new aggregate of a type (decision D171): the id admission gives a creating command that names
 * none, the target of a transformed command left without one (decision D182), and [Ids.next] of a type in domain code.
 * One generator may serve every type or one type; a host selects one per type
 * ([AggregateIdGenerator.of]).
 */
fun interface AggregateIdGenerator {
    fun next(type: AggregateType): String

    companion object {
        /** A UUIDv7 for every type, as [GlobalIdGenerator.UUID_V7] gives. */
        @JvmField
        val UUID_V7: AggregateIdGenerator = of(GlobalIdGenerator.UUID_V7)

        /** [global]'s ids, whatever the type. */
        @JvmStatic
        fun of(global: GlobalIdGenerator): AggregateIdGenerator = AggregateIdGenerator { global.next() }

        /** The generator [byType] gives a type, [default] for the others; [default] itself when [byType] is empty. */
        @JvmStatic
        fun of(default: AggregateIdGenerator, byType: Map<AggregateType, AggregateIdGenerator>): AggregateIdGenerator {
            if (byType.isEmpty()) {
                return default
            }
            val generators = byType.toMap()
            return AggregateIdGenerator { type -> (generators[type] ?: default).next(type) }
        }
    }
}

/**
 * Generators a host finds by a name no generator bean has (decision D171), such as CosId's by its provider's names:
 * `wow.aggregates.[<context>.<aggregate>].id-generator` names one of these when it names no [AggregateIdGenerator].
 */
fun interface AggregateIdGeneratorSource {
    /** The generator named [name], or null when there is none. */
    fun find(name: String): AggregateIdGenerator?
}

/** A node's two generators (decision D171), the [Ids] its decide functions and sagas take. */
data class IdGenerators(
    val global: GlobalIdGenerator = GlobalIdGenerator.UUID_V7,
    val aggregates: AggregateIdGenerator = AggregateIdGenerator.UUID_V7,
) : Ids {
    override fun next(): String = global.next()

    override fun next(type: AggregateType): String = aggregates.next(type)

    companion object {
        /** UUIDv7 for both. */
        @JvmField
        val DEFAULT: IdGenerators = IdGenerators()
    }
}

/**
 * Version 7 UUIDs (RFC 9562 §5.7): 48 bits of Unix epoch milliseconds from [clock], the version, 12 random bits, the
 * variant and 62 random bits.
 */
internal class UuidV7Generator(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) :
    GlobalIdGenerator {
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
