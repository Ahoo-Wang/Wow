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

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

data class RedisEventStreamExchange(
    override val message: DomainEventStream,
    private val acknowledge: Mono<Void>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : EventStreamExchange {
    override fun acknowledge(): Mono<Void> {
        return acknowledge
    }
}