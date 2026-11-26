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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.spi.log.Partitioner
import java.security.SecureRandom
import java.util.UUID
import java.util.random.RandomGenerator
import java.util.random.RandomGeneratorFactory

/**
 * Generates the message-level ids of a node: command ids, the request id of a request that names
 * none, wait ids, and [Ids.next] of domain code. UUIDv7 by default, or the application's own; wow-cosid gives
 * aggregate ids alone ([AggregateIdGenerator]), never these, since request ids from several services meet in one
 * context's idempotency key and a CosId id is unique only within its namespace.
 */
@WowSpi
fun interface GlobalIdGenerator {
    fun next(): String

    companion object {
        /** UUIDs of version 7 (RFC 9562): time-ordered by the millisecond, then random. */
        @JvmField
        val UUID_V7: GlobalIdGenerator = UuidV7Generator(System::currentTimeMillis)
    }
}

/**
 * Generates the id of a new aggregate of a type: the id admission gives a creating command that names
 * none, the target of a transformed command left without one, and [Ids.next] of a type in domain code.
 * One generator may serve every type or one type; a host selects one per type
 * ([AggregateIdGenerator.of]).
 */
@WowSpi
fun interface AggregateIdGenerator {
    fun next(type: AggregateType): String

    /**
     * A new id of [type] whose aggregate in [tenantId], `type.ref(tenantId, id)`, falls in [partition] of a log of
     * [partitions] ([Partitioner.partitionOf]), or null when this generator cannot aim at a partition. Admission asks
     * it for a creation that names no id, aimed at its request's partition ([Partitioner.partitionOfRequest]): every
     * attempt of the request then meets the others at the log's request key, and nothing is claimed. Given null, it
     * claims the id [next] gives for the request instead ([me.ahoo.wow.spi.log.EventLog.claimCreation]): a round trip
     * more on a log whose request key is narrower than the type.
     *
     * A generator aims on every call or on none: one that gave an id to one attempt of a request and null to another
     * would have the first aimed and the second claimed, and the two would not meet, so the request could commit
     * twice. The default is null, the safe side: a generator, or a wrapper of one, that does not implement it is
     * claimed, slower but never wrong (the opposite of `claimCreation`, which has no default so that a wrapper cannot
     * drop the claims it does not forward). Wow's UUIDv7 ids aim ([UUID_V7]); [of] forwards it per type.
     *
     * It holds while the partition count does not change: an online split that doubles it must aim at the request's
     * slot first, since two attempts aimed before and after the split agree only modulo the older count.
     */
    fun nextIn(type: AggregateType, tenantId: String, partition: Int, partitions: Int): String? = null

    companion object {
        /** A UUIDv7 for every type, as [GlobalIdGenerator.UUID_V7] gives, aimed at a partition when asked. */
        @JvmField
        val UUID_V7: AggregateIdGenerator = UuidV7Aggregates(GlobalIdGenerator.UUID_V7 as UuidV7Generator)

        /**
         * [global]'s ids, whatever the type: its [GlobalIdGenerator.next], and no aim. Wow's own UUIDv7 generator
         * gives its aggregate side, which aims; an application's class that is both generators is still asked for
         * [GlobalIdGenerator.next] alone.
         */
        @JvmStatic
        fun of(global: GlobalIdGenerator): AggregateIdGenerator = when {
            global === GlobalIdGenerator.UUID_V7 -> UUID_V7
            global is UuidV7Generator -> UuidV7Aggregates(global)
            else -> AggregateIdGenerator { global.next() }
        }

        /** The generator [byType] gives a type, [default] for the others; [default] itself when [byType] is empty. */
        @JvmStatic
        fun of(default: AggregateIdGenerator, byType: Map<AggregateType, AggregateIdGenerator>): AggregateIdGenerator {
            if (byType.isEmpty()) {
                return default
            }
            val generators = byType.toMap()
            return object : AggregateIdGenerator {
                override fun next(type: AggregateType): String = (generators[type] ?: default).next(type)

                override fun nextIn(type: AggregateType, tenantId: String, partition: Int, partitions: Int): String? =
                    (generators[type] ?: default).nextIn(type, tenantId, partition, partitions)
            }
        }
    }
}

