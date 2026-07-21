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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.redis.RedisScripts
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.AGGREGATE_ID_INDEX_BUCKETS
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateIdFromIndexMember
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateIdIndexKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateIdIndexMember
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyConverter.toAggregateIdIndexMemberLowerBound
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
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
        internal const val AGGREGATE_ID_INDEX_SCAN_CONCURRENCY = 16

        val SCRIPT_EVENT_STEAM_APPEND: RedisScript<String> =
            RedisScripts.load("event_steam_append.lua", String::class.java)
    }

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        val aggregateId = eventStream.aggregateId
        val eventStreamKey = EventStreamKeyConverter.convert(aggregateId)
        val aggregateIdIndexKey = aggregateId.toAggregateIdIndexKey()
        return redisTemplate.execute(
            SCRIPT_EVENT_STEAM_APPEND,
            listOf(eventStreamKey, aggregateIdIndexKey),
            listOf(
                eventStream.requestId,
                eventStream.version.toString(),
                eventStream.toJsonString(),
                toAggregateIdIndexMember(aggregateId),
            ),
        ).next().flatMap {
            handleAppendResult(eventStream, it)
        }
    }

    private fun handleAppendResult(eventStream: DomainEventStream, result: String): Mono<Void> {
        return when (result) {
            ErrorCodes.EVENT_VERSION_CONFLICT -> Mono.error(EventVersionConflictException(eventStream))

            ErrorCodes.DUPLICATE_REQUEST_ID -> Mono.error(
                DuplicateRequestIdException(
                    eventStream.aggregateId,
                    eventStream.requestId,
                )
            )

            else -> Mono.empty()
        }
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

    override fun existsRequestId(aggregateId: AggregateId, requestId: String): Mono<Boolean> {
        val requestIdxKey = "${EventStreamKeyConverter.convert(aggregateId)}:req_idx"
        return redisTemplate.opsForSet().isMember(requestIdxKey, requestId)
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        val key = EventStreamKeyConverter.convert(aggregateId)
        val range = Range.closed<Long>(0, 0)
        return redisTemplate.opsForZSet().reverseRange(key, range)
            .map {
                it.toObject<DomainEventStream>()
            }.next()
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        if (afterId == AggregateIdScanner.LAST_ID) {
            return Flux.empty()
        }
        val range = Range.rightUnbounded(
            Range.Bound.exclusive(toAggregateIdIndexMemberLowerBound(afterId))
        )
        val rangeLimit = Limit.limit().count(limit)
        return Flux.range(0, AGGREGATE_ID_INDEX_BUCKETS)
            .flatMap(
                { bucket ->
                    scanAggregateIdBucket(namedAggregate, bucket, range, rangeLimit)
                },
                AGGREGATE_ID_INDEX_SCAN_CONCURRENCY,
            )
            .sort(compareBy { it.id })
            .take(limit.toLong())
    }

    private fun scanAggregateIdBucket(
        namedAggregate: NamedAggregate,
        bucket: Int,
        range: Range<String>,
        rangeLimit: Limit
    ): Flux<AggregateId> {
        val aggregateIdIndexKey = namedAggregate.toAggregateIdIndexKey(bucket)
        return redisTemplate.opsForZSet()
            .rangeByLex(aggregateIdIndexKey, range, rangeLimit)
            .map {
                toAggregateIdFromIndexMember(namedAggregate, it)
            }
    }
}
