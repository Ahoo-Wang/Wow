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

import com.mongodb.client.result.InsertManyResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoEventStoreBatchPartialWindowTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `remaining requests should flush after an earlier partial batch completes`() {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertIndex = AtomicInteger()
        val firstInsert = Sinks.one<InsertManyResult>()
        val firstWriteSubscribed = CountDownLatch(1)
        val batchSizes = mutableListOf<Int>()
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            batchSizes += firstArg<List<Document>>().size
            if (insertIndex.getAndIncrement() == 0) {
                Mono.defer {
                    firstWriteSubscribed.countDown()
                    firstInsert.asMono()
                }
            } else {
                Mono.just(InsertManyResult.acknowledged(emptyMap()))
            }
        }
        val batcher = MongoEventStoreBatcher(
            database = database,
            options = MongoEventStoreBatchOptions(
                enabled = true,
                maxSize = 128,
                maxDelay = Duration.ofMillis(10),
            ),
            closeTimeout = Duration.ofMillis(200),
        )

        val firstWindow = (0 until 19).map {
            batcher.append(eventStream("order-$it")).toFuture()
        }
        firstWriteSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val remainingWindow = (19 until 128).map {
            batcher.append(eventStream("order-$it")).toFuture()
        }
        firstInsert.tryEmitValue(InsertManyResult.acknowledged(emptyMap()))
            .assert().isEqualTo(Sinks.EmitResult.OK)

        val allAppends = (firstWindow + remainingWindow).toTypedArray()
        CompletableFuture.allOf(*allAppends).get(1, TimeUnit.SECONDS)
        batcher.close()
        batchSizes.first().assert().isEqualTo(19)
        batchSizes.drop(1).sum().assert().isEqualTo(109)
    }

    private fun eventStream(id: String): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            eventCount = 1,
        )
    }
}
