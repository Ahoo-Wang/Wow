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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toMono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MongoDatabaseContextGuardTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    @AfterEach
    fun cleanup() {
        val database = mongo.database()
        database.getCollection(MongoDatabaseContextGuard.METADATA_COLLECTION_NAME).drop().toMono().block()
        COLLECTIONS.forEach { collection ->
            database.getCollection(collection).drop().toMono().block()
        }
    }

    @Test
    fun `should reject a blank context name`() {
        assertThrows<IllegalArgumentException> {
            MongoDatabaseContextGuard(mongo.database()).ensureContext(" ")
        }
    }

    @Test
    fun `should claim an empty database and allow the same context`() {
        val guard = MongoDatabaseContextGuard(mongo.database())
        guard.ensureContext("order-service")
        guard.ensureContext("order-service")

        val marker = mongo.database()
            .getCollection(MongoDatabaseContextGuard.METADATA_COLLECTION_NAME)
            .find()
            .first()
            .toMono()
            .block()!!
        marker.getString(MessageRecords.CONTEXT_NAME).assert().isEqualTo("order-service")
        marker.getInteger(MongoDatabaseContextGuard.LAYOUT_VERSION_FIELD).assert().isEqualTo(1)
    }

    @Test
    fun `should reject a different context with actionable diagnostics`() {
        val guard = MongoDatabaseContextGuard(mongo.database())
        guard.ensureContext("order-service")

        val error = assertThrows<IllegalStateException> {
            guard.ensureContext("payment-service")
        }

        error.message.assert()
            .contains(mongo.database().name)
            .contains("order-service")
            .contains("payment-service")
            .contains("one bounded context per MongoDB database")
    }

    @Test
    fun `should reject an unsupported ownership marker layout`() {
        mongo.database()
            .getCollection(MongoDatabaseContextGuard.METADATA_COLLECTION_NAME)
            .insertOne(
                Document(Documents.ID_FIELD, "boundedContext")
                    .append(MessageRecords.CONTEXT_NAME, "order-service")
                    .append(MongoDatabaseContextGuard.LAYOUT_VERSION_FIELD, 2),
            )
            .toMono()
            .block()

        val error = assertThrows<IllegalStateException> {
            MongoDatabaseContextGuard(mongo.database()).ensureContext("order-service")
        }
        error.message.assert().contains("unsupported context ownership layout version", "[2]", "[1]")
    }

    @Test
    fun `should reject an ownership marker without a layout version`() {
        mongo.database()
            .getCollection(MongoDatabaseContextGuard.METADATA_COLLECTION_NAME)
            .insertOne(
                Document(Documents.ID_FIELD, "boundedContext")
                    .append(MessageRecords.CONTEXT_NAME, "order-service"),
            )
            .toMono()
            .block()

        val error = assertThrows<IllegalStateException> {
            MongoDatabaseContextGuard(mongo.database()).ensureContext("order-service")
        }
        error.message.assert().contains("unsupported context ownership layout version", "[null]", "[1]")
    }

    @Test
    fun `should atomically select one owner when different contexts claim concurrently`() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val claims = listOf("order-service", "payment-service").map { contextName ->
                executor.submit<Result<Unit>> {
                    start.await()
                    runCatching {
                        MongoDatabaseContextGuard(mongo.database()).ensureContext(contextName)
                    }
                }
            }
            start.countDown()

            val results = claims.map { claim -> claim.get(10, TimeUnit.SECONDS) }
            results.count(Result<Unit>::isSuccess).assert().isEqualTo(1)
            results.count(Result<Unit>::isFailure).assert().isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `should reject a foreign context after a matching legacy document`() {
        mongo.database()
            .getCollection(TEST_EVENT_STREAM_COLLECTION)
            .insertMany(
                listOf(
                    Document(MessageRecords.CONTEXT_NAME, "order-service"),
                    Document(MessageRecords.CONTEXT_NAME, "payment-service"),
                ),
            )
            .toMono()
            .block()

        val error = assertThrows<IllegalStateException> {
            MongoDatabaseContextGuard(mongo.database()).ensureContext("order-service")
        }

        error.message.assert()
            .contains(TEST_EVENT_STREAM_COLLECTION)
            .contains("payment-service")
            .contains("order-service")
        mongo.database()
            .getCollection(MongoDatabaseContextGuard.METADATA_COLLECTION_NAME)
            .find()
            .first()
            .toMono()
            .block()
            .assert()
            .isNull()
    }

    @Test
    fun `should scan event snapshot and checkpoint collections before claiming`() {
        listOf(TEST_EVENT_STREAM_COLLECTION, TEST_SNAPSHOT_COLLECTION, TEST_CHECKPOINT_COLLECTION)
            .forEach { collectionName ->
                mongo.database()
                    .getCollection(collectionName)
                    .insertOne(Document(MessageRecords.CONTEXT_NAME, "legacy-order-service"))
                    .toMono()
                    .block()

                val error = assertThrows<IllegalStateException> {
                    MongoDatabaseContextGuard(mongo.database()).ensureContext("payment-service")
                }
                error.message.assert()
                    .contains(collectionName)
                    .contains("legacy-order-service")
                    .contains("payment-service")
                mongo.database().getCollection(collectionName).drop().toMono().block()
            }
    }

    companion object {
        private const val TEST_EVENT_STREAM_COLLECTION = "contextGuardTest_event_stream"
        private const val TEST_SNAPSHOT_COLLECTION = "contextGuardTest_snapshot"
        private const val TEST_CHECKPOINT_COLLECTION = "contextGuardTest_snapshot_checkpoint"
        private val COLLECTIONS = listOf(
            TEST_EVENT_STREAM_COLLECTION,
            TEST_SNAPSHOT_COLLECTION,
            TEST_CHECKPOINT_COLLECTION,
        )
    }
}
