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

package me.ahoo.wow.mongo

import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketOpenException
import com.mongodb.MongoSocketReadException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteConcernException
import com.mongodb.ServerAddress
import com.mongodb.bulk.WriteConcernError
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.internal.connection.MongoWriteConcernWithResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.query.MongoSnapshotQueryBackend
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** A transient failure of MongoDB is the SPI's retryable [StorageUnavailableException]; a defect stays the driver's. */
class MongoFailuresTest {
    private val cart = AggregateType("shop", "cart")

    /** A port no server listens on: bound without listening, so no other process takes it while the test runs. */
    private val reserved = Socket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val address = ServerAddress("127.0.0.1", reserved.localPort)

    /** A client of a server nobody runs: every call fails to select a server within a short timeout. */
    private val unreachable: MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyToClusterSettings {
                it.hosts(listOf(address)).serverSelectionTimeout(SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            .applyToSocketSettings { it.connectTimeout(SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .build(),
    )

    @AfterEach
    fun close() {
        unreachable.close()
        reserved.close()
    }

    @Test
    fun `a socket, a server selection or a primary that failed should be transient`() {
        listOf(
            MongoSocketOpenException("Exception opening socket", address, IOException("Connection refused")),
            MongoSocketReadException("Prematurely reached end of stream", address),
            MongoTimeoutException("Timed out while waiting for a server"),
            MongoNotPrimaryException(BsonDocument(), address),
            MongoNodeIsRecoveringException(BsonDocument(), address),
            MongoException(SHUTDOWN_IN_PROGRESS, "shutdown in progress").apply {
                addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
            },
            MongoException(SHUTDOWN_IN_PROGRESS, "shutdown in progress").apply { addLabel("RetryableWriteError") },
        ).forEach { failure ->
            MongoFailures.isTransient(failure).assert().describedAs("$failure").isTrue()
            val translated = MongoFailures.translate(failure)
            translated.assert().isInstanceOf(StorageUnavailableException::class.java)
            translated.cause.assert().isSameAs(failure)
            translated.message.assert().isEqualTo("MongoDB is unavailable; retry later.")
        }
    }

    @Test
    fun `a defect should stay the driver's exception`() {
        val duplicate = MongoException(DUPLICATE_KEY, "E11000 duplicate key error")
        MongoFailures.isTransient(duplicate).assert().isFalse()
        MongoFailures.translate(duplicate).assert().isSameAs(duplicate)
    }

    @Test
    fun `a document beyond the size limit should be told from other defects`() {
        // Finding R2.2-2: the stream can never be stored, so it is refused rather than left unknown.
        MongoFailures.isTooLarge(MongoException(BSON_OBJECT_TOO_LARGE, "BSONObj size is invalid")).assert().isTrue()
        MongoFailures.isTooLarge(MongoException(DUPLICATE_KEY, "E11000 duplicate key error")).assert().isFalse()
    }

    @Test
    fun `the request id lookup on an unreachable server should throw the retryable error (C1 F2)`() {
        val log = MongoEventLog(
            unreachable,
            "wow",
            "shop",
            partitions = 1,
            dispatcher = Dispatchers.IO,
            sharding = UNSHARDED
        )
        assertThrows<StorageUnavailableException> {
            runBlocking { log.findByRequestId(cart, "tenant", "request-1") }
        }.causes().filterIsInstance<MongoTimeoutException>().assert().isNotEmpty()
    }

    @Test
    fun `a snapshot load on an unreachable server should throw the retryable error`() {
        val store = MongoSnapshotStore(unreachable, "wow", "shop", dispatcher = Dispatchers.IO, sharding = UNSHARDED)
        assertThrows<StorageUnavailableException> {
            runBlocking { store.load(cart.ref("tenant", "cart-1")) }
        }.causes().filterIsInstance<MongoTimeoutException>().assert().isNotEmpty()
    }

    @Test
    fun `a snapshot query whose server went away should throw the retryable error, a defect as it is`() {
        val reset = MongoSocketReadException("Prematurely reached end of stream", address)
        assertThrows<StorageUnavailableException> {
            MongoSnapshotQueryBackend.timed(null) { throw reset }
        }.cause.assert().isSameAs(reset)
        val duplicate = MongoException(DUPLICATE_KEY, "E11000 duplicate key error")
        assertThrows<MongoException> {
            MongoSnapshotQueryBackend.timed(null) { throw duplicate }
        }.assert().isSameAs(duplicate)
    }

    @Test
    fun `the leases, checkpoints, ledger and timers on an unreachable server should throw the retryable error`() {
        // Review A.5: they ran on withContext, so a server that could not be selected stayed the driver's exception.
        val calls: List<suspend () -> Any?> = listOf(
            { MongoLeaseStore(unreachable, "wow", dispatcher = Dispatchers.IO).owners("shop") },
            { MongoCheckpointStore(unreachable, "wow", Dispatchers.IO).load("shop", "projection", 0) },
            {
                MongoFailureLedger(unreachable, "wow", dispatcher = Dispatchers.IO)
                    .parked("projection", cart.ref("t", "c"))
            },
            {
                MongoTimerStore(
                    unreachable,
                    "wow",
                    "shop",
                    4,
                    Dispatchers.IO,
                    sharding = UNSHARDED
                ).find(cart.ref("t", "c"), "remind")
            },
        )
        calls.forEach { call ->
            assertThrows<StorageUnavailableException> { runBlocking { call() } }
                .causes().filterIsInstance<MongoTimeoutException>().assert().isNotEmpty()
        }
    }

    @Test
    fun `a write may have landed after a timeout, a failed socket or an unconfirmed write concern, not after an answer`() {
        // Finding A2-6, decision D271: an insert whose result is unknown leaves the outcome unknown. A write concern
        // a stepping-down primary interrupted applied the write there (roadmap §6 item 18, the failover test).
        val interrupted = MongoNodeIsRecoveringException(
            BsonDocument("code", BsonInt32(INTERRUPTED_DUE_TO_REPL_STATE_CHANGE)),
            address,
        )
        listOf(
            MongoTimeoutException("Timed out while waiting for a server"),
            MongoSocketReadException("Prematurely reached end of stream", address),
            MongoExecutionTimeoutException(MAX_TIME_EXPIRED, "operation exceeded time limit"),
            MongoWriteConcernException(
                WriteConcernError(
                    INTERRUPTED_DUE_TO_REPL_STATE_CHANGE,
                    "",
                    "operation was interrupted",
                    BsonDocument()
                ),
                address,
            ),
            MongoWriteConcernWithResponseException(interrupted, BsonDocument()),
        ).forEach { MongoFailures.isWriteUnknown(it).assert().describedAs("$it").isTrue() }
        listOf(
            MongoException(DUPLICATE_KEY, "E11000 duplicate key error"),
            MongoException(WRITE_CONFLICT, "WriteConflict"),
        ).forEach { MongoFailures.isWriteUnknown(it).assert().describedAs("$it").isFalse() }
    }

    @Test
    fun `an append to an unreachable server should throw the retryable error, never append`() {
        // Nothing reached the store: the command is answered as unavailable, not unknown (finding A2-6).
        val log = MongoEventLog(unreachable, "wow", "shop", 1, Dispatchers.IO, sharding = UNSHARDED)
        assertThrows<StorageUnavailableException> { runBlocking { log.append(stream()) } }
    }

    private fun stream() = EventStreamRecord.of(
        cart.ref("t", "c-1"),
        1,
        "command-1",
        "request-1",
        "shop.cart.Add",
        0L,
        listOf(EventRecord.of("shop.cart.Added", 1, "{}")),
        Scope.NONE,
    )

    private companion object {
        /** Not sharded: a store that may shard asks the server as it is built, which fails on this one. */
        val UNSHARDED = MongoSharding.NONE

        const val MAX_TIME_EXPIRED = 50
        const val INTERRUPTED_DUE_TO_REPL_STATE_CHANGE = 11602
        const val WRITE_CONFLICT = 112
        const val SELECTION_TIMEOUT_MS = 200L
        const val SHUTDOWN_IN_PROGRESS = 91
        const val DUPLICATE_KEY = 11000
        const val BSON_OBJECT_TOO_LARGE = 10334
    }
}

/** This throwable and its causes: a coroutine may rethrow a copy of what was thrown, with it as the cause. */
internal fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
