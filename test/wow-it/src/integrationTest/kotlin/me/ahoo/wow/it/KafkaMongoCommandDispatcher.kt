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

package me.ahoo.wow.it

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.kafka.KafkaCommandBus
import me.ahoo.wow.kafka.KafkaDomainEventBus
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.mongo.EventStreamSchemaInitializer
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.tck.container.KafkaTestFixture
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.modeling.command.CommandDispatcherSpec
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

class KafkaMongoCommandDispatcher : CommandDispatcherSpec() {
    @JvmField
    @RegisterExtension
    val kafka = KafkaTestFixture()

    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createEventStore(): EventStore {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(aggregateMetadata.namedAggregate)
        return MongoEventStore(database).metrizable()
    }

    private val onCommandSeekSink = Sinks.empty<Void>()
    override fun onCommandSeek(): Mono<Void> {
        return onCommandSeekSink.asMono()
    }

    override fun createCommandBus(): CommandBus {
        return KafkaCommandBus(
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
            receiverOptionsCustomizer = { options ->
                options
                    .addAssignListener { partitions ->
                        partitions.forEach {
                            it.seekToEnd()
                            onCommandSeekSink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST)
                        }
                    }
            },
        ).metrizable()
    }

    override fun createEventBus(): DomainEventBus {
        return KafkaDomainEventBus(
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
        )
    }
}
