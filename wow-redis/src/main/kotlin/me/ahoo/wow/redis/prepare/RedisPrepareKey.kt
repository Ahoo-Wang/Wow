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

package me.ahoo.wow.redis.prepare

import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PreparedValue
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toTtlAt
import me.ahoo.wow.redis.RedisScripts
import me.ahoo.wow.redis.eventsourcing.RedisWrappedKey.wrap
import me.ahoo.wow.redis.prepare.PrepareKeyConverter.toKey
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono

const val VALUE_FIELD = "value"
const val TTL_AT_FIELD = "ttlAt"

class RedisPrepareKey<V : Any>(
    override val name: String,
    private val valueType: Class<V>,
    private val redisTemplate: ReactiveStringRedisTemplate
) : PrepareKey<V> {
    companion object {
        val SCRIPT_PREPARE_PREPARE: RedisScript<Boolean> =
            RedisScripts.load("prepare_prepare.lua", Boolean::class.java)

        val SCRIPT_PREPARE_REPREPARE: RedisScript<Boolean> =
            RedisScripts.load("prepare_reprepare.lua", Boolean::class.java)

        val SCRIPT_PREPARE_REPREPARE_WITH_OLD_VALUE: RedisScript<Boolean> =
            RedisScripts.load("prepare_reprepare_with_old_value.lua", Boolean::class.java)

        val SCRIPT_PREPARE_ROLLBACK: RedisScript<Boolean> =
            RedisScripts.load("prepare_rollback.lua", Boolean::class.java)

        val SCRIPT_PREPARE_ROLLBACK_WITH_OLD_VALUE: RedisScript<Boolean> =
            RedisScripts.load("prepare_rollback_with_old_value.lua", Boolean::class.java)
    }

    private fun Map<String, String>.decode(): PreparedValue<V>? {
        if (isEmpty()) {
            return null
        }
        val ttlAt = checkNotNull(this[TTL_AT_FIELD]).toLong()
        val value = checkNotNull(this[VALUE_FIELD]).toObject(valueType)
        return value.toTtlAt(ttlAt)
    }

    override fun prepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_PREPARE,
            listOf(wrappedKey),
            listOf(
                System.currentTimeMillis().toString(),
                value.ttlAt.toString(),
                value.value.toJsonString(),
            ),
        ).next()
    }

    override fun getValue(key: String): Mono<PreparedValue<V>> {
        val redisKey = key.toKey()
        return redisTemplate.opsForHash<String, String>().entries(redisKey)
            .collectMap({ it.key }, { it.value })
            .mapNotNull {
                it.decode()
            }
    }

    override fun rollback(key: String): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_ROLLBACK,
            listOf(wrappedKey),
            listOf(
                System.currentTimeMillis().toString(),
            ),
        ).next()
    }

    override fun reprepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_REPREPARE,
            listOf(wrappedKey),
            listOf(
                value.ttlAt.toString(),
                value.value.toJsonString(),
            ),
        ).next()
    }

    override fun reprepare(key: String, oldValue: V, newValue: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_REPREPARE_WITH_OLD_VALUE,
            listOf(wrappedKey),
            listOf(
                newValue.ttlAt.toString(),
                newValue.value.toJsonString(),
                oldValue.toJsonString(),
            ),
        ).next()
    }

    override fun rollback(key: String, value: V): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_ROLLBACK_WITH_OLD_VALUE,
            listOf(wrappedKey),
            listOf(
                value.toJsonString(),
            ),
        ).next()
    }
}
