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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** The partition hash is part of the storage format: these values must never change. */
class PartitionerTest {
    @Test
    fun `hash is 64-bit FNV-1a`() {
        Partitioner.hash("").assert().isEqualTo(0xcbf29ce484222325uL.toLong())
        Partitioner.hash("a").assert().isEqualTo(0xaf63dc4c8601ec8cuL.toLong())
        Partitioner.hash("foobar").assert().isEqualTo(0x85944171f73967e8uL.toLong())
    }

    @Test
    fun `the partition key spells out context, aggregate, tenant and id`() {
        Partitioner.key(AggregateType("example", "cart").ref("tenant", "id-1"))
            .assert().isEqualTo("example.cart/tenant/id-1")
    }

    @Test
    fun `a ref keeps its partition`() {
        val ref = AggregateType("example", "cart").ref("tenant", "id-1")
        Partitioner.partitionOf(
            ref,
            64
        ).assert().isEqualTo(Math.floorMod(Partitioner.hash("example.cart/tenant/id-1"), 64))
        (0 until 1000).map { Partitioner.partitionOf(AggregateType("c", "a").ref("t", "$it"), 16) }.toSet()
            .assert().hasSize(16)
    }
}
