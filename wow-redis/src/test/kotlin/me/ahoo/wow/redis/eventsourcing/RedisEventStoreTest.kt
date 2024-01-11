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

package me.ahoo.wow.redis.eventsourcing

import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.redis.RedisInitializer
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import reactor.kotlin.test.test

class RedisEventStoreTest : EventStoreSpec() {
    protected lateinit var redisInitializer: RedisInitializer

    @BeforeEach
    override fun setup() {
        redisInitializer = RedisInitializer()
        super.setup()
    }

    @AfterEach
    fun destroy() {
        redisInitializer.close()
    }

    override fun createEventStore(): EventStore {
        return RedisEventStore(redisInitializer.redisTemplate)
    }

    override fun scanAggregateId() = Unit

    override fun tailCursorId() {
        eventStore.tailCursorId(namedAggregate)
            .test()
            .expectNext(AggregateIdScanner.FIRST_CURSOR_ID)
            .verifyComplete()
    }

    override fun archiveAggregateId() {
        eventStore.archiveAggregateId(namedAggregate)
            .test()
            .verifyComplete()

        eventStore.archiveAggregateId(namedAggregate, AggregateIdScanner.FIRST_CURSOR_ID)
            .test()
            .verifyComplete()
    }
}
