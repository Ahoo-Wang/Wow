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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** The aggregate tables a log makes sure of: only those of its own context. */
class PostgresLogCatalogTest {
    @Test
    fun `an aggregate type of another context is refused by a read, an append and the table checks`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(1, context)
            val elsewhere = AggregateType(PostgresFixture.newContext(), "order")
            val ref = elsewhere.ref("tenant", "a")
            val stream = EventStreamRecord(
                ref,
                1,
                "c-a-1",
                "r-a-1",
                "$elsewhere.Act",
                1,
                listOf(EventRecord("$elsewhere.Acted", 1, "{}")),
                Scope.NONE,
            )
            val refused = "$elsewhere is not in context $context."
            // A read makes sure of the type's table; an append refuses the stream before it takes a connection.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.read(ref, 1, 1).toList() } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(ref, 1, 0) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.append(stream, null) } }
                .hasMessage("$ref is not in context $context.")
            // The checks of a type's table, which create none.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.holds(elsewhere) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.aggregates(elsewhere, null, 1) } }
                .hasMessage(refused)
        }
}
