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
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.messaging.transport.TransportStateEventBus
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

/**
 * The state event bus on Redis Streams: a [TransportStateEventBus] over a [RedisStreamTransport], with streams from [topicConverter].
 * Undecodable entries are reported to [messageBusObserver] and stay pending ([RedisRecordDecodeFailureHandler]).
 */
class RedisStateEventBus(
    redisTemplate: ReactiveStringRedisTemplate,
    topicConverter: StateEventTopicConverter = DefaultStateEventTopicConverter,
    pollTimeout: Duration = Duration.ofSeconds(2),
    recoveryOptions: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions.DEFAULT,
    messageBusObserver: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
    retentionOptions: RedisStreamRetentionOptions = RedisStreamRetentionOptions.DEFAULT,
    failurePolicy: TransportFailurePolicy = TransportFailurePolicy.DEFAULT,
) : TransportStateEventBus(
    transport = RedisStreamTransport(
        redisTemplate = redisTemplate,
        pollTimeout = pollTimeout,
        recoveryOptions = recoveryOptions,
        messageBusObserver = messageBusObserver,
        retentionOptions = retentionOptions,
        failurePolicy = failurePolicy,
    ),
    topicNaming = TopicNaming(topicConverter::convert),
    decodeFailureHandler = RedisRecordDecodeFailureHandler(messageBusObserver),
)
