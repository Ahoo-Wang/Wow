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

package me.ahoo.wow.runtime.cluster

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.log.Partitioner

/**
 * Highest random weight (rendezvous) assignment of partitions to nodes: `owner(p) = argmax
 * weight(node, p)`. Every node computes the same assignment from the same member list without a leader, and a change
 * of members moves only the partitions the joining node wins or the leaving node held, about P/N of them.
 *
 * The weight is part of the cluster protocol, so nodes of adjacent versions agree: the 64-bit FNV-1a hash the
 * log partitions by ([Partitioner.hash]) of `node/partition`, finished with MurmurHash3's 64-bit mixer so that
 * keys differing only in their last characters still spread over all bits. Weights compare as unsigned numbers; a
 * tie goes to the smaller node id.
 */
@InternalWowApi
object Hrw {
    private const val MIX_1 = -0xae502812aa7333L // 0xff51afd7ed558ccd
    private const val MIX_2 = -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
    private const val SHIFT = 33

    /** The weight of [node] for [partition]. */
    fun weight(node: String, partition: Int): Long = mix(Partitioner.hash("$node/$partition"))

    /** The owner of [partition] among [nodes], or null when there is none. */
    fun owner(partition: Int, nodes: Collection<String>): String? {
        var best: String? = null
        var bestWeight = 0L
        for (node in nodes) {
            val weight = weight(node, partition)
            val order = if (best == null) 1 else java.lang.Long.compareUnsigned(weight, bestWeight)
            if (order > 0 || (order == 0 && node < best!!)) {
                best = node
                bestWeight = weight
            }
        }
        return best
    }

    /** The partitions among [partitions] that [node] owns when [nodes] are the members. */
    fun partitionsOf(node: String, nodes: Collection<String>, partitions: Int): Set<Int> =
        (0 until partitions).filterTo(LinkedHashSet()) { owner(it, nodes) == node }

    /** The owner of every partition among [partitions] when [nodes] are the members. */
    fun assignment(nodes: Collection<String>, partitions: Int): Map<Int, String> =
        (0 until partitions).mapNotNull { partition -> owner(partition, nodes)?.let { partition to it } }.toMap()

    /** MurmurHash3's fmix64. */
    private fun mix(hash: Long): Long {
        var h = hash
        h = h xor (h ushr SHIFT)
        h *= MIX_1
        h = h xor (h ushr SHIFT)
        h *= MIX_2
        h = h xor (h ushr SHIFT)
        return h
    }
}
