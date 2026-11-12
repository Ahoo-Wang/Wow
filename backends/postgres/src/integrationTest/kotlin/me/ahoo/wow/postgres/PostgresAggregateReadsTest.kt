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

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.VersionPages
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** The reads of one aggregate's streams: what they refuse, and a read whose table was dropped and made again. */
class PostgresAggregateReadsTest : PostgresLogFixtures() {
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

    @Test
    fun `a read is one statement, the first touch of a table aside`(): Unit = runBlocking {
        // B.O-1: a read must not check the table's existence or format each time.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val type = AggregateType(context, "order")
        PostgresFixture.log(1, context).append(stream("a", 1, aggregate = type))
        val reader = PostgresEventLog(counted.dataSource, context, 1).also { it.createSchema() }
        val ref = type.ref("tenant", "a")
        reader.read(ref).toList()
        repeat(READS) {
            val mark = counted.begin()
            reader.read(ref).toList().single().version.assert().isEqualTo(1)
            reader.readEntries(ref).toList()
            reader.readTail(ref, 1, 1)
            reader.positionOf(ref, 1)
            reader.findByRequestId(type, "tenant", "none")
            counted.since(mark).size.assert().isEqualTo(READ_STATEMENTS)
        }
    }

    @Test
    fun `a snapshot in the log's database is read with the tail in one statement`(): Unit = runBlocking {
        // D268: a cache miss reads the snapshot and the tail after it in one round trip.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val type = AggregateType(context, "order")
        val writer = PostgresFixture.log(1, context)
        for (version in 1..3) {
            writer.append(stream("a", version, aggregate = type))
        }
        val reader = PostgresEventLog(counted.dataSource, context, 1).also { it.createSchema() }
        val snapshots = PostgresSnapshotStore(counted.dataSource, context)
        val ref = type.ref("tenant", "a")
        val tails = reader.snapshotTailsWith(snapshots)
        tails.readWithSnapshot(ref, 1)
        snapshots.save(snapshot(ref, 2))
        val mark = counted.begin()
        val read = tails.readWithSnapshot(ref, 1)
        counted.since(mark).size.assert().isEqualTo(1)
        read.snapshot?.version.assert().isEqualTo(2)
        read.tail.streams.map { it.version }.assert().containsExactly(2, 3)
        read.tail.earlier.map { it.version }.assert().containsExactly(1)
    }

    @Test
    fun `snapshots of another context or database are not read with the tail`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = PostgresFixture.log(1, context)
        val otherContext = PostgresSnapshotStore(PostgresFixture.dataSource, PostgresFixture.newContext())
        val elsewhere = PostgresSnapshotStore(CountedDataSource(PostgresFixture.dataSource).dataSource, context)
        val ref = AggregateType(context, "order").ref("tenant", "a")
        // Only the store's own log and snapshots of one context are read together; the runtime asks no other.
        listOf(otherContext, elsewhere).forEach { store ->
            runCatching { log.snapshotTailsWith(store).readWithSnapshot(ref, 1) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `a failing statement is thrown, never swallowed, so that the kernel reads apart and counts it`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val type = AggregateType(context, "order")
        val log = PostgresFixture.log(1, context)
        for (version in 1..3) {
            log.append(stream("a", version, aggregate = type))
        }
        val ref = type.ref("tenant", "a")
        val store = PostgresSnapshotStore(PostgresFixture.dataSource, context)
        store.save(snapshot(ref, 2))
        PostgresFixture.dataSource.connection.use { connection ->
            val snapshots = PostgresSnapshotTables(context, "order").snapshot
            connection.createStatement().use { it.execute("DROP TABLE $snapshots") }
        }
        // The snapshot store still believes its table is there: the one statement fails, and says so (finding R5.B-16).
        runCatching { log.snapshotTailsWith(store).readWithSnapshot(ref, 1) }.exceptionOrNull().assert().isNotNull()
    }

    @Test
    fun `a long read takes one statement a page, a read within a page one`(): Unit = runBlocking {
        // X-80: a replay from version 1 once held a hot aggregate's whole history; now it holds a page.
        val prepared = ConcurrentLinkedQueue<String>()
        val context = PostgresFixture.newContext()
        val log = PostgresEventLog(preparing(PostgresFixture.dataSource, prepared), context, 1)
            .also { it.createSchema() }
        val type = AggregateType(context, "account")
        val long = type.ref("tenant", "long")
        val count = 2 * VersionPages.SIZE + 3
        for (versions in (1..count).chunked(GROUP_OF_STREAMS)) {
            log.appendAll(versions.map { Append.of(stream(long, it)) })
        }
        val short = type.ref("tenant", "short")
        for (version in 1..SHORT) {
            log.append(stream(short, version))
        }
        fun pages(read: suspend () -> Int): Pair<Int, Map<Boolean, Int>> {
            prepared.clear()
            val size = runBlocking { read() }
            // A page of entries names the partition and sequence first.
            val statements = prepared.filter { it.endsWith("ORDER BY version LIMIT ?") }
            return size to statements.groupingBy { it.startsWith("SELECT partition, sequence") }.eachCount()
        }

        pages { log.read(long).toList().size }.assert().isEqualTo(count to mapOf(false to 3))
        pages { log.readEntries(long).toList().size }.assert().isEqualTo(count to mapOf(true to 3))
        pages { log.read(short).toList().size }.assert().isEqualTo(SHORT to mapOf(false to 1))
        pages { log.readEntries(short).toList().size }.assert().isEqualTo(SHORT to mapOf(true to 1))
        // A page that reaches the end of the range asked for is the last.
        pages { log.read(long, 1, VersionPages.SIZE).toList().size }
            .assert().isEqualTo(VersionPages.SIZE to mapOf(false to 1))
        pages { log.read(long).take(1).toList().size }.assert().isEqualTo(1 to mapOf(false to 1))
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

    private fun snapshot(ref: AggregateRef, version: Int) = SnapshotRecord(
        ref = ref,
        version = version,
        schemaFingerprint = "fingerprint",
        state = """{"version":$version}""",
        scope = Scope("owner", "space"),
        deleted = false,
        firstEventTime = 1L,
        lastEventTime = version.toLong(),
        access = emptyMap(),
    )

    /** [target], recording the statement of each `prepareStatement` of its connections in [prepared]. */
    private fun preparing(target: DataSource, prepared: ConcurrentLinkedQueue<String>): DataSource {
        fun <T : Any> proxy(type: Class<T>, delegate: T, intercept: (String, Array<Any?>) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val arguments = args ?: emptyArray()
                intercept(method.name, arguments) ?: try {
                    method.invoke(delegate, *arguments)
                } catch (thrown: InvocationTargetException) {
                    throw thrown.targetException
                }
            },
        )
        return proxy(DataSource::class.java, target) { name, _ ->
            if (name != "getConnection") return@proxy null
            val connection = target.connection
            proxy(Connection::class.java, connection) { method, arguments ->
                if (method == "prepareStatement") (arguments.firstOrNull() as? String)?.let(prepared::add)
                null
            }
        }
    }

    private companion object {
        const val READS = 3
        const val READ_STATEMENTS = 5
        const val GROUP_OF_STREAMS = 64
        const val SHORT = 5
    }
}
