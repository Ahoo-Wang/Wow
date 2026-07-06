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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class RedisSnapshotStoreScanTest {

    @Test
    fun `scan aggregate id should sort redis scan result before limiting`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val keys = listOf("003", "002", "004").map {
            DefaultSnapshotKeyConverter.convert(namedAggregate.aggregateId(it))
        }
        every { redisTemplate.scan(any()) } returns Flux.fromIterable(keys)
        val snapshotStore = RedisSnapshotStore(redisTemplate)

        snapshotStore.scanAggregateId(namedAggregate, afterId = "001", limit = 2)
            .collectList()
            .test()
            .consumeNextWith {
                it.map { aggregateId -> aggregateId.id }.assert().containsExactly("002", "003")
            }
            .verifyComplete()
    }
}
