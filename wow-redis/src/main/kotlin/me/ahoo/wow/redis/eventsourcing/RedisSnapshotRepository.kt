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
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.redis.eventsourcing.DefaultSnapshotKeyConverter.toKeyPrefix
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ScanOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class RedisSnapshotRepository(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val keyConverter: AggregateKeyConverter = DefaultSnapshotKeyConverter
) : SnapshotRepository {
    companion object {
        const val NAME = "redis"
    }

    override val name: String
        get() = NAME

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        val snapshotKey = keyConverter.convert(aggregateId)
        return redisTemplate.opsForValue()
            .get(snapshotKey)
            .map { it.toObject<Snapshot<S>>() }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        val snapshotKey = keyConverter.convert(snapshot.aggregateId)
        return redisTemplate.opsForValue()
            .set(snapshotKey, snapshot.toJsonString())
            .then()
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        val keyPrefix = namedAggregate.toKeyPrefix()
        val keyPattern = "$keyPrefix*"
        val options = ScanOptions.scanOptions().match(keyPattern)
            .type(DataType.STRING)
            .count(limit.toLong()).build()
        return redisTemplate.scan(options)
            .map {
                DefaultSnapshotKeyConverter.toAggregateId(namedAggregate, it)
            }.filter {
                it.id > afterId
            }
    }
}
