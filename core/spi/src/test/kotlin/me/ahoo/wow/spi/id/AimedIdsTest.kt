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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.UUID
import java.util.random.RandomGenerator

/** The ids a generator aims at a partition ([AggregateIdGenerator.nextIn]): Wow's UUIDv7 ones, and how [of] forwards. */
class AimedIdsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")

    @Test
    fun `an aimed UUIDv7 falls in the partition asked, for every partition count and tenant`() {
        val tenants = listOf("tenant", "租户", "a/b", "50%", "😀")
        (0..12).map { 1 shl it }.forEach { partitions ->
            tenants.forEach { tenant ->
                listOf(0, partitions / 2, partitions - 1).forEach { partition ->
                    val id = AggregateIdGenerator.UUID_V7.nextIn(order, tenant, partition, partitions)!!
                    Partitioner.partitionOf(order.ref(tenant, id), partitions).assert()
                        .describedAs("$id of $tenant").isEqualTo(partition)
                    val uuid = UUID.fromString(id)
                    uuid.version().assert().isEqualTo(7)
                    uuid.variant().assert().isEqualTo(2)
                    uuid.toString().assert().isEqualTo(id)
                }
            }
        }
    }

    @Test
    fun `only the last four digits are aimed, the other random bits stay as drawn`() {
        val zeros = object : RandomGenerator {
            override fun nextLong(): Long = 0
        }
        val ones = object : RandomGenerator {
            override fun nextLong(): Long = -1

            override fun nextInt(): Int = -1
        }
        UuidV7Generator({ 1L }, { zeros }).aimed(order, "t", 3, 64)
            .assert().startsWith("00000000-0001-7000-8000-00000000").hasSize(36)
        UuidV7Generator({ 1L }, { ones }).aimed(order, "t", 3, 64)
            .assert().startsWith("00000000-0001-7fff-bfff-ffffffff").hasSize(36)
        // Each id draws its own random bits: ids aimed at one partition stay apart.
        (1..1_000).map { AggregateIdGenerator.UUID_V7.nextIn(order, "t", 7, 4096) }.toSet().assert().hasSize(1_000)
    }

    @Test
    fun `from every state the last four digits reach every slot, so one round always finds the partition`() {
        // A slot is the hash's low 12 bits, which depend only on the low 12 bits before them: 4,096 states in all.
        val digits = "0123456789abcdef".map { it.code }
        val reached = BooleanArray(Partitioner.SLOTS)
        for (state in 0 until Partitioner.SLOTS) {
            reached.fill(false)
            var count = 0
            for (a in digits) {
                val first = Partitioner.hash(state.toLong(), a)
                for (b in digits) {
                    val second = Partitioner.hash(first, b)
                    for (c in digits) {
                        val third = Partitioner.hash(second, c)
                        for (d in digits) {
                            val slot = Partitioner.hash(third, d).toInt() and (Partitioner.SLOTS - 1)
                            if (!reached[slot]) {
                                reached[slot] = true
                                count++
                            }
                        }
                    }
                }
            }
            count.assert().describedAs("slots from state $state").isEqualTo(Partitioner.SLOTS)
        }
    }

    @Test
    fun `the generators are made whichever of their classes a JVM initialises first`() {
        // The aggregate generator's default member initialises it with the first class that implements it: the
        // global UUIDv7 generator, made while its own companion initialises, must not be one.
        val classPath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        listOf("IdGenerators", "GlobalIdGenerator", "AggregateIdGenerator", "UuidV7Aggregates").forEach { first ->
            URLClassLoader(classPath.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                Class.forName("me.ahoo.wow.spi.id.$first", true, loader)
                listOf("GlobalIdGenerator", "AggregateIdGenerator").forEach { holder ->
                    Class.forName("me.ahoo.wow.spi.id.$holder", true, loader).getField("UUID_V7").get(null)
                        .assert().describedAs("$holder.UUID_V7 after $first").isNotNull()
                }
            }
        }
    }

    @Test
    fun `a partition outside the count, or an invalid count, is refused`() {
        assertThrownBy<IllegalArgumentException> { AggregateIdGenerator.UUID_V7.nextIn(order, "t", 4, 4) }
            .hasMessage("No partition 4 among 4.")
        assertThrownBy<IllegalArgumentException> { AggregateIdGenerator.UUID_V7.nextIn(order, "t", -1, 4) }
            .hasMessage("No partition -1 among 4.")
        assertThrownBy<IllegalArgumentException> { AggregateIdGenerator.UUID_V7.nextIn(order, "t", 0, 3) }
            .hasMessageContaining("3 partitions")
    }

    @Test
    fun `a generator that does not aim gives null, and the generators per type forward each its own`() {
        val counted = AggregateIdGenerator { "counted-${it.name}" }
        counted.nextIn(order, "t", 0, 4).assert().isNull()
        val selected = AggregateIdGenerator.of(AggregateIdGenerator.UUID_V7, mapOf(cart to counted))
        selected.nextIn(cart, "t", 1, 4).assert().isNull()
        selected.next(cart).assert().isEqualTo("counted-cart")
        Partitioner.partitionOf(order.ref("t", selected.nextIn(order, "t", 1, 4)!!), 4).assert().isEqualTo(1)
        AggregateIdGenerator.of(GlobalIdGenerator.UUID_V7).assert().isSameAs(AggregateIdGenerator.UUID_V7)
        // Another of Wow's UUIDv7 generators, with a clock of its own, aims as the shared one does.
        val clocked = AggregateIdGenerator.of(UuidV7Generator({ 1L }))
        clocked.next(order).assert().startsWith("00000000-0001-7")
        Partitioner.partitionOf(order.ref("t", clocked.nextIn(order, "t", 2, 4)!!), 4).assert().isEqualTo(2)
        AggregateIdGenerator.of(GlobalIdGenerator { "g" }).nextIn(order, "t", 2, 4).assert().isNull()
    }

    @Test
    fun `an application's class that is both generators is asked for its global ids alone, and does not aim`() {
        val both = Both()
        val aggregates = AggregateIdGenerator.of(both)
        aggregates.next(order).assert().isEqualTo("global")
        aggregates.nextIn(order, "t", 0, 4).assert().isNull()
        both.aggregateCalls.assert().isEqualTo(0)
    }

    /** A class of an application's that is a global and an aggregate generator, which also aims. */
    private class Both : GlobalIdGenerator, AggregateIdGenerator {
        var aggregateCalls = 0

        override fun next(): String = "global"

        override fun next(type: AggregateType): String = "aggregate".also { aggregateCalls++ }

        override fun nextIn(type: AggregateType, tenantId: String, partition: Int, partitions: Int): String =
            "aimed".also { aggregateCalls++ }
    }
}
