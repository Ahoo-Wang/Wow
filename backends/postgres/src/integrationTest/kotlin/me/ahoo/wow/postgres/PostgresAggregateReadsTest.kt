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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** The reads of one aggregate's streams: what they refuse, and a read whose table was dropped and made again. */
class PostgresAggregateReadsTest {
    @Test
    fun `a tail read refuses a version below 1 and a negative number of earlier requests`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = PostgresFixture.log(1, context, listOf("order"))
        val ref = AggregateType(context, "order").ref("tenant", "a")
        assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(ref, 0, 0) } }
            .hasMessage("A stream has a version from 1.")
        assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(ref, 1, -1) } }
            .hasMessage("At most a non-negative number of earlier requests.")
        val snapshots = log.snapshotTailsWith(PostgresFixture.snapshotStore(context))
        assertThrownBy<IllegalArgumentException> { runBlocking { snapshots.readWithSnapshot(ref, -1) } }
            .hasMessage("At most a non-negative number of earlier requests.")
    }

    @Test
    fun `a read whose table was dropped reads the table made again, with what another node appended to it`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val table = PostgresEventLogTables(context).aggregate("order")
            val ref = AggregateType(context, "order").ref("tenant", "a")
            // Another node's log, which appends to the table once this log has made it again.
            val other = PostgresFixture.log(1, context, listOf("order"))
            val hook = Meanwhile(setOf(table.tail, table.position))
            val log = PostgresEventLog(hook.over(PostgresFixture.dataSource), context, 1)
            log.positionOf(ref, 1).assert().isNull()
            val drop = "DROP TABLE ${table.log}"

            execute(drop)
            hook.arm(append(other, ref, 1, 2))
            val tail = log.readTail(ref, 2, 1)
            tail.streams.map { it.version }.assert().isEqualTo(listOf(2))
            tail.earlier.map { it.version }.assert().isEqualTo(listOf(1))

            execute(drop)
            hook.arm(append(other, ref, 1))
            log.positionOf(ref, 1).assert().isNotNull()

            execute(drop)
            hook.arm {}
            log.positionOf(ref, 1).assert().isNull()
        }

    /** [log] appending [ref]'s [versions], once. */
    private fun append(log: PostgresEventLog, ref: AggregateRef, vararg versions: Int): () -> Unit {
        val done = AtomicInteger()
        return {
            if (done.getAndIncrement() == 0) {
                runBlocking {
                    versions.forEach {
                        log.append(stream(ref, it), null).assert().isInstanceOf(AppendOutcome.Committed::class.java)
                    }
                }
            }
        }
    }

    /**
     * Runs an action before the second time a connection prepares one of [reads] since it was armed: a read's statement
     * again, once the table it failed on is made again.
     */
    private inner class Meanwhile(private val reads: Set<String>) {
        private val prepared = HashMap<String, Int>()
        private var action: () -> Unit = {}

        fun arm(action: () -> Unit) = synchronized(this) {
            prepared.clear()
            this.action = action
        }

        private fun second(sql: String): Boolean = synchronized(this) { prepared.merge(sql, 1, Int::plus) == 2 }

        fun over(target: DataSource): DataSource = proxy(DataSource::class.java, target) { name, _ ->
            if (name != "getConnection") return@proxy null
            val connection = target.connection
            proxy(Connection::class.java, connection) { method, arguments ->
                val sql = arguments.firstOrNull() as? String
                if (method == "prepareStatement" && sql in reads && second(sql!!)) action()
                null
            }
        }
    }

    private fun execute(sql: String) {
        PostgresFixture.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun stream(ref: AggregateRef, version: Int) = EventStreamRecord(
        ref,
        version,
        "c-${ref.id}-$version",
        "r-${ref.id}-$version",
        "${ref.type}.Act",
        version.toLong(),
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    private fun <T : Any> proxy(type: Class<T>, delegate: T, intercept: (String, Array<Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            val arguments = args ?: emptyArray()
            intercept(method.name, arguments) ?: try {
                method.invoke(delegate, *arguments)
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        },
    )
}
