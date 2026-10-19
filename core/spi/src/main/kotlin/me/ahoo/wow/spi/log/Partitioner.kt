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

/**
 * Assigns an aggregate to a partition. The hash is part of the storage format: it is fixed across JVMs, processes and
 * versions (64-bit FNV-1a over `context.aggregate/tenant/id`, never `String.hashCode`), so every node and every
 * release agrees, and changing it means repartitioning the log.
 *
 * **Slots** (decision D240). An aggregate hashes first to one of [SLOTS] fixed slots, `slot = floorMod(hash, 4096)`,
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
object Partitioner {
    /** The fixed number of slots aggregates hash to (decision D240); a valid partition count divides it. */
    const val SLOTS: Int = 4096

    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val PRIME = 0x100000001b3L

    /** The partition of [ref] among [partitions]: its [slotOf] modulo [partitions], a valid count ([requirePartitions]). */
    fun partitionOf(ref: AggregateRef, partitions: Int): Int = slotOf(ref) % requirePartitions(partitions)

    /** The slot of [ref], in `0 until` [SLOTS]: fixed whatever the partition count (decision D240). */
    fun slotOf(ref: AggregateRef): Int = Math.floorMod(hash(key(ref)), SLOTS)

    /** Whether [partitions] is a valid partition count: a power of two that divides [SLOTS] (decision D240). */
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
                "(1, 2, 4, …, $SLOTS; decision D240): partitions are the hash slots modulo the count, so that they " +
                "split online by doubling. Choose one such as 64, the default."
        }
        return partitions
    }

    /** The text the partition is hashed from; part of the format, independent of [AggregateRef.toString]. */
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
}
