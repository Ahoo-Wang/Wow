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
package me.ahoo.wow.r2dbc

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.sharding.CosIdShardingDecorator
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.assertThrows
import reactor.kotlin.test.test

internal class R2dbcEventStoreTest : EventStoreSpec() {
    override fun createEventStore(): EventStore {
        return R2dbcEventStore(
            SimpleDatabase(ConnectionFactoryProviders.create(2)),
            ShardingEventStreamSchema(
                CosIdShardingDecorator(
                    ModCycle(4, namedAggregate.aggregateName + "_" + EVENT_STREAM_LOGIC_NAME_PREFIX)
                ),
            ),
        ).metrizable()
    }

    override fun scanAggregateId() {
        assertThrows<NotImplementedError> { eventStore.scanAggregateId(namedAggregate) }
    }

    override fun tailCursorId() {
        eventStore.tailCursorId(namedAggregate)
            .test()
            .expectNext(AggregateIdScanner.FIRST_CURSOR_ID)
            .verifyComplete()
    }
}
