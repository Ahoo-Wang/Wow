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

package me.ahoo.wow.mongo.log

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** The aggregate collections a log makes sure of: only those of its own context. */
class MongoLogCollectionsTest {
    @Test
    fun `an aggregate type of another context is refused by a read, an append and the collection checks`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            val elsewhere = AggregateType(MongoLogFixture.newContext(), "order")
            val first = stream(elsewhere.ref("tenant", "a"))
            val refused = "$elsewhere is not in context $context."
            // A read makes sure of the type's collection; an append and a group refuse the stream first.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.read(first.ref, 1, 1).toList() } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(first.ref, 1, 0) } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.append(first, null) } }
                .hasMessage("${first.ref} is not in context $context.")
            val group = listOf(first to null, stream(elsewhere.ref("tenant", "b")) to null)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group) } }
                .hasMessage("A group commit appends streams of $context.")
            // The checks of a type's collection, which create none.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.holds(elsewhere) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.aggregates(elsewhere, null, 1) } }
                .hasMessage(refused)
        }

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )
}
