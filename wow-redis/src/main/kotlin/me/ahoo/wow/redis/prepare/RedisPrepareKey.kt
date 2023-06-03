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
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.asTtlAt
import me.ahoo.wow.redis.RedisWrappedKey.wrap
import me.ahoo.wow.redis.prepare.PrepareKeyConverter.asKey
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
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
        private val RESOURCE_PREPARE_PREPARE: Resource = ClassPathResource("prepare_prepare.lua")
        val SCRIPT_PREPARE_PREPARE: RedisScript<Boolean> =
            RedisScript.of(RESOURCE_PREPARE_PREPARE, Boolean::class.java)

        private val RESOURCE_PREPARE_REPREPARE: Resource = ClassPathResource("prepare_reprepare.lua")
        val SCRIPT_PREPARE_REPREPARE: RedisScript<Boolean> =
            RedisScript.of(RESOURCE_PREPARE_REPREPARE, Boolean::class.java)

        private val RESOURCE_PREPARE_REPREPARE_WITH_OLD_VALUE: Resource =
            ClassPathResource("prepare_reprepare_with_old_value.lua")
        val SCRIPT_PREPARE_REPREPARE_WITH_OLD_VALUE: RedisScript<Boolean> =
            RedisScript.of(RESOURCE_PREPARE_REPREPARE_WITH_OLD_VALUE, Boolean::class.java)

        private val RESOURCE_PREPARE_ROLLBACK: Resource = ClassPathResource("prepare_rollback.lua")
        val SCRIPT_PREPARE_ROLLBACK: RedisScript<Boolean> =
            RedisScript.of(RESOURCE_PREPARE_ROLLBACK, Boolean::class.java)

        private val RESOURCE_PREPARE_ROLLBACK_WITH_OLD_VALUE: Resource =
            ClassPathResource("prepare_rollback_with_old_value.lua")
        val SCRIPT_PREPARE_ROLLBACK_WITH_OLD_VALUE: RedisScript<Boolean> =
            RedisScript.of(RESOURCE_PREPARE_ROLLBACK_WITH_OLD_VALUE, Boolean::class.java)
    }

    private fun Map<String, String>.decode(): PreparedValue<V>? {
        if (isEmpty()) {
            return null
        }
        val ttlAt = checkNotNull(this[TTL_AT_FIELD]).toLong()
        val value = checkNotNull(this[VALUE_FIELD]).asObject(valueType)
        return value.asTtlAt(ttlAt)
    }

    override fun prepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_PREPARE,
            listOf(wrappedKey),
            listOf(
                System.currentTimeMillis().toString(),
                value.ttlAt.toString(),
                value.value.asJsonString(),
            )
        ).next()
    }

    override fun getValue(key: String): Mono<PreparedValue<V>> {
        val redisKey = key.asKey()
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
                System.currentTimeMillis().toString()
            )
        ).next()
    }

    override fun reprepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_REPREPARE,
            listOf(wrappedKey),
            listOf(
                value.ttlAt.toString(),
                value.value.asJsonString(),
            )
        ).next()
    }

    override fun reprepare(key: String, oldValue: V, newValue: PreparedValue<V>): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_REPREPARE_WITH_OLD_VALUE,
            listOf(wrappedKey),
            listOf(
                newValue.ttlAt.toString(),
                newValue.value.asJsonString(),
                oldValue.asJsonString()
            )
        ).next()
    }

    override fun rollback(key: String, value: V): Mono<Boolean> {
        val wrappedKey = key.wrap()
        return redisTemplate.execute(
            SCRIPT_PREPARE_ROLLBACK_WITH_OLD_VALUE,
            listOf(wrappedKey),
            listOf(
                value.asJsonString()
            )
        ).next()
    }
}
