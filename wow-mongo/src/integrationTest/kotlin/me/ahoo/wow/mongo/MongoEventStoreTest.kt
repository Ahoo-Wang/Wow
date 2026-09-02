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
import me.ahoo.wow.api.Version
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.metrics.meteredForTck
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class MongoEventStoreTest : EventStoreSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createEventStore(): EventStore {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(namedAggregate)
        return MongoEventStore(database).meteredForTck()
    }

    // MongoDatabaseContextGuard rejects cross-context databases before the EventStore is created.
    override fun scanAggregateIdShouldFilterBoundedContext() = Unit

    @Test
    fun `batch append should persist all event streams`() {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(namedAggregate)
        val streams = (1..8).map { index ->
            generateEventStream(namedAggregate.aggregateId("batch-$index"))
        }
        val options = MongoEventStoreBatchOptions(
            enabled = true,
            maxSize = streams.size,
            maxDelay = Duration.ofMillis(10),
        )

        MongoEventStore(database, options).use { batchEventStore ->
            StepVerifier.create(
                Flux.fromIterable(streams)
                    .flatMap(batchEventStore::append)
                    .then()
            ).verifyComplete()

            StepVerifier.create(
                Flux.fromIterable(streams)
                    .flatMap { stream ->
                        batchEventStore.load(stream.aggregateId)
                    }.count()
            ).expectNext(streams.size.toLong())
                .verifyComplete()
        }
    }

    @Test
    fun `batch append should isolate a real version conflict`() {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(namedAggregate)
        val conflictAggregateId = namedAggregate.aggregateId("batch-version-conflict")
        val existing = MockDomainEventStreams.generateEventStream(
            aggregateId = conflictAggregateId,
            aggregateVersion = 1,
            eventCount = 1,
        )
        val conflict = MockDomainEventStreams.generateEventStream(
            aggregateId = conflictAggregateId,
            aggregateVersion = 1,
            eventCount = 1,
        )
        val valid = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId("batch-version-valid"),
            eventCount = 1,
        )
        MongoEventStore(database).append(existing).block()

        MongoEventStore(database, batchOptions(maxSize = 2)).use { batchEventStore ->
            val signals = Mono.zip(
                batchEventStore.append(conflict).materialize(),
                batchEventStore.append(valid).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(EventVersionConflictException::class.java)
            signals.t2.isOnComplete.assert().isTrue()
            batchEventStore.load(conflict.aggregateId).count().block().assert().isEqualTo(1)
            batchEventStore.load(valid.aggregateId).count().block().assert().isEqualTo(1)
        }
    }

    @Test
    fun `batch append should isolate a real duplicate request id`() {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(namedAggregate)
        val duplicateAggregateId = namedAggregate.aggregateId("batch-request-duplicate")
        val requestId = generateGlobalId()
        val existing = MockAggregateCreated(generateGlobalId()).toDomainEventStream(
            GivenInitializationCommand(duplicateAggregateId, requestId = requestId),
            Version.UNINITIALIZED_VERSION,
        )
        val duplicate = MockAggregateCreated(generateGlobalId()).toDomainEventStream(
            GivenInitializationCommand(duplicateAggregateId, requestId = requestId),
            Version.UNINITIALIZED_VERSION + 1,
        )
        val valid = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId("batch-request-valid"),
            eventCount = 1,
        )
        MongoEventStore(database).append(existing).block()

        MongoEventStore(database, batchOptions(maxSize = 2)).use { batchEventStore ->
            val signals = Mono.zip(
                batchEventStore.append(duplicate).materialize(),
                batchEventStore.append(valid).materialize(),
            ).block()!!

            signals.t1.throwable.assert().isInstanceOf(DuplicateRequestIdException::class.java)
            signals.t2.isOnComplete.assert().isTrue()
            batchEventStore.load(duplicate.aggregateId).count().block().assert().isEqualTo(1)
            batchEventStore.load(valid.aggregateId).count().block().assert().isEqualTo(1)
        }
    }

    @Test
    fun `batch append should partition one window by collection`() {
        val database = mongo.database()
        val otherNamedAggregate = MaterializedNamedAggregate(
            contextName = namedAggregate.contextName,
            aggregateName = "batch-other-aggregate",
        )
        EventStreamSchemaInitializer(database).initSchema(namedAggregate)
        EventStreamSchemaInitializer(database).initSchema(otherNamedAggregate)
        val first = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId("batch-first-collection"),
            eventCount = 1,
        )
        val second = MockDomainEventStreams.generateEventStream(
            aggregateId = otherNamedAggregate.aggregateId("batch-second-collection"),
            eventCount = 1,
        )

        MongoEventStore(database, batchOptions(maxSize = 2)).use { batchEventStore ->
            StepVerifier.create(
                Flux.merge(
                    batchEventStore.append(first),
                    batchEventStore.append(second),
                ).then()
            ).verifyComplete()

            batchEventStore.load(first.aggregateId).count().block().assert().isEqualTo(1)
            batchEventStore.load(second.aggregateId).count().block().assert().isEqualTo(1)
        }
    }

    private fun batchOptions(maxSize: Int): MongoEventStoreBatchOptions {
        return MongoEventStoreBatchOptions(
            enabled = true,
            maxSize = maxSize,
            maxDelay = Duration.ofMillis(10),
        )
    }
}
