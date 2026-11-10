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

package me.ahoo.wow.postgres

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** The access attributes of the log: what a stamp of no stream and a page of no aggregate do. */
class PostgresLogAccessTest {
    @Test
    fun `a stamp of an empty range writes nothing and makes no table, and a page of no aggregates is refused`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(1, context)
            val type = AggregateType(context, "order")
            val ref = type.ref("tenant", "a")
            log.stamp(ref, mapOf("owner" to "x"), 1, 2, 2).assert().isZero()
            log.stamp(ref, mapOf("owner" to "x"), 1, 3, 2).assert().isZero()
            log.holds(type).assert().isFalse()
            assertThrownBy<IllegalArgumentException> { runBlocking { log.aggregates(type, null, 0) } }
                .hasMessage("A page holds at least one aggregate.")
        }
}
