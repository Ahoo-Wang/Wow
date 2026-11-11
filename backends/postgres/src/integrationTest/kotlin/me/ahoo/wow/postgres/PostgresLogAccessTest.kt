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
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.log.AppendOutcome
import org.junit.jupiter.api.Test

/** The access attributes of the log: what a stamp of no stream and a page of no aggregate do. */
class PostgresLogAccessTest : PostgresLogFixtures() {
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

    @Test
    fun `the access walk of a type without history creates no table, and its first append creates it`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(1, context)
            val order = AggregateType(context, "order")
            val snapshot = AggregateType(context, "snapshot")
            log.append(stream("o-1", 1, aggregate = order))
            // What a walk's run reads and writes of a type, all of it, before the type's first stream.
            val walk = AccessWalk("", mapOf(0 to (log.head(0) ?: 0L)))
            log.holds(snapshot).assert().isFalse()
            log.walk(snapshot).assert().isNull()
            log.claim(snapshot, walk, expected = null).assert().isFalse()
            log.advance(snapshot, "", null, walked = 0, completed = true).assert().isFalse()
            log.aggregates(snapshot, null, limit = 10).refs.assert().isEmpty()
            exists(table(context, "snapshot")).assert().isFalse()
            // The tail reads across the registered tables: the type's is not among them.
            registered(context).assert().containsExactly("order")

            log.append(stream("s-1", 1, aggregate = snapshot))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            exists(table(context, "snapshot")).assert().isTrue()
            registered(context).assert().containsExactly("order", "snapshot")
            // Another node finds it through the registry, and claims its walk beside it.
            val other = PostgresFixture.log(1, context)
            other.holds(snapshot).assert().isTrue()
            other.claim(snapshot, walk, expected = null).assert().isTrue()
            log.walk(snapshot).assert().isEqualTo(walk)
            log.aggregates(snapshot, null, limit = 10).refs.assert().containsExactly(snapshot.ref("tenant", "s-1"))
        }

    private fun exists(table: String): Boolean = query("SELECT to_regclass('\"$table\"') IS NOT NULL").single() == "t"

    private fun registered(context: String): List<String> =
        query("SELECT aggregate FROM ${PostgresEventLogTables.REGISTRY} WHERE context = '$context' ORDER BY aggregate")

    private fun query(sql: String): List<String> = PostgresFixture.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }
}
