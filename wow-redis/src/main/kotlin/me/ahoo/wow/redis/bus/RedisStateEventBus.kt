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

package me.ahoo.wow.redis.bus

import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono
import java.time.Duration

class RedisStateEventBus(
    redisTemplate: ReactiveStringRedisTemplate,
    topicConverter: StateEventTopicConverter = DefaultStateEventTopicConverter,
    pollTimeout: Duration = Duration.ofSeconds(2)
) : DistributedStateEventBus,
    AbstractRedisMessageBus<StateEvent<*>, StateEventExchange<*>>(
        redisTemplate,
        topicConverter,
        pollTimeout,
    ) {
    override val messageType: Class<StateEvent<*>>
        get() = StateEvent::class.java

    override fun StateEvent<*>.toExchange(acknowledgePublisher: Mono<Void>): StateEventExchange<*> {
        return RedisStateEventExchange(
            this,
            acknowledgePublisher,
        )
    }
}
