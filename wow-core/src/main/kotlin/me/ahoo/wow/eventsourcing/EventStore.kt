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

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * EventStore .
 * @author ahoo wang
 */
interface EventStore : AggregateIdScanner {
    /**
     * Append event stream to EventStore.
     * Ensure transaction consistency.
     */
    @Throws(
        EventVersionConflictException::class,
        DuplicateAggregateIdException::class,
        DuplicateRequestIdException::class,
    )
    fun append(eventStream: DomainEventStream): Mono<Void>

    /**
     * 根据聚合ID加载事件流.
     * ``` kotlin
     *  val offset=headVersion-1;
     *  val limit=tailVersion-headVersion+1;
     * ```
     * [headVersion~tailVersion]
     *
     * @param aggregateId 聚合ID
     * @param headVersion 事件流的第一个事件版本号，当 `headVersion`=H 时，即从事件版本号 H (包括)开始加载事件流。
     * @param tailVersion 事件流到最后一个版本号，包括.
     * @return 事件流
     */
    fun load(
        aggregateId: AggregateId,
        headVersion: Int = DEFAULT_HEAD_VERSION,
        tailVersion: Int = Int.MAX_VALUE
    ): Flux<DomainEventStream>

    companion object {
        const val DEFAULT_HEAD_VERSION: Int = 1
    }
}
