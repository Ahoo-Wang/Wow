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

package me.ahoo.wow.spring.boot

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.ExecutionLease
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The round trips one command costs the kernel on a real backend (decision D261, revising D46), pinned per path: the
 * aggregate's recent requests save the request-id lookup of a command that appends, on a warm aggregate and on a cold
 * load, and keep every other path's cost. Each case runs again without recent requests (`recentRequests = 0`, as D46
 * did) for the round trips it saves. No timing is measured.
 */
abstract class KernelRoundTripsSpec {
    data class Open(val owner: String)
    data class Add(val amount: Int)
    data class Check(val amount: Int)
    data class Refuse(val reason: String)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private val context = "trips_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
    private val model: AggregateModel<Counter> = aggregate(context, "counter") {
        initial { Counter() }
        decide<Open>(Creation.REQUIRED) { _, _ -> Decision.events(Added(0)) }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        decideSuspending<Check> { _, body -> Decision.events(Added(body.amount)) }
        decide<Refuse> { _, body -> Decision.reject("refused", body.reason) }
        evolve<Added> { total += it.amount }
    }
    private val codec = EventCodec()
    private val sequence = AtomicInteger()

    /** The log of [context] on this backend, counted. */
    protected abstract fun log(context: String): EventLog

    /** The snapshot store of [context] on this backend, counted. */
    protected abstract fun snapshots(context: String): SnapshotStore

    /** The round trips counted so far: a mark for [since]. */
    protected abstract fun mark(): Int

    /** The round trips counted since [mark]. */
    protected abstract fun since(mark: Int): Int

    /** The round trips of one warm append on this backend, the append itself. */
    protected abstract val append: Int

    /**
     * The round trips of a cold load's reads (decision D268): the snapshot and the tail after it with the earlier
     * requests, together where the log reads its database's snapshots with the tail.
     */
    protected abstract val load: Int

    private val log by lazy { log(context) }
    private val snapshots by lazy { snapshots(context) }

    private fun kernel(recentRequests: Int) =
        Kernel(log, codec, states = LruStateCache(16), snapshots = snapshots, recentRequests = recentRequests)

    private fun command(ref: AggregateRef, body: Any, requestId: String = "request-${sequence.incrementAndGet()}") =
        Command("command-${sequence.incrementAndGet()}", requestId, ref, body)

    private suspend fun Kernel.trips(command: Command<*>): Pair<CommandOutcome, Int> {
        val mark = mark()
        val outcome = execute(model, command)
        return outcome to since(mark)
    }

    private fun ref(name: String) = model.type.ref("tenant", "$name-${UUID.randomUUID()}")

    @Test
    fun `a warm append is the append alone, one lookup less than D46`(): Unit = runBlocking {
        listOf(256 to 0, 0 to 1).forEach { (recent, lookups) ->
            val kernel = kernel(recent)
            val ref = ref("warm")
            kernel.execute(model, command(ref, Add(1))).assert().isInstanceOf(CommandOutcome.Committed::class.java)
            val (outcome, trips) = kernel.trips(command(ref, Add(1)))
            outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
            trips.assert().describedAs("recentRequests=$recent").isEqualTo(append + lookups)
        }
    }

    @Test
    fun `a retry the recent requests hold reads its version slot only, as the lookup did`(): Unit = runBlocking {
        val kernel = kernel(256)
        val ref = ref("retry")
        kernel.execute(model, command(ref, Add(1)))
        val first = kernel.execute(model, command(ref, Add(1), requestId = "same")) as CommandOutcome.Committed
        val (outcome, trips) = kernel.trips(command(ref, Add(1), requestId = "same"))
        outcome.assert().isEqualTo(CommandOutcome.AlreadyCommitted(first.stream))
        trips.assert().isEqualTo(1)
    }

    @Test
    fun `a creation retried beyond the recent requests is a duplicate, by the lookup before refusing`(): Unit =
        runBlocking {
            val kernel = kernel(2)
            val ref = ref("beyond")
            val created = kernel.execute(model, command(ref, Open("ahoo"), requestId = "open")) as CommandOutcome.Committed
            repeat(3) { kernel.execute(model, command(ref, Add(1))) }
            val (outcome, trips) = kernel.trips(command(ref, Open("ahoo"), requestId = "open"))
            outcome.assert().isEqualTo(CommandOutcome.AlreadyCommitted(created.stream))
            trips.assert().isEqualTo(1)
        }

