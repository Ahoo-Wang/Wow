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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import org.junit.jupiter.api.Test

/** Stamps written together in writes of at most a batch of streams, a stamp of more cut into chunks of one. */
class StampAllInTest {
    private val type = AggregateType("office", "document")

    private fun stamp(id: String, from: Int, until: Int) =
        AccessStamp(type.ref("t", id), mapOf("owner" to "alice"), until - 1, from, until)

    @Test
    fun `stamps are written in writes of at most a batch of streams, each chunk whole`(): Unit = runBlocking {
        val writes = ArrayList<List<Pair<String, Int>>>()
        val stamps = object : AccessStamps {
            override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int) =
                error("Written together.")

            override suspend fun stampAll(stamps: List<AccessStamp>): Int {
                writes += stamps.map { it.ref.id to it.until - it.from }
                return stamps.sumOf { it.until - it.from }
            }
        }
        // a and b, 2 streams each, fill one write of 4; c, 5 streams, is cut into 4 and 1.
        stamps.stampAllIn(listOf(stamp("a", 1, 3), stamp("b", 1, 3), stamp("c", 1, 6)), 4).assert().isEqualTo(9)
        writes.assert().containsExactly(listOf("a" to 2, "b" to 2), listOf("c" to 4), listOf("c" to 1))
        // Nothing to stamp writes nothing; a write of no stream is refused.
        stamps.stampAllIn(emptyList(), 4).assert().isEqualTo(0)
        writes.assert().hasSize(3)
        assertThrownBy<IllegalArgumentException> { runBlocking { stamps.stampAllIn(emptyList(), 0) } }
    }
}
