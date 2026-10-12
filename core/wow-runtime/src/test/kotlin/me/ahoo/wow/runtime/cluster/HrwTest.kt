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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The HRW assignment (decision D70) is part of the cluster protocol: every node and version computes the same. */
class HrwTest {
    private val three = listOf("node-1", "node-2", "node-3")

    @Test
    fun `weights are pinned across JVMs and versions`() {
        // Computed independently: FNV-1a 64 of "node/partition", then MurmurHash3's fmix64.
        Hrw.weight("node-1", 0).assert().isEqualTo(170397259082157690L)
        Hrw.weight("node-2", 0).assert().isEqualTo(2095258359307605711L)
        Hrw.weight("node-3", 7).assert().isEqualTo(4354308686818417113L)
        Hrw.weight("10.0.0.1:7070", 31).assert().isEqualTo(8480216236261337056L)
    }

    @Test
    fun `the assignment is pinned`() {
        (0 until 16).map { Hrw.owner(it, three)!!.removePrefix("node-") }.joinToString("")
            .assert().isEqualTo("3113322233121132")
    }

    @Test
    fun `the owner does not depend on the order of the members`() {
        (0 until 64).forEach { partition ->
            Hrw.owner(partition, three.reversed()).assert().isEqualTo(Hrw.owner(partition, three))
        }
        Hrw.owner(0, emptyList()).assert().isNull()
        Hrw.owner(5, listOf("only")).assert().isEqualTo("only")
    }

    @Test
    fun `a joining node takes only the partitions it wins, about P over N`() {
        val partitions = 256
        val before = Hrw.assignment(three, partitions)
        val after = Hrw.assignment(three + "node-4", partitions)
        val moved = (0 until partitions).filter { before[it] != after[it] }
        moved.forEach { after[it].assert().isEqualTo("node-4") }
        moved.toSet().assert().isEqualTo(Hrw.partitionsOf("node-4", three + "node-4", partitions))
        moved.size.assert().isBetween(partitions / 4 / 2, partitions / 4 * 3 / 2)
    }

    @Test
    fun `a leaving node's partitions spread over the others and nothing else moves`() {
        val partitions = 256
        val before = Hrw.assignment(three, partitions)
        val after = Hrw.assignment(three - "node-2", partitions)
        (0 until partitions).forEach {
            if (before[it] == "node-2") {
                after[it].assert().isNotEqualTo("node-2")
            } else {
                after[it].assert().isEqualTo(before[it])
            }
        }
        val shares = three.map { Hrw.partitionsOf(it, three, partitions).size }
        shares.forEach { it.assert().isBetween(partitions / 3 / 2, partitions / 3 * 3 / 2) }
    }
}