/**
 * Generators a host finds by a name no generator bean has, such as CosId's by its generators' names:
 * `wow.aggregates.[<context>.<aggregate>].id-generator` names one of these when it names no [AggregateIdGenerator].
 */
@WowSpi
fun interface AggregateIdGeneratorSource {
    /** The generator named [name], or null when there is none. */
    fun find(name: String): AggregateIdGenerator?
}

/**
 * A node's two generators, the [Ids] its decide functions and sagas take.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
@WowSpi
class IdGenerators @InternalWowApi constructor(
    val global: GlobalIdGenerator = GlobalIdGenerator.UUID_V7,
    val aggregates: AggregateIdGenerator = AggregateIdGenerator.UUID_V7,
) : Ids {
    override fun next(): String = global.next()

    override fun next(type: AggregateType): String = aggregates.next(type)

    override fun equals(other: Any?): Boolean =
        this === other || other is IdGenerators && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IdGenerators(global=$global, aggregates=$aggregates)"

    private fun valueFields(): List<Any?> = listOf(global, aggregates)

    companion object {
        /** UUIDv7 for both. */
        @JvmField
        val DEFAULT: IdGenerators = IdGenerators()

        /** An IdGenerators of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            global: GlobalIdGenerator = GlobalIdGenerator.UUID_V7,
            aggregates: AggregateIdGenerator = AggregateIdGenerator.UUID_V7,
        ): IdGenerators = IdGenerators(global, aggregates)
    }
}

/**
 * Version 7 UUIDs (RFC 9562 §5.7): 48 bits of Unix epoch milliseconds from [clock], the version, 12 random bits, the
 * variant and 62 random bits. Its aggregate side, [UuidV7Aggregates], aims an id at a partition ([aimed]).
 *
 * The random bits come from the calling thread's own `L64X256MixRandom` by default ([SeededRandoms]), seeded once per
 * thread from `SecureRandom`. The ids must be unique, request ids most of all: they are the idempotency key a
 * context's log meets from every service, and two equal ones answer a command as another's retry. Uniqueness needs
 * a seed independent of when the JVM started and more state than 64 bits, which `ThreadLocalRandom`, seeded from
 * the start time with 64 bits of state, does not have. The ids are not secrets, so no draw takes `SecureRandom`
 * itself: a shared one would serialise every command's ids, some taken on the server's event loops, on one lock.
 */
