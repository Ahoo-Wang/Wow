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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi

/**
 * Assigns an aggregate to a partition. The hash is part of the storage format: it is fixed across JVMs, processes and
 * versions (64-bit over `context.aggregate/tenant/id`, never `String.hashCode`), so every node and every
 * release agrees, and changing it means repartitioning the log.
 *
 * **Slots**. An aggregate hashes first to one of [SLOTS] fixed slots, `slot = floorMod(hash, 4096)`,
 * which never changes; its partition is `slot mod P`. P must be a power of two that divides [SLOTS] (1, 2, 4, …,
 * 4096, [isValidPartitions]): a log, a command topic or a host configured with another count fails at start. Since P
 * divides 4096, `floorMod(hash, 4096) mod P = floorMod(hash, P)`, so the slots assign every aggregate to the partition
 * it had before they existed: logs, checkpoints and Kafka command partitions are unaffected. They are there for the
 * online splits of 10.x, which double P: partition p splits into p and p + P by the next bit of the slot, so an
 * aggregate only ever moves from its partition to that partition's own child, and history stays where it is.
 *
 * **Distribution.** A slot is the low 12 bits of the hash, and FNV-1a's low bits depend only on the low bits of each
 * byte (bit k of the hash on bits 0..k of the bytes): the 12 bits of a slot see every bit of every byte, but a
 * partition of a small P sees only the low bits of each character, so keys that differ only in a character's high
 * bits (`a` and `A` differ in bit 5) share a partition when P is at most 32. Over keys as ids are made (sequences,
 * UUIDs, snowflakes) the slots and partitions stay uniform; [PartitionerTest] checks it. A mixer would fix the low
 * bits, but it would change every assignment: a new format, not this one.
 */
@WowSpi
object Partitioner {
    /** The fixed number of slots aggregates hash to; a valid partition count divides it. */
    const val SLOTS: Int = 4096

    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val PRIME = 0x100000001b3L

    /** The partition of [ref] among [partitions]: its [slotOf] modulo [partitions], a valid count ([requirePartitions]). */
    fun partitionOf(ref: AggregateRef, partitions: Int): Int = slotOf(ref) % requirePartitions(partitions)

    /** The slot of [ref], in `0 until` [SLOTS]: fixed whatever the partition count. */
    fun slotOf(ref: AggregateRef): Int = Math.floorMod(hash(key(ref)), SLOTS)

    /**
     * The partition of the request [requestId] within [tenantId] in aggregate type [type], among [partitions]: the slot
     * of `context.aggregate/tenant?request` modulo [partitions], a function of these alone. A creation that names no
     * id takes one in its request's partition where its type's generator can aim there
     * ([me.ahoo.wow.spi.id.AggregateIdGenerator.nextIn]), so that every attempt of the request meets the others at the
     * log's request key. Nothing stores it, but it is fixed across nodes and versions as the aggregates' partition is:
     * during a rolling upgrade the nodes of two versions must give one request one partition. Request ids spread over
     * the partitions as ids do (the distribution above): with at most 32 partitions, ids that differ only in the high
     * bits of a character share one, and with them the aggregates their creations make.
     *
     * It holds while the partition count does not change: an online split that doubles it must aim at the request's
     * slot first, since two attempts taken before and after the split agree only modulo the older count.
     */
    fun partitionOfRequest(type: AggregateType, tenantId: String, requestId: String, partitions: Int): Int {
        var hash = hash(OFFSET_BASIS, type.context)
        hash = hash(hash, '.'.code)
        hash = hash(hash, type.name)
        hash = hash(hash, '/'.code)
        hash = hash(hash, tenantId)
        hash = hash(hash, '?'.code)
        return Math.floorMod(hash(hash, requestId), SLOTS) % requirePartitions(partitions)
    }

    /** The hash an empty text starts from: [hash] continues from it ([hash] of a seed). */
    internal const val EMPTY: Long = OFFSET_BASIS

    /** Whether [partitions] is a valid partition count: a power of two that divides [SLOTS]. */
    fun isValidPartitions(partitions: Int): Boolean =
        partitions in 1..SLOTS && partitions and (partitions - 1) == 0

    /**
     * Returns [partitions] when it is a valid partition count ([isValidPartitions]); fails with an
     * [IllegalArgumentException] that says why otherwise. Every log, command topic and host configuration checks its
     * partition count with it at start.
     */
    fun requirePartitions(partitions: Int): Int {
        require(isValidPartitions(partitions)) {
            "A log has $partitions partitions, but a partition count must be a power of two that divides $SLOTS " +
                "(1, 2, 4, …, $SLOTS): partitions are the hash slots modulo the count, so that they " +
                "split online by doubling. Choose one such as 64, the default."
        }
        return partitions
    }

    /**
     * The text the partition is hashed from; part of the format, and deliberately independent of
     * [AggregateRef.toString], which escapes its separators: this key keeps them raw, so an aggregate whose tenant
     * or id holds a `/` or a `%` stays in the partition its stored streams are in. That two refs may share a key only
     * puts them in one partition.
     */
    fun key(ref: AggregateRef): String = "${ref.type.context}.${ref.type.name}/${ref.tenantId}/${ref.id}"

    /** 64-bit FNV-1a over the UTF-8 bytes of [text]. */
    fun hash(text: String): Long {
        var hash = OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= PRIME
        }
        return hash
    }

    /**
     * [hash] continued from [seed] over the UTF-8 bytes of [text], encoded in place, with nothing allocated:
     * `hash(EMPTY, a)` is [hash] of `a`, and continued over a separator byte and `b`, it is [hash] of `a/b`. A lone
     * surrogate is encoded as `encodeToByteArray` encodes it, `?`, so a text that ends in a high surrogate, continued
     * directly by one that starts with a low surrogate, is not the hash of the two joined: a key's parts are separated.
     */
    internal fun hash(seed: Long, text: String): Long {
        var hash = seed
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            val code = char.code
            when {
                code < 0x80 -> hash = hash(hash, code)
                code < 0x800 -> {
                    hash = hash(hash, 0xC0 or (code shr 6))
                    hash = hash(hash, 0x80 or (code and 0x3F))
                }
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    val point = Character.toCodePoint(char, text[index++])
                    hash = hash(hash, 0xF0 or (point shr 18))
                    hash = hash(hash, 0x80 or ((point shr 12) and 0x3F))
                    hash = hash(hash, 0x80 or ((point shr 6) and 0x3F))
                    hash = hash(hash, 0x80 or (point and 0x3F))
                }
                char.isSurrogate() -> hash = hash(hash, '?'.code)
                else -> {
                    hash = hash(hash, 0xE0 or (code shr 12))
                    hash = hash(hash, 0x80 or ((code shr 6) and 0x3F))
                    hash = hash(hash, 0x80 or (code and 0x3F))
                }
            }
        }
        return hash
    }

    /** [hash] continued from [seed] over one byte, [byte] (`0 until 256`). */
    internal fun hash(seed: Long, byte: Int): Long = (seed xor byte.toLong()) * PRIME
}
