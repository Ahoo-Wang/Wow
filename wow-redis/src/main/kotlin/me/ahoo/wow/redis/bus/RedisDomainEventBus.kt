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

import me.ahoo.wow.messaging.transport.TopicNaming
import me.ahoo.wow.messaging.transport.TransportDomainEventBus
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

/**
 * The domain event stream bus on Redis Streams: a [TransportDomainEventBus] over a [RedisStreamTransport], with streams from [topicConverter].
 * Undecodable entries are reported to [messageBusObserver] and stay pending ([RedisRecordDecodeFailureHandler]).
 */
class RedisDomainEventBus(
    redisTemplate: ReactiveStringRedisTemplate,
    topicConverter: EventStreamTopicConverter = DefaultEventStreamTopicConverter,
    pollTimeout: Duration = Duration.ofSeconds(2),
    recoveryOptions: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions.DEFAULT,
    messageBusObserver: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
    retentionOptions: RedisStreamRetentionOptions = RedisStreamRetentionOptions.DEFAULT,
) : TransportDomainEventBus(
    transport = RedisStreamTransport(
        redisTemplate = redisTemplate,
        pollTimeout = pollTimeout,
        recoveryOptions = recoveryOptions,
        messageBusObserver = messageBusObserver,
        retentionOptions = retentionOptions,
    ),
    topicNaming = TopicNaming(topicConverter::convert),
    decodeFailureHandler = RedisRecordDecodeFailureHandler(messageBusObserver),
)
