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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toKey
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class RedisEventStore(
    private val redisTemplate: ReactiveStringRedisTemplate
) : AbstractEventStore() {
    companion object {
        private val RESOURCE_EVENT_STEAM_APPEND: Resource = ClassPathResource("event_steam_append.lua")
        val SCRIPT_EVENT_STEAM_APPEND: RedisScript<String> =
            RedisScript.of(RESOURCE_EVENT_STEAM_APPEND, String::class.java)
    }

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        val aggregateKey = eventStream.aggregateId.toKey()
        return redisTemplate.execute(
            SCRIPT_EVENT_STEAM_APPEND,
            listOf(aggregateKey),
            listOf(
                eventStream.getContextAlias(),
                eventStream.aggregateName,
                eventStream.requestId,
                eventStream.version.toString(),
                eventStream.toJsonString(),
            ),
        ).doOnNext {
            when (it) {
                ErrorCodes.EVENT_VERSION_CONFLICT -> throw EventVersionConflictException(eventStream)
                ErrorCodes.DUPLICATE_REQUEST_ID -> throw DuplicateRequestIdException(
                    eventStream.aggregateId,
                    eventStream.requestId,
                )
            }
        }.then()
    }

    override fun loadStream(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        val key = EventStreamKeyConverter.convert(aggregateId)
        val range = Range.closed(headVersion.toDouble(), tailVersion.toDouble())
        return redisTemplate.opsForZSet().rangeByScore(key, range, Limit.unlimited())
            .map {
                it.toObject<DomainEventStream>()
            }
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        throw UnsupportedOperationException()
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        val key = EventStreamKeyConverter.convert(aggregateId)
        val range = Range.closed<Long>(0, 0)
        return redisTemplate.opsForZSet().reverseRange(key, range)
            .map {
                it.toObject<DomainEventStream>()
            }.next()
    }
}
