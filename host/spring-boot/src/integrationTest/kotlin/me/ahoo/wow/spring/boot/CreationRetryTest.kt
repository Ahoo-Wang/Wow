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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A creation whose id admission assigns, retried with its request id while its first attempt is still deciding
 * (#6518): the first attempt's decide is held past its wait's deadline, so its caller is answered `wow.wait.timeout`
 * and retries the same request; the retry finds nothing committed yet; then the first attempt is let go and appends.
 * The retry's own draw falls in another partition than the first attempt's, so MongoDB's request key, unique per
 * partition, would never meet the two: the request's claim keeps them to one aggregate, and one request commits once on
 * every store.
 *
 * The order is fixed by the test, not by timing: the retry is sent only once the first attempt is inside its decide
 * and its caller has its answer, the first attempt is let go only once the retry's caller has its own, and the
 * commits are counted once a later attempt of the request, run after both, is answered. The ids are chosen to fall
 * in the partitions each case needs ([ids]).
 */
abstract class CreationRetrySpec {
    data class Place(val sku: String)
    data class Placed(val sku: String)

    class Order

    private val context = "retry_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)

    /** Completed once the first attempt is inside its decide. */
    private val deciding = CompletableDeferred<Unit>()

    /** Lets the first attempt's decide go on. */
    private val release = CompletableDeferred<Unit>()

    private val decides = AtomicInteger()
    private val model = aggregate<Order>(context, "order") {
        initial { Order() }
        decideSuspending<Place>(Creation.REQUIRED) { _, body ->
            if (decides.incrementAndGet() == 1) {
                deciding.complete(Unit)
                release.await()
            }
            Decision.events(Placed(body.sku))
        }
        evolve<Placed> { }
    }

    /** The log of [context] on this backend, its schema created, with [PARTITIONS] partitions. */
    protected abstract fun log(context: String): EventLog

    private val log by lazy { log(context) }

    /** Completed with the outcome of the first attempt's append. */
    private val firstAppended = CompletableDeferred<AppendOutcome>()

    private val recorded by lazy {
        object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
                log.append(stream, transport).also { outcome ->
                    if (stream.ref.id == ids.first()) firstAppended.complete(outcome)
                }
        }
    }

    /**
     * The ids the type's generator gives, in order: the first attempt's, then one in another partition of the log
     * (the retry's draw), then any other, which a caller of another scope draws from until one falls in the first's.
     */
    private val ids: List<String> by lazy {
        val first = "order-0"
        val second = generateSequence(1) { it + 1 }.map { "order-$it" }.first { partitionOf(it) != partitionOf(first) }
        listOf(first, second)
    }
    private val drawn = CopyOnWriteArrayList<String>()

    private fun partitionOf(id: String) = Partitioner.partitionOf(model.type.ref(TENANT, id), PARTITIONS)

    private val gateway by lazy {
        val origin = Origin("node-1", "127.0.0.1:9090", context)
        val registry = WaitRegistry()
        val coordinator = WaitCoordinator(
            Executor(Kernel(recorded), laneCapacity = 4),
            registry,
            SubscriptionDirectory.NONE,
            LocalSignalSender(origin.node, registry),
            origin,
            WaitOptions(maxTimeout = 30.seconds, defaultTimeout = WAIT),
        )
        CommandGateway(
            ModelRegistry(listOf(model)),
            mapOf(context to coordinator),
            ids = IdGenerators(
                aggregates = AggregateIdGenerator { ids.getOrElse(drawn.size) { "order-x$it" }.also(drawn::add) },
            ),
            committed = CommittedRequests.of(recorded),
        )
    }

    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    private val bob = Caller(TENANT, Scope("bob"), subject = "bob")
    private val service = Caller(subject = "service:checkout", service = true)

    private fun place(call: CallContext? = null, caller: Caller = alice) = ServerCommandRequest(
        context = context,
        aggregate = "order",
        command = "Place",
        body = CommandBody.of("""{"sku":"mug"}"""),
        caller = if (call == null) caller else service,
        aggregateId = null,
        requestId = REQUEST,
        call = call,
    )

    private fun call() = CallContext(
        commandId = "call-1",
        tenantId = TENANT,
        scope = Scope("alice"),
        causation = Causation("root-1", "checkout/t/alice@1"),
        operator = Operator("alice", TENANT),
    )

    /** The streams the request committed, among every id the generator gave: at most one. */
    private suspend fun commits(): List<EventStreamRecord> = drawn.distinct().flatMap { id ->
        log.read(model.type.ref(TENANT, id)).toList().filter { it.requestId == REQUEST }
    }

    /** The two attempts of one request, the first held past its wait's deadline, then a later one: their answers. */
    private suspend fun attempts(call: CallContext?): List<CommandResult> = coroutineScope {
        val first = async { gateway.execute(place(call = call)) }
        withTimeout(TIMEOUT) { deciding.await() }
        val timedOut = withTimeout(TIMEOUT) { first.await() }
        val retried = withTimeout(TIMEOUT) { gateway.execute(place(call = call)) }
        release.complete(Unit)
        withTimeout(TIMEOUT) { firstAppended.await() }
        val later = withTimeout(TIMEOUT) { gateway.execute(place(call = call)) }
        listOf(timedOut, retried, later)
    }

    /** What this backend answers a retry sent while the first attempt decides, and the id the request keeps. */
    protected abstract fun assertRetry(retried: CommandResult, ids: List<String>): String

    /** The first attempt's append outcome on this backend, the retry's commit or claim before it. */
    protected abstract fun assertFirstAppend(outcome: AppendOutcome)

    private suspend fun assertOneCreation(call: CallContext?) {
        val (timedOut, retried, later) = attempts(call)
        timedOut.error!!.code.assert().describedAs(timedOut.toString()).isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
        timedOut.ref.id.assert().isEqualTo(ids[0])
        val kept = assertRetry(retried, ids)
        assertFirstAppend(firstAppended.await())
        later.duplicate.assert().describedAs(later.toString()).isTrue()
        later.ref.id.assert().isEqualTo(kept)
        // Each attempt drew an id; the retry's fell in another partition than the first attempt's.
        drawn.take(2).assert().isEqualTo(ids)
        commits().map { it.ref.id }.assert().containsExactly(kept)
    }

    @Test
    fun `a retry through the public port while the first attempt decides commits one aggregate`(): Unit =
        runBlocking { assertOneCreation(call = null) }

    @Test
    fun `a retry of a saga's call while the first attempt decides commits one aggregate`(): Unit =
        runBlocking { assertOneCreation(call = call()) }

    @Test
    fun `a caller of another scope reusing the request is answered not found, and commits nothing`(): Unit =
        runBlocking {
            release.complete(Unit)
            val opened = gateway.execute(place())
            opened.status.assert().describedAs(opened.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
            opened.ref.id.assert().isEqualTo(ids[0])
            val stranger = gateway.execute(place(caller = bob))
            stranger.error!!.code.assert().describedAs(stranger.toString()).isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
            // An id of its own, so it learns nothing of alice's order, drawn in her order's partition.
            stranger.ref.id.assert().isNotEqualTo(ids[0])
            partitionOf(stranger.ref.id).assert().isEqualTo(partitionOf(ids[0]))
            commits().map { it.ref.id }.assert().containsExactly(ids[0])
        }

    protected companion object {
        const val TENANT = "acme"
        const val REQUEST = "checkout-1"
        const val PARTITIONS = 4
        const val CONTEXT_SUFFIX = 12
        val WAIT = 1.seconds
        val TIMEOUT = 20.seconds
    }
}

/**
 * [CreationRetrySpec] on MongoDB: its request key is unique per partition, so the retry takes the id the first attempt
 * claimed, and waits behind it on the one aggregate; once the first commits, the retry is its duplicate.
 */
class MongoCreationRetryTest : CreationRetrySpec() {
    override fun assertRetry(retried: CommandResult, ids: List<String>): String {
        retried.error!!.code.assert().describedAs(retried.toString()).isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
        retried.ref.id.assert().isEqualTo(ids[0])
        return ids[0]
    }

    override fun assertFirstAppend(outcome: AppendOutcome) {
        outcome.assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    override fun log(context: String): EventLog =
        MongoEventLog(client, DATABASE, context, PARTITIONS).also { it.createSchema() }

    private companion object {
        const val DATABASE = "wow_creation_retry"
        val client: MongoClient by lazy { MongoClients.create(WowContainers.mongoUrl) }
    }
}

/**
 * [CreationRetrySpec] on PostgreSQL: its request key is unique per table, so it keeps no claims; the retry commits
 * under its own id, and the first attempt's append meets it there and is its duplicate.
 */
class PostgresCreationRetryTest : CreationRetrySpec() {
    override fun assertRetry(retried: CommandResult, ids: List<String>): String {
        retried.status.assert().describedAs(retried.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        retried.duplicate.assert().isFalse()
        retried.ref.id.assert().isEqualTo(ids[1])
        return ids[1]
    }

    override fun assertFirstAppend(outcome: AppendOutcome) {
        outcome.assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
    }

    override fun log(context: String): EventLog = store.log(context, PARTITIONS).also { store.createSchemas() }

    private companion object {
        val store: Store by lazy {
            val postgres = WowContainers.postgres
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                },
            )
            ServiceLoader.load(StoreProvider::class.java).first { it.type == "postgres" }
                .open(StoreConfig.of("main", "postgres", resources = StoreResources.of(mapOf("data" to dataSource))))
        }
    }
}
