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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisZSetCommands
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class RedisEventStore(private val redisTemplate: ReactiveStringRedisTemplate) : AbstractEventStore() {
    companion object {
        private val RESOURCE_EVENT_STEAM_APPEND: Resource = ClassPathResource("event_steam_append.lua")
        val SCRIPT_EVENT_STEAM_APPEND: RedisScript<String> =
            RedisScript.of(RESOURCE_EVENT_STEAM_APPEND, String::class.java)
    }

    private fun AggregateId.asEventStreamKey(): String {
        return "event_steam:${contextName}:${aggregateName}:${id}"
    }

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        ClassPathResource("event_stream.lua")
        val key = eventStream.aggregateId.asEventStreamKey()
        return redisTemplate.execute(
            SCRIPT_EVENT_STEAM_APPEND,
            listOf(key),
            listOf(eventStream.requestId, eventStream.version.toString(), eventStream.asJsonString()),
        ).doOnNext {
            when (it) {
                ErrorCodes.EVENT_VERSION_CONFLICT -> throw EventVersionConflictException(eventStream)
                ErrorCodes.DUPLICATE_REQUEST_ID -> throw DuplicateRequestIdException(
                    eventStream.aggregateId,
                    eventStream.requestId
                )
            }
        }
            .then()
    }

    override fun loadStream(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        val key = aggregateId.asEventStreamKey()
        val range = Range.closed(headVersion.toDouble(), tailVersion.toDouble())
        return redisTemplate.opsForZSet().rangeByScore(key, range, RedisZSetCommands.Limit.unlimited())
            .map {
                it.asObject<DomainEventStream>()
            }
    }
}