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
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoEventStoreBatchSettledResultTest {
    private val namedAggregate = MaterializedNamedAggregate("order-service", "order")

    @Test
    fun `settled append results should survive a later close timeout`() {
        val fixture = createSaturatedResultFixture()
        val settledAppends = mutableListOf<CompletableFuture<Void?>>()

        try {
            repeat(10) { index ->
                var append = fixture.batcher.append(eventStream("order-$index"))
                if (index in setOf(0, 2, 4, 6)) {
                    append = append.doOnSuccess {
                        fixture.resultCallbacksStarted.countDown()
                        fixture.releaseResultCallbacks.await()
                    }
                }
                settledAppends += append.toFuture()
            }
            val blockedAppends = listOf(
                fixture.batcher.append(eventStream("order-10")).toFuture(),
                fixture.batcher.append(eventStream("order-11")).toFuture(),
            )
            fixture.resultCallbacksStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            fixture.blockedWriteSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            val closeError = assertThrows<MongoEventStoreBatchCloseTimeoutException> {
                fixture.batcher.close()
            }
            settledAppends[8].isDone.assert().isFalse()
            settledAppends[9].isDone.assert().isFalse()
            blockedAppends.forEach {
                assertThrows<CompletionException>(it::join)
                    .cause.assert().isSameAs(closeError)
            }

            fixture.releaseResultCallbacks.countDown()
            settledAppends.forEach(CompletableFuture<Void?>::join)
        } finally {
            fixture.releaseResultCallbacks.countDown()
        }
    }

    private fun createSaturatedResultFixture(): SaturatedResultFixture {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val insertIndex = AtomicInteger()
        val fixture = SaturatedResultFixture(
            batcher = BatchMongoEventStreamAppender(
                database = database,
                options = MongoEventStoreBatchOptions(
                    enabled = true,
                    maxSize = 2,
                    maxDelay = Duration.ofHours(1),
                    maxPendingAppends = 32,
                ),
                closeTimeout = Duration.ofMillis(50),
            ),
        )
        every { database.getCollection(any<String>()) } returns collection
        every {
            collection.insertMany(any<List<Document>>(), any())
        } answers {
            if (insertIndex.getAndIncrement() < 5) {
                Mono.just(InsertManyResult.acknowledged(emptyMap()))
            } else {
                Mono.defer {
                    fixture.blockedWriteSubscribed.countDown()
                    Mono.never()
                }
            }
        }
        return fixture
    }

    private fun eventStream(id: String): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(id),
            eventCount = 1,
        )
    }

    private data class SaturatedResultFixture(
        val batcher: BatchMongoEventStreamAppender,
        val blockedWriteSubscribed: CountDownLatch = CountDownLatch(1),
        val resultCallbacksStarted: CountDownLatch = CountDownLatch(4),
        val releaseResultCallbacks: CountDownLatch = CountDownLatch(1),
    )
}