    @Test
    fun `a warm refusal keeps the lookup and the version slot read`(): Unit = runBlocking {
        listOf(256, 0).forEach { recent ->
            val kernel = kernel(recent)
            val ref = ref("refused")
            kernel.execute(model, command(ref, Add(1)))
            val (outcome, trips) = kernel.trips(command(ref, Refuse("no")))
            outcome.assert().isInstanceOf(CommandOutcome.Rejected::class.java)
            trips.assert().describedAs("recentRequests=$recent").isEqualTo(2)
        }
    }

    @Test
    fun `a decide doing I-O skips the lookup only on the partition's owner`(): Unit = runBlocking {
        val kernel = kernel(256)
        val ref = ref("io")
        kernel.execute(model, command(ref, Add(1)))
        kernel.trips(command(ref, Check(1))).second.assert().isEqualTo(append + 1)
        withContext(ExecutionLease.HELD) { kernel.trips(command(ref, Check(1))) }.second.assert().isEqualTo(append)
    }

    @Test
    fun `a cold load reads the snapshot and the tail with the earlier requests in its load's round trips`(): Unit =
        runBlocking {
            val writer = Kernel(log, codec)
            val ref = ref("cold")
            repeat(3) { writer.execute(model, command(ref, Add(1))) }
            val third = writer.execute(model, command(ref, Add(1))) as CommandOutcome.Committed
            snapshots.save(codec.snapshotOf(model, ref, third.state as Counter, 4, Scope.NONE, 0, 0))
            listOf(256 to 0, 0 to 1).forEach { (recent, lookups) ->
                val (outcome, trips) = kernel(recent).trips(command(ref, Add(1)))
                outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
                trips.assert().describedAs("recentRequests=$recent").isEqualTo(load + lookups + append)
            }
        }

    private companion object {
        const val CONTEXT_SUFFIX = 12
    }
}

/** [KernelRoundTripsSpec] on PostgreSQL: a round trip is a statement. */
class PostgresKernelRoundTripsTest : KernelRoundTripsSpec() {
    override val append = 1

    /** The snapshot with the tail, one statement. */
    override val load = 1

    override fun log(context: String): EventLog =
        PostgresEventLog(Counted.dataSource, context, 4).also { it.createSchema() }

    override fun snapshots(context: String): SnapshotStore = PostgresSnapshotStore(Counted.dataSource, context)

    override fun mark(): Int = Counted.prepared.get()

    override fun since(mark: Int): Int = Counted.prepared.get() - mark

    private object Counted {
        val prepared = AtomicInteger()

        private val target: DataSource by lazy {
            val postgres = WowContainers.postgres
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = POOL_SIZE
                },
            )
        }

        val dataSource: DataSource by lazy { proxy(DataSource::class.java, target) }

        private fun <T : Any> proxy(type: Class<T>, delegate: T): T = type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, args ->
                    if (method.name == "prepareStatement") prepared.incrementAndGet()
                    val result = try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                    if (method.name == "getConnection") proxy(Connection::class.java, result as Connection) else result
                },
            ),
        )

        const val POOL_SIZE = 4
    }
}

/** [KernelRoundTripsSpec] on MongoDB: a round trip is a command the driver sends. */
class MongoKernelRoundTripsTest : KernelRoundTripsSpec() {
    /**
     * The insert, at a sequence of the node's block (decision D271): the version before it is the one the log saw last,
     * from the load or the previous append.
     */
    override val append = 1

    /** The snapshot, then the tail. */
    override val load = 2

    override fun log(context: String): EventLog =
        MongoEventLog(Counted.client, DATABASE, context, 4).also { it.createSchema() }

    override fun snapshots(context: String): SnapshotStore = MongoSnapshotStore(Counted.client, DATABASE, context)

    override fun mark(): Int = Counted.sent.get()

    override fun since(mark: Int): Int = Counted.sent.get() - mark

    private object Counted {
        val sent = AtomicInteger()

        private val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                if (event.commandName !in MONITORING) sent.incrementAndGet()
            }
        }

        val client: MongoClient by lazy {
            MongoClients.create(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(WowContainers.mongo.replicaSetUrl))
                    .addCommandListener(listener)
                    .build(),
            )
        }

        private val MONITORING = setOf("hello", "isMaster", "ismaster", "buildInfo", "endSessions", "ping")
    }

    private companion object {
        const val DATABASE = "wow_round_trips"
    }
}
