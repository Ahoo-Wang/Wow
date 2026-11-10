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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.notAppendedAfter
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

/** How the log's appends answer the failures of their statements, and how a large group is cut into statements. */
class PostgresLogAppenderTest {
    @Test
    fun `an append whose statement fails once it may have left is unknown, a lost connection too`(): Unit =
        runBlocking {
            // The driver's own failure, and one of a pool's wrapper, which is not the driver's exception.
            val failures = listOf(
                PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE),
                SQLException("This connection has been closed.", "08003"),
            )
            failures.forEach { failure ->
                val context = PostgresFixture.newContext()
                val log = log(context) { sql -> failure.takeIf { sql.startsWith(SINGLE) } }
                val outcome = log.append(stream(AggregateType(context, "order").ref("tenant", "a"), 1), null)
                (outcome as AppendOutcome.Unknown).cause.assert().isSameAs(failure)
                log.head(0).assert().isNull()
            }
        }

    @Test
    fun `an append that fails before its statement leaves, other than transiently, is unknown`(): Unit = runBlocking {
        // Only a transient failure before the statement is thrown as the storage error; any other is unknown.
        val context = PostgresFixture.newContext()
        val refused = SQLException("permission denied for table", "42501")
        PostgresFixture.log(1, context)
        val log = PostgresEventLog(unreachable(refused), context, 1)
        val outcome = log.append(stream(AggregateType(context, "order").ref("tenant", "a"), 1), null)
        (outcome as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
    }

    @Test
    fun `a group PostgreSQL ends a deadlock with is appended one by one, and commits`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = log(context) { sql -> deadlock().takeIf { sql.startsWith(GROUP) } }
        val order = AggregateType(context, "order")
        val streams = listOf(stream(order.ref("tenant", "a"), 1), stream(order.ref("tenant", "b"), 1))
        val outcomes = log.appendAll(streams.map { it to null })
        outcomes.assert().isEqualTo(listOf(committed(1), committed(2)))
    }

    @Test
    fun `a group whose connection is lost while it runs is unknown for every stream, never appended one by one`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val lost =
                PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE)
            val log = log(context) { sql -> lost.takeIf { sql.startsWith(GROUP) } }
            val order = AggregateType(context, "order")
            val outcomes = log.appendAll((1..3).map { stream(order.ref("tenant", "a$it"), 1) to null })
            outcomes.forEach { (it as AppendOutcome.Unknown).cause.assert().isSameAs(lost) }
            log.head(0).assert().isNull()
        }

    @Test
    fun `a stream appended alone after its group aborted is unknown when its statement fails, and its later ones are not appended`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val closed = SQLException("This connection has been closed.", "08003")
            val log = log(context) { sql ->
                when {
                    sql.startsWith(GROUP) -> deadlock()
                    sql.startsWith(SINGLE) -> closed
                    else -> null
                }
            }
            val order = AggregateType(context, "order")
            val first = stream(order.ref("tenant", "a"), 1)
            val outcomes = log.appendAll(listOf(first to null, stream(first.ref, 2) to null))
            outcomes[0].assert().isEqualTo(AppendOutcome.Unknown(closed))
            outcomes[1].assert().isEqualTo(notAppendedAfter(first, outcomes[0]))
            log.head(0).assert().isNull()
        }

    @Test
    fun `a group of more streams than a statement takes is committed a statement at a time, an aggregate's streams in one`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val prepared = ConcurrentLinkedQueue<String>()
            val log = log(context) { sql ->
                prepared += sql
                null
            }
            val order = AggregateType(context, "order")
            // The last aggregate of the first statement has its second stream after it: it stays in that statement.
            val firsts = (0 until STATEMENT_STREAMS).map { stream(order.ref("tenant", "a$it"), 1) }
            val second = stream(firsts.last().ref, 2)
            val next = stream(order.ref("tenant", "b"), 1)
            prepared.clear()
            val outcomes = log.appendAll((firsts + second + next).map { it to null })
            outcomes.assert().isEqualTo((1L..STATEMENT_STREAMS + 2L).map { committed(it) })
            // Each stream of a group statement binds one `?::boolean`: the statements hold 1,001 streams, then 1.
            val groups = prepared.filter { it.startsWith(GROUP) }.map { sql -> sql.split("?::boolean").size - 1 }
            groups.assert().isEqualTo(listOf(STATEMENT_STREAMS + 1, 1))
            log.read(second.ref).toList().map { it.version }.assert().isEqualTo(listOf(1, 2))
        }

    private fun committed(sequence: Long) = AppendOutcome.Committed(LogPosition(0, sequence))

    private fun deadlock() = PSQLException(ServerErrorMessage("SERROR\u0000C40P01\u0000Mdeadlock detected\u0000"))

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

    /**
     * A log of [context] with one partition, its table of `order` made sure of, over connections whose statements
     * fail with what [failureOf] gives for their SQL when they run, and run when it gives null.
     */
    private fun log(context: String, failureOf: (String) -> SQLException?): PostgresEventLog {
        PostgresFixture.log(1, context, listOf("order"))
        return PostgresEventLog(failing(PostgresFixture.dataSource, failureOf), context, 1)
    }

    private fun failing(target: DataSource, failureOf: (String) -> SQLException?): DataSource =
        proxy(DataSource::class.java, target) { name, _ ->
            if (name != "getConnection") return@proxy null
            val connection = target.connection
            proxy(Connection::class.java, connection) { method, arguments ->
                val sql = (arguments.firstOrNull() as? String)?.trimStart()
                if (method != "prepareStatement" || sql == null) return@proxy null
                val failure = failureOf(sql)
                proxy(PreparedStatement::class.java, connection.prepareStatement(sql)) { call, _ ->
                    if (call == "executeQuery" && failure != null) throw failure else null
                }
            }
        }

    /** A data source whose every connection fails with [failure]. */
    private fun unreachable(failure: SQLException): DataSource =
        proxy(DataSource::class.java, PostgresFixture.dataSource) { name, _ ->
            if (name == "getConnection") throw failure else null
        }

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

    private companion object {
        /** The single append's statement, and a group's ([PostgresEventLogTables.appendGroup]). */
        const val SINGLE = "WITH current"
        const val GROUP = "WITH input"

        /** The most streams one group statement takes. */
        const val STATEMENT_STREAMS = 1000
    }
}
