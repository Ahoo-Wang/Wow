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

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.batch.BatchCoordinator
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.infra.batch.BatchWriter
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import org.bson.Document
import reactor.core.publisher.Mono
import java.time.Duration

internal data class MongoEventStreamAppend(
    val eventStream: DomainEventStream,
    val document: Document,
    val collectionName: String,
)

internal class BatchMongoEventStreamAppender(
    database: MongoDatabase,
    options: BatchOptions,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
    metrics: WowMetrics = WowMetrics.NONE,
) : MongoEventStreamAppender {
    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
    }

    private val coordinator = BatchCoordinator(
        name = MongoEventStore::class.simpleName!!,
        options = options,
        keySelector = { append: MongoEventStreamAppend ->
            append.eventStream.aggregateId
        },
        writer = BatchWriter(MongoEventStreamBatchWriter(database)::write),
        metrics = metrics,
    )

    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return coordinator.submit {
            MongoEventStreamAppend(
                eventStream = eventStream,
                document = eventStream.toDocument(),
                collectionName = eventStream.toEventStreamCollectionName(),
            )
        }
    }

    override fun close() {
        coordinator.close(closeTimeout)
    }

    private companion object {
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
