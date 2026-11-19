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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A creation whose id admission assigns, retried with its request id while its first attempt is still deciding
 * (#6518): the first attempt's decide is held past its wait's deadline, so its caller is answered `wow.wait.timeout`
 * and retries the same request; the retry's lookup finds nothing committed and draws a second id, in another
 * partition; then the first attempt is let go and appends.
 *
 * The order is fixed by the test, not by timing: the retry is sent only once the first attempt is inside its decide
 * and its caller has its answer, and the first attempt is let go only once the retry's caller has its own; the
 * first attempt's append is awaited before anything is counted. The two ids are chosen to fall in two partitions of
 * the log, so the per-partition request key of MongoDB is never the one that meets them.
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

    /** The outcome of each append, by the aggregate it appended to. */
    private val appends = ConcurrentLinkedQueue<Pair<AggregateRef, AppendOutcome>>()

    /** Completed with the outcome of the first attempt's append. */
    private val firstAppended = CompletableDeferred<AppendOutcome>()

    private val recorded by lazy {
        object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
                log.append(stream, transport).also { outcome ->
                    appends += stream.ref to outcome
                    if (stream.ref.id == ids.first()) firstAppended.complete(outcome)
                }
        }
    }

    /** Two ids of the type that fall in two partitions of the log: the first attempt's, then the retry's. */
    private val ids: List<String> by lazy {
        val type = model.type
        val first = "order-0"
        val second = generateSequence(1) { it + 1 }.map { "order-$it" }.first {
            Partitioner.partitionOf(type.ref(TENANT, it), PARTITIONS) !=
                Partitioner.partitionOf(type.ref(TENANT, first), PARTITIONS)
        }
        listOf(first, second)
    }
    private val drawn = AtomicInteger()

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
            ids = IdGenerators(aggregates = AggregateIdGenerator { ids[drawn.getAndIncrement()] }),
            committed = CommittedRequests.of(recorded),
        )
    }

    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    private val service = Caller(subject = "service:checkout", service = true)

    private fun place(requestId: String = REQUEST, call: CallContext? = null) = ServerCommandRequest(
        context = context,
        aggregate = "order",
        command = "Place",
        body = CommandBody.of("""{"sku":"mug"}"""),
        caller = if (call == null) alice else service,
        aggregateId = null,
        requestId = requestId,
        call = call,
    )

    private fun call() = CallContext(
        commandId = "call-1",
        tenantId = TENANT,
        scope = Scope("alice"),
        causation = Causation("root-1", "checkout/t/alice@1"),
        operator = Operator("alice", TENANT),
    )

    /** The aggregates the request committed, among the two ids drawn. */
    private suspend fun committed(): List<String> = ids.filter { id ->
        log.read(model.type.ref(TENANT, id), 1, 1).firstOrNull()?.requestId == REQUEST
    }

    /** The two attempts of one request, the first held past its wait's deadline; the retry's result. */
    private suspend fun retriedWhileDeciding(call: CallContext?): Pair<CommandResult, CommandResult> = coroutineScope {
        val first = async { gateway.execute(place(call = call)) }
        withTimeout(TIMEOUT) { deciding.await() }
        val timedOut = withTimeout(TIMEOUT) { first.await() }
        val retried = withTimeout(TIMEOUT) { gateway.execute(place(call = call)) }
        release.complete(Unit)
        withTimeout(TIMEOUT) { firstAppended.await() }
        timedOut to retried
    }

    /** What both backends answer the two attempts' callers. */
    private fun assertAnswers(timedOut: CommandResult, retried: CommandResult) {
        timedOut.error!!.code.assert().describedAs(timedOut.toString()).isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
        timedOut.ref.id.assert().isEqualTo(ids[0])
        retried.status.assert().describedAs(retried.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        retried.duplicate.assert().isFalse()
        retried.ref.id.assert().isEqualTo(ids[1])
        drawn.get().assert().isEqualTo(2)
    }

    /** How many aggregates the one request committed once both attempts appended: [expected] on this backend. */
    protected abstract val expected: Int

    /** The first attempt's append outcome on this backend, once the retry committed. */
    protected abstract fun assertFirstAppend(outcome: AppendOutcome)

    /** A third attempt of the request, once both appended: a duplicate, of the aggregate this backend kept. */
    private fun assertLater(later: CommandResult) {
        later.duplicate.assert().describedAs(later.toString()).isTrue()
        later.ref.id.assert().describedAs("later: $later").isIn(ids)
    }

    @Test
    fun `a retry through the public port while the first attempt decides`(): Unit = runBlocking {
        val (timedOut, retried) = retriedWhileDeciding(call = null)
        assertAnswers(timedOut, retried)
        assertFirstAppend(firstAppended.await())
        committed().size.assert().describedAs("committed: ${committed()}").isEqualTo(expected)
        assertLater(gateway.execute(place()))
    }

    @Test
    fun `a retry of a saga's call while the first attempt decides`(): Unit = runBlocking {
        val (timedOut, retried) = retriedWhileDeciding(call = call())
        assertAnswers(timedOut, retried)
        assertFirstAppend(firstAppended.await())
        committed().size.assert().describedAs("committed: ${committed()}").isEqualTo(expected)
        assertLater(gateway.execute(place(call = call())))
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
 * [CreationRetrySpec] on MongoDB: its request key is unique per partition, so two attempts of one request in two
 * partitions both commit, two aggregates of one request.
 */
class MongoCreationRetryTest : CreationRetrySpec() {
    override val expected = 2

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
 * [CreationRetrySpec] on PostgreSQL: its request key is unique per table, so the first attempt's append meets the
 * retry's commit and is answered as its duplicate; one aggregate.
 */
class PostgresCreationRetryTest : CreationRetrySpec() {
    override val expected = 1

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
