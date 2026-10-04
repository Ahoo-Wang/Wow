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

import io.mockk.mockk
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.kotlin.test.test

class RedisEventStoreTimeRangeTest {

    @Test
    fun `load by time should signal unsupported through the Flux instead of throwing`() {
        val eventStore = RedisEventStore(mockk<ReactiveStringRedisTemplate>())
        val aggregateId = MaterializedNamedAggregate("order-service", "order").aggregateId("order-1")

        eventStore.load(aggregateId, 0L, 1L)
            .test()
            .expectError(UnsupportedOperationException::class.java)
            .verify()
    }
}
