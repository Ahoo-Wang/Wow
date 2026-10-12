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
 */
object Partitioner {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val PRIME = 0x100000001b3L

    /** The partition of [ref] among [partitions]. */
    fun partitionOf(ref: AggregateRef, partitions: Int): Int {
        require(partitions >= 1) { "There is at least one partition." }
        return Math.floorMod(hash(key(ref)), partitions)
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
