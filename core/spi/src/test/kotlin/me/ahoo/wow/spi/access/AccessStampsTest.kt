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

package me.ahoo.wow.spi.access

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** Stamps written together: one at a time unless the store writes them at once, which needs them disjoint. */
class AccessStampsTest {
    private val type = AggregateType("office", "document")
    private val a = type.ref("t", "a")
    private val b = type.ref("t", "b")

    private fun stamp(ref: AggregateRef, asOf: Int, from: Int, until: Int) =
        AccessStamp(ref, mapOf("owner" to "alice"), asOf, from, until)

    @Test
    fun `stamps are disjoint when no two of one aggregate cover one stream`() {
        AccessStamps.disjoint(emptyList()).assert().isTrue()
        AccessStamps.disjoint(listOf(stamp(a, 4, 3, 5), stamp(a, 4, 1, 3), stamp(b, 2, 1, 5))).assert().isTrue()
        AccessStamps.disjoint(listOf(stamp(a, 4, 1, 3), stamp(a, 5, 2, 4))).assert().isFalse()
    }

    @Test
    fun `by default each stamp is written as one, in order, and their streams are summed`(): Unit = runBlocking {
        val written = ArrayList<AccessStamp>()
        val stamps = object : AccessStamps {
            override suspend fun stamp(
                ref: AggregateRef,
                access: Map<String, Any?>,
                asOf: Int,
                from: Int,
                until: Int
            ): Int {
                written += AccessStamp(ref, access, asOf, from, until)
                return until - from
            }
        }
        val all = listOf(stamp(a, 4, 1, 3), stamp(b, 2, 1, 2))
        stamps.stampAll(all).assert().isEqualTo(3)
        written.assert().isEqualTo(all)
    }
}
