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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ConnectionDescription
import com.mongodb.connection.ServerId
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.EMPTY_ABAC_TAGS
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.toDocument
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendTestKit
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MongoQueryResourceBoundTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_query_resource")

    private lateinit var client: MongoClient
    private lateinit var database: MongoDatabase
    private lateinit var commandMonitor: MongoWireCommandMonitor
    private lateinit var backendFactory: MongoObservableQueryBackendFactory
    private lateinit var testKit: QueryBackendTestKit

    @BeforeEach
    fun prepareDocuments() {
        commandMonitor = MongoWireCommandMonitor()
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.connectionString))
            .addCommandListener(commandMonitor)
            .build()
        client = MongoClients.create(settings)
        database = client.getDatabase(mongo.databaseName)
        backendFactory = MongoObservableQueryBackendFactory(database, beforeUpstreamCancel = commandMonitor::beginCancel)
        testKit = QueryBackendTestKit(backendFactory, QueryDocumentKind.SNAPSHOT)
        val target = testKit.target
        val collectionName = target.namedAggregate.toSnapshotCollectionName()
        val collection = database.getCollection(collectionName)
        StepVerifier.create(
            Mono.from(collection.drop()).onErrorResume { Mono.empty() }
                .then(Mono.from(database.createCollection(collectionName)))
                .then(Mono.from(collection.insertMany(resourceDocuments()))),
        ).expectNextCount(1).verifyComplete()
        backendFactory.verifyRouteReadiness(
            QueryBackendResolutionContext(target, PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT), MatchAll),
        )
        backendFactory.reset()
        commandMonitor.reset()
    }

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @Test
    fun defaultMongoCursorCrossesThreeBoundedWireBatches() {
        val emitted = AtomicLong()
        StepVerifier.create(query().doOnNext { emitted.incrementAndGet() }, 0)
            .expectSubscription()
            .then { assertEquals(0, commandMonitor.started("find")) }
            .thenRequest(3)
            .expectNextCount(3)
            .thenRequest(253)
            .expectNextCount(253)
            .thenRequest(256)
            .expectNextCount(256)
            .thenRequest(88)
            .expectNextCount(88)
            .verifyComplete()

        assertEquals(600, emitted.get())
        assertEquals(1, commandMonitor.started("find"))
        assertTrue(commandMonitor.started("getMore") >= 2)
        assertTrue(commandMonitor.hasBoundedReadEvidence(DEFAULT_BATCH_SIZE))
        val batches = commandMonitor.batches().filter { batch -> batch.itemCount > 0 }
        assertTrue(batches.size >= 3)
        assertEquals(600, batches.sumOf(MongoWireBatch::itemCount))
        batches.forEach { batch ->
            val requestedBatchSize = checkNotNull(batch.requestedBatchSize)
            assertTrue(batch.itemCount <= requestedBatchSize)
            assertTrue(requestedBatchSize <= DEFAULT_BATCH_SIZE)
        }
        assertEquals(1, backendFactory.subscriptionCount)
        assertEquals(0, backendFactory.cancellationCount)
    }

    @Test
    fun cancellingFirstBatchClosesCursorWithoutPostCancelReads() {
        StepVerifier.create(query(), 0)
            .expectSubscription()
            .thenRequest(3)
            .expectNextCount(3)
            .thenCancel()
            .verify()

        assertTrue(commandMonitor.awaitKillCursor())
        assertEquals(1, commandMonitor.started("find"))
        assertEquals(0, commandMonitor.started("getMore"))
        assertEquals(1, commandMonitor.succeeded("killCursors"))
        assertEquals(0, commandMonitor.postCancelReads.get())
        assertTrue(commandMonitor.cancelPhase.get())
        assertEquals(1, backendFactory.subscriptionCount)
        assertEquals(1, backendFactory.cancellationCount)
    }

    @Test
    fun wireOracleRejectsMissingOrZeroBatchSizeAndObservesEmptyBatch() {
        listOf<Int?>(0, null).forEachIndexed { index, batchSize ->
            commandMonitor.reset()
            commandMonitor.commandStarted(startedFind(index + 1, batchSize))
            commandMonitor.commandSucceeded(succeededEmptyFind(index + 1))

            assertFalse(commandMonitor.hasBoundedReadEvidence(DEFAULT_BATCH_SIZE), "batchSize=$batchSize")
        }
    }

    private fun query() = testKit.gateway.list(
        ListQueryRequest(
            target = testKit.target,
            expression = MatchAll,
            resultShape = QueryResultShape.Typed(
                ResourceQueryResult::class.java,
                QueryProjection.Include(setOf(AGGREGATE_ID)),
            ),
            sort = listOf(QuerySort(AGGREGATE_ID, QuerySortDirection.ASC)),
            limit = 0,
        ),
    ).map(ResourceQueryResult::aggregateId)

    private fun resourceDocuments(): List<Document> = (1..DOCUMENT_COUNT).map { index ->
        val logicalId = "resource-${index.toString().padStart(4, '0')}"
        val aggregate = ResourceAggregate(
            testKit.target.namedAggregate.aggregateId(logicalId),
            ResourceState(logicalId),
        )
        SimpleSnapshot(aggregate, snapshotTime = index.toLong()).toDocument()
    }

    private fun startedFind(requestId: Int, batchSize: Int?): CommandStartedEvent {
        val command = BsonDocument("find", BsonString("resource"))
        batchSize?.let { command.append("batchSize", BsonInt32(it)) }
        return CommandStartedEvent(null, 1, requestId, connectionDescription(), "resource", "find", command)
    }

    private fun succeededEmptyFind(requestId: Int): CommandSucceededEvent {
        val cursor = BsonDocument("id", BsonInt64(0))
            .append("ns", BsonString("resource.collection"))
            .append("firstBatch", BsonArray())
        val response = BsonDocument("cursor", cursor).append("ok", BsonDouble(1.0))
        return CommandSucceededEvent(
            null,
            1,
            requestId,
            connectionDescription(),
            "resource",
            "find",
            response,
            1,
        )
    }

    private fun connectionDescription(): ConnectionDescription = ConnectionDescription(
        ServerId(ClusterId("query-resource-monitor"), ServerAddress()),
    )

    private companion object {
        const val DOCUMENT_COUNT: Int = 600
        const val DEFAULT_BATCH_SIZE: Int = 256
        val AGGREGATE_ID = me.ahoo.wow.api.query.expression.LogicalField("aggregateId")
    }
}

