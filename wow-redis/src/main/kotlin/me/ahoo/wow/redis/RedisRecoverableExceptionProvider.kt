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

import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.RecoverableExceptionProvider
import me.ahoo.wow.exception.RecoverableExceptionRegistrar
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException

/**
 * Registers the transient Redis failures as [RecoverableType.RECOVERABLE], so the framework's retries
 * (`RetryableFilter`, the event-store append resolution) retry them:
 * - a lost or refused connection: Spring's [RedisConnectionFailureException] and Lettuce's [RedisConnectionException];
 * - a command timeout: Lettuce's [RedisCommandTimeoutException] and the [QueryTimeoutException] Spring translates it
 *   to.
 */
class RedisRecoverableExceptionProvider : RecoverableExceptionProvider {
    override fun register(registrar: RecoverableExceptionRegistrar) {
        registrar.register(RedisConnectionFailureException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(RedisConnectionException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(RedisCommandTimeoutException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(QueryTimeoutException::class.java, RecoverableType.RECOVERABLE)
    }
}
