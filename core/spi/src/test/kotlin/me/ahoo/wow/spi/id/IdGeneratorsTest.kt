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
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.random.RandomGenerator

class IdGeneratorsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")

    @Test
    fun `UUIDv7 ids carry the version, the variant and the millisecond first`() {
        val now = 0x0190_F2A3_4B5CL
        val ids = UuidV7Generator({ now }).let { generator -> (1..1_000).map { UUID.fromString(generator.next()) } }
        ids.forEach {
            it.version().assert().isEqualTo(7)
            it.variant().assert().isEqualTo(2)
            (it.mostSignificantBits ushr 16).assert().isEqualTo(now)
        }
        ids.toSet().size.assert().isEqualTo(ids.size)
    }

    @Test
    fun `UUIDv7 ids sort by the millisecond they were made in`() {
        var now = 1_700_000_000_000L
        val generator = UuidV7Generator({ now++ })
        val ids = (1..100).map { generator.next() }
        ids.sorted().assert().isEqualTo(ids)
        UUID.fromString(GlobalIdGenerator.UUID_V7.next()).version().assert().isEqualTo(7)
    }

    @Test
    fun `UUIDv7 ids taken on many threads at once are unique`() {
        val generator = UuidV7Generator({ 1_700_000_000_000L })
        val ids = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..THREADS).map { Thread { repeat(PER_THREAD) { ids += generator.next() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        ids.size.assert().isEqualTo(THREADS * PER_THREAD)
    }

    @Test
    fun `UUIDv7 ids taken on many virtual threads, each seeding its own random, are unique`() {
        val generator = UuidV7Generator({ 1_700_000_000_000L })
        val ids = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..VIRTUAL_THREADS).map {
            Thread.ofVirtual().start {
                repeat(PER_VIRTUAL) { ids += generator.next() }
            }
        }
        threads.forEach { it.join() }
        ids.size.assert().isEqualTo(VIRTUAL_THREADS * PER_VIRTUAL)
    }

    @Test
    fun `the random bits should come from the injected source`() {
        val zeros = object : RandomGenerator {
            override fun nextLong(): Long = 0
        }
        val ones = object : RandomGenerator {
            override fun nextLong(): Long = -1

            override fun nextInt(): Int = -1
        }
        UuidV7Generator({ 1L }, { zeros }).next().assert().isEqualTo("00000000-0001-7000-8000-000000000000")
        UuidV7Generator({ 1L }, { ones }).next().assert().isEqualTo("00000000-0001-7fff-bfff-ffffffffffff")
    }

    @Test
    fun `each thread should draw from a seeded random of its own, the same one each time`() {
        val here = SeededRandoms.current()
        SeededRandoms.current().assert().isSameAs(here)
        here.javaClass.simpleName.assert().isEqualTo("L64X256MixRandom")
        var there: RandomGenerator? = null
        Thread { there = SeededRandoms.current() }.apply { start() }.join()
        there.assert().isNotNull().isNotSameAs(here)
        // Seeded apart, from SecureRandom: no two threads start the same sequence.
        val firsts = (1..8).map {
            var first = 0L
            Thread { first = SeededRandoms.current().nextLong() }.apply { start() }.join()
            first
        }
        firsts.toSet().size.assert().isEqualTo(firsts.size)
    }

    @Test
    fun `the default generators should give UUIDv7 ids, global and per aggregate type`() {
        val ids = IdGenerators.DEFAULT
        ids.global.assert().isSameAs(GlobalIdGenerator.UUID_V7)
        ids.aggregates.assert().isSameAs(AggregateIdGenerator.UUID_V7)
        UUID.fromString(ids.next()).version().assert().isEqualTo(7)
        UUID.fromString(ids.next(order)).version().assert().isEqualTo(7)
        ids.next(order).assert().isNotEqualTo(ids.next(order))
    }

    @Test
    fun `an aggregate generator of a global one should give its ids whatever the type`() {
        var n = 0
        val aggregates = AggregateIdGenerator.of(GlobalIdGenerator { "g-${++n}" })
        aggregates.next(order).assert().isEqualTo("g-1")
        aggregates.next(cart).assert().isEqualTo("g-2")
    }

    @Test
    fun `the generators selected per type should name their types, the default the others`() {
        val default = AggregateIdGenerator { "default-${it.name}" }
        val orders = AggregateIdGenerator { "orders-${it.name}" }
        AggregateIdGenerator.of(default, emptyMap()).assert().isSameAs(default)
        val selected = AggregateIdGenerator.of(default, mapOf(order to orders))
        selected.next(order).assert().isEqualTo("orders-order")
        selected.next(cart).assert().isEqualTo("default-cart")
        val ids = IdGenerators(GlobalIdGenerator { "global" }, selected)
        ids.next().assert().isEqualTo("global")
        ids.next(order).assert().isEqualTo("orders-order")
    }

    private companion object {
        const val THREADS = 8
        const val PER_THREAD = 5_000
        const val VIRTUAL_THREADS = 1_000
        const val PER_VIRTUAL = 20
    }
}
