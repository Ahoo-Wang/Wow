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

package me.ahoo.wow.redis

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.redis.bus.RedisCommandBus
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.redis.eventsourcing.RedisSnapshotRepository
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.modeling.command.CommandDispatcherSpec
import org.junit.jupiter.api.extension.RegisterExtension

class RedisCommandDispatcherTest : CommandDispatcherSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createSnapshotRepository(): SnapshotRepository {
        return RedisSnapshotRepository(redis.redisTemplate).metrizable()
    }

    override fun createEventStore(): EventStore {
        return RedisEventStore(redis.redisTemplate)
    }

    override fun createCommandBus(): CommandBus {
        return RedisCommandBus(redis.redisTemplate)
    }

    override fun createEventBus(): DomainEventBus {
        return RedisDomainEventBus(redis.redisTemplate)
    }
}
