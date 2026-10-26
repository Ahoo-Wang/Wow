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
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.random.Random

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

    @Test
    fun `a slot is the hash modulo 4096, fixed whatever the partitions`() {
        val ref = AggregateType("example", "cart").ref("tenant", "id-1")
        Partitioner.SLOTS.assert().isEqualTo(4096)
        Partitioner.slotOf(ref).assert().isEqualTo(Math.floorMod(Partitioner.hash("example.cart/tenant/id-1"), 4096))
        validCounts.forEach { partitions ->
            Partitioner.partitionOf(ref, partitions).assert().isEqualTo(Partitioner.slotOf(ref) % partitions)
        }
    }

    @Test
    fun `slots assign every aggregate the partition it had before them, for every valid count`() {
        val random = Random(240)
        val keys = (0 until 2_000).map { "order-$it" } +
            (0 until 2_000).map { UUID(random.nextLong(), random.nextLong()).toString() } +
            (0 until 2_000).map { random.nextLong().toString(36) } +
            listOf("订单-1", "\u0000", "a".repeat(300))
        val tenants = listOf("tenant", "(0)", "租户")
        for (key in keys) {
            for (tenant in tenants) {
                val ref = AggregateType("ctx", "agg").ref(tenant, key)
                val hash = Partitioner.hash(Partitioner.key(ref))
                validCounts.forEach { partitions ->
                    Partitioner.partitionOf(ref, partitions).assert().isEqualTo(Math.floorMod(hash, partitions))
                }
            }
        }
    }

    @Test
    fun `a partition count is a power of two that divides 4096`() {
        validCounts.assert().hasSize(13)
        validCounts.forEach { Partitioner.isValidPartitions(it).assert().isTrue() }
        validCounts.forEach { Partitioner.requirePartitions(it).assert().isEqualTo(it) }
        val ref = AggregateType("c", "a").ref("t", "1")
        listOf(Int.MIN_VALUE, -64, -1, 0, 3, 6, 12, 48, 63, 65, 100, 1000, 4095, 4097, 8192, Int.MAX_VALUE)
            .forEach { invalid ->
                Partitioner.isValidPartitions(invalid).assert().isFalse()
                assertThrows<IllegalArgumentException> { Partitioner.partitionOf(ref, invalid) }
                    .message.assert().contains("$invalid partitions").contains("power of two")
            }
        (1..Partitioner.SLOTS).count { Partitioner.isValidPartitions(it) }.assert().isEqualTo(13)
    }

    @Test
    fun `sequential and random ids spread uniformly over the slots`() {
        val perSlot = 64
        val total = Partitioner.SLOTS * perSlot
        val random = Random(4096)
        listOf<(Int) -> String>(
            { "$it" },
            { "order-$it" },
            { UUID(random.nextLong(), random.nextLong()).toString() },
        ).forEach { id ->
            val counts = IntArray(Partitioner.SLOTS)
            repeat(total) { counts[Partitioner.slotOf(AggregateType("shop", "order").ref("tenant", id(it)))]++ }
            // Chi-square over 4095 degrees of freedom: about 4095 ± 90 for a uniform spread; 4,600 is over 5 sigma.
            val chiSquare = counts.sumOf { (it - perSlot).toDouble().let { d -> d * d / perSlot } }
            chiSquare.assert().isLessThan(4_600.0)
            counts.min().assert().isGreaterThan(0)
        }
    }

    @Test
    fun `a small partition count sees only the low bits of each character`() {
        val lower = AggregateType("c", "a").ref("t", "a")
        val upper = AggregateType("c", "a").ref("t", "A")
        Partitioner.partitionOf(lower, 32).assert().isEqualTo(Partitioner.partitionOf(upper, 32))
        Partitioner.slotOf(lower).assert().isNotEqualTo(Partitioner.slotOf(upper))
    }

    private companion object {
        val validCounts = (0..12).map { 1 shl it }
    }
}
