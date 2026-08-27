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
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.redis.RedisScripts
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.AGGREGATE_ID_INDEX_BUCKETS
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdFromIndexMember
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexKey
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexMember
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexMemberLowerBound
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexMemberPrefix
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
        internal val SCRIPT_EVENT_STREAM_APPEND: RedisScript<String> =
            RedisScripts.load("event_stream_append.lua", String::class.java)
    }

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        val aggregateId = eventStream.aggregateId
        val eventStreamKey = EventStreamKeyLayout.key(aggregateId)
        val aggregateIdIndexKey = aggregateId.toAggregateIdIndexKey()
        val requestIndexKey = EventStreamKeyLayout.requestIndexKey(aggregateId)
        return redisTemplate.execute(
            SCRIPT_EVENT_STREAM_APPEND,
            listOf(eventStreamKey, aggregateIdIndexKey, requestIndexKey),
            listOf(
                eventStream.requestId,
                eventStream.version.toString(),
                eventStream.toJsonString(),
                toAggregateIdIndexMember(aggregateId),
                toAggregateIdIndexMemberPrefix(aggregateId.id),
                toAggregateIdIndexMemberLowerBound(aggregateId.id),
            ),
        ).next()
            .switchIfEmpty(Mono.error(IllegalStateException("Redis EventStore append script returned no result.")))
            .flatMap { handleAppendResult(eventStream, it) }
    }

    private fun handleAppendResult(eventStream: DomainEventStream, result: String): Mono<Void> {
        return when (result) {
            ErrorCodes.SUCCEEDED -> Mono.empty()

            ErrorCodes.EVENT_VERSION_CONFLICT -> Mono.error(EventVersionConflictException(eventStream))

            ErrorCodes.DUPLICATE_AGGREGATE_ID -> Mono.error(DuplicateAggregateIdException(eventStream))

            ErrorCodes.DUPLICATE_REQUEST_ID -> Mono.error(
                DuplicateRequestIdException(
                    eventStream.aggregateId,
                    eventStream.requestId,
                )
            )

            else -> Mono.error(
                IllegalStateException("Unexpected Redis EventStore append result:[$result].")
            )
        }
    }

    override fun loadStream(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        val key = EventStreamKeyLayout.key(aggregateId)
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
        val requestIdxKey = EventStreamKeyLayout.requestIndexKey(aggregateId)
        return redisTemplate.opsForSet().isMember(requestIdxKey, requestId)
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        val key = EventStreamKeyLayout.key(aggregateId)
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