internal class UuidV7Generator(
    private val clock: () -> Long,
    private val random: () -> RandomGenerator = SeededRandoms::current,
) : GlobalIdGenerator {
    override fun next(): String {
        val random = random()
        val randomA = random.nextInt() and RAND_A_MASK
        val msb = (clock() shl TIMESTAMP_SHIFT) or VERSION or randomA.toLong()
        val lsb = (random.nextLong() and RAND_B_MASK) or VARIANT
        return UUID(msb, lsb).toString()
    }

    /**
     * An id whose aggregate of [type] in [tenantId] falls in [partition] of [partitions], with nothing drawn twice: the
     * timestamp and the random bits are drawn once, the partition key up to the id's last four hexadecimal digits is
     * hashed once, and those four digits, the low 16 bits of the random `rand_b`, are the first whose hash falls there.
     * A slot is the hash's low 12 bits, which depend only on the low bits before them ([Partitioner]), and from every
     * state the four digits reach all of the 4,096 slots, so the first round always finds one (`IdGeneratorsTest`).
     * The id is a UUIDv7 still: its version and variant are kept, and 58 of its 74 random bits are random.
     */
    fun aimed(type: AggregateType, tenantId: String, partition: Int, partitions: Int): String {
        val mask = Partitioner.requirePartitions(partitions) - 1
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        val random = random()
        val msb = (clock() shl TIMESTAMP_SHIFT) or VERSION or (random.nextInt() and RAND_A_MASK).toLong()
        val lsb = (random.nextLong() and RAND_B_MASK and AIMED.inv()) or VARIANT
        // The key `context.aggregate/tenant/`, then the id as UUID.toString spells it but for its last four digits.
        var key = Partitioner.hash(Partitioner.EMPTY, type.context)
        key = Partitioner.hash(Partitioner.hash(key, '.'.code), type.name)
        key = Partitioner.hash(Partitioner.hash(key, '/'.code), tenantId)
        key = digits(Partitioner.hash(key, '/'.code), msb ushr 32, 8)
        key = digits(Partitioner.hash(key, '-'.code), msb ushr 16, 4)
        key = digits(Partitioner.hash(key, '-'.code), msb, 4)
        key = digits(Partitioner.hash(key, '-'.code), lsb ushr 48, 4)
        key = digits(Partitioner.hash(key, '-'.code), lsb ushr 16, 8)
        // The last four digits, the first one changing slowest: each hash is the one before it and one more digit.
        var first = 0L
        var second = 0L
        var third = 0L
        for (low in 0 until AIMED_VALUES) {
            if ((low and 0xFFF) == 0) first = digit(key, low ushr 12)
            if ((low and 0xFF) == 0) second = digit(first, low ushr 8)
            if ((low and 0xF) == 0) third = digit(second, low ushr 4)
            if ((digit(third, low).toInt() and mask) == partition) {
                return UUID(msb, lsb or low.toLong()).toString()
            }
        }
        error("No id of $type in $tenantId falls in partition $partition of $partitions: the hash is not FNV-1a.")
    }

    private companion object {
        /** The bits [aimed] chooses: the low 16 of `rand_b`, the id's last four hexadecimal digits. */
        const val AIMED = 0xFFFFL
        const val AIMED_VALUES = 0x10000
        const val HEX = "0123456789abcdef"

        /** [hash] continued over the [count] low hexadecimal digits of [value], the highest first. */
        fun digits(hash: Long, value: Long, count: Int): Long {
            var continued = hash
            for (shift in (count - 1) * 4 downTo 0 step 4) {
                continued = digit(continued, (value ushr shift).toInt())
            }
            return continued
        }

        /** [hash] continued over the hexadecimal digit of the low four bits of [value]. */
        fun digit(hash: Long, value: Int): Long = Partitioner.hash(hash, HEX[value and 0xF].code)

        const val TIMESTAMP_SHIFT = 16
        const val VERSION = 0x7000L
        const val RAND_A_MASK = 0x0FFF
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT = Long.MIN_VALUE
    }
}

/**
 * The ids of [global] for every aggregate type, aimed at a partition when asked ([UuidV7Generator.aimed]). A class of
 * its own, made by [AggregateIdGenerator]'s companion: [GlobalIdGenerator.UUID_V7], made first, never initialises
 * [AggregateIdGenerator], whose default member initialises it with the first class that implements it.
 */
internal class UuidV7Aggregates(private val global: UuidV7Generator) : AggregateIdGenerator {
    override fun next(type: AggregateType): String = global.next()

    override fun nextIn(type: AggregateType, tenantId: String, partition: Int, partitions: Int): String =
        global.aimed(type, tenantId, partition, partitions)
}

/**
 * One `L64X256MixRandom` per thread (320 bits of state), seeded on the thread's first draw from a shared
 * `SecureRandom`: the one draw that takes its lock. A virtual thread is a thread, so it seeds its own on its first id.
 */
internal object SeededRandoms {
    private const val ALGORITHM = "L64X256MixRandom"

    /** The seed's length: the generator's state, 64 + 256 bits. */
    private const val SEED_BYTES = 40

    private val seeds = SecureRandom()

    private val randoms: ThreadLocal<RandomGenerator> = ThreadLocal.withInitial {
        val seed = ByteArray(SEED_BYTES).also(seeds::nextBytes)
        RandomGeneratorFactory.of<RandomGenerator>(ALGORITHM).create(seed)
    }

    /** The calling thread's generator. */
    fun current(): RandomGenerator = randoms.get()
}
