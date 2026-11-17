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

package me.ahoo.wow.postgres.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.util.UUID

/** Screening only: the batched load reads, in one statement a type, what a load of each ref reads. */
class PostgresSnapshotLoadAllScreenTest {
    private val context = "screen_" + UUID.randomUUID().toString().replace("-", "").take(8)
    private val store = PostgresFixture.snapshotStore(context)

    @Test
    fun `a batched load reads what each load reads, across types and tenants, an absent ref left out`(): Unit =
        runBlocking {
            val one = AggregateType(context, "one")
            val two = AggregateType(context, "two")
            val refs = listOf(one.ref("t1", "a"), one.ref("t2", "a"), two.ref("t1", "a"), one.ref("t1", "b"))
            refs.take(3).forEachIndexed { at, ref ->
                store.save(SnapshotRecord(ref, at + 1, "f", """{"n":$at}""", firstEventTime = 0, lastEventTime = 0))
            }
            val loaded = store.loadAll(refs + refs.first())
            loaded.keys.assert().containsExactlyInAnyOrderElementsOf(refs.take(3))
            refs.take(3).forEach { ref ->
                val alone = store.load(ref)!!
                loaded.getValue(ref).version.assert().isEqualTo(alone.version)
                loaded.getValue(ref).state.assert().isEqualTo(alone.state)
            }
        }
}