internal data class ResourceState(val logicalId: String)

private data class ResourceQueryResult(val aggregateId: String)

internal class ResourceAggregate(
    override val aggregateId: AggregateId,
    override val state: ResourceState,
) : ReadOnlyStateAggregate<ResourceState> {
    override val ownerId: String = "owner"
    override val spaceId: String = "space"
    override val version: Int = 1
    override val firstOperator: String = "operator"
    override val operator: String = "operator"
    override val firstEventTime: Long = 1
    override val eventTime: Long = 1
    override val eventId: String = "event"
    override val tags: AbacTags = EMPTY_ABAC_TAGS
    override val deleted: Boolean = false
}

internal data class MongoWireBatch(val requestedBatchSize: Int?, val itemCount: Int)

internal class MongoWireCommandMonitor : CommandListener {
    private data class StartedCommand(val name: String, val batchSize: Int?)

    private val startedCounts = ConcurrentHashMap<String, AtomicLong>()
    private val succeededCounts = ConcurrentHashMap<String, AtomicLong>()
    private val startedByRequest = ConcurrentHashMap<Int, StartedCommand>()
    private val readCommands = CopyOnWriteArrayList<StartedCommand>()
    private val batches = CopyOnWriteArrayList<MongoWireBatch>()
    private var killCursorSucceeded = CountDownLatch(1)
    val cancelPhase = AtomicBoolean()
    val postCancelReads = AtomicLong()

    override fun commandStarted(event: CommandStartedEvent) {
        val commandName = event.commandName
        startedCounts.computeIfAbsent(commandName) { AtomicLong() }.incrementAndGet()
        if (cancelPhase.get() && commandName in READ_COMMANDS) {
            postCancelReads.incrementAndGet()
        }
        if (commandName in READ_COMMANDS) {
            val command = StartedCommand(
                commandName,
                (event.command["batchSize"] as? org.bson.BsonNumber)?.intValue(),
            )
            startedByRequest[event.requestId] = command
            readCommands += command
        }
    }

    override fun commandSucceeded(event: CommandSucceededEvent) {
        succeededCounts.computeIfAbsent(event.commandName) { AtomicLong() }.incrementAndGet()
        startedByRequest.remove(event.requestId)?.let { command ->
            recordBatch(command, Document.parse(event.response.toJson()))
        }
        if (event.commandName == "killCursors") {
            killCursorSucceeded.countDown()
        }
    }

    fun beginCancel() {
        cancelPhase.set(true)
    }

    fun reset() {
        startedCounts.clear()
        succeededCounts.clear()
        startedByRequest.clear()
        readCommands.clear()
        batches.clear()
        cancelPhase.set(false)
        postCancelReads.set(0)
        killCursorSucceeded = CountDownLatch(1)
    }

    fun started(command: String): Long = startedCounts[command]?.get() ?: 0

    fun succeeded(command: String): Long = succeededCounts[command]?.get() ?: 0

    fun batches(): List<MongoWireBatch> = batches.toList()

    fun hasBoundedReadEvidence(maxBatchSize: Int): Boolean {
        if (readCommands.isEmpty() || readCommands.any { command ->
                command.batchSize == null || command.batchSize !in 1..maxBatchSize
            }
        ) {
            return false
        }
        val succeededReads = READ_COMMANDS.sumOf(::succeeded)
        if (batches.size.toLong() != succeededReads) {
            return false
        }
        return batches.all { batch ->
            val requestedBatchSize = batch.requestedBatchSize
            requestedBatchSize != null && batch.itemCount <= requestedBatchSize
        }
    }

    fun awaitKillCursor(): Boolean = killCursorSucceeded.await(5, TimeUnit.SECONDS)

    private fun recordBatch(command: StartedCommand, response: Document) {
        if (command.name !in READ_COMMANDS) {
            return
        }
        val copied = Document.parse(response.toJson())
        val cursor = copied["cursor"] as? Document ?: return
        val values = cursor.getList(if (command.name == "find") "firstBatch" else "nextBatch", Document::class.java)
        batches += MongoWireBatch(command.batchSize, values.size)
    }

    private companion object {
        val READ_COMMANDS: Set<String> = setOf("find", "getMore")
    }
}
