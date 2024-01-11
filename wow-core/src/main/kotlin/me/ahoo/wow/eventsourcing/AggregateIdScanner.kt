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
import me.ahoo.wow.api.modeling.NamedAggregate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface AggregateIdScanner {
    companion object {
        const val FIRST_CURSOR_ID = "(0)"
    }

    fun tailCursorId(namedAggregate: NamedAggregate): Mono<String> {
        return Mono.just(FIRST_CURSOR_ID)
    }

    fun archiveAggregateId(namedAggregate: NamedAggregate): Mono<Void> {
        return Mono.empty()
    }

    fun archiveAggregateId(namedAggregate: NamedAggregate, tailCursorId: String): Mono<Void> {
        return Mono.empty()
    }

    fun scanAggregateId(
        namedAggregate: NamedAggregate,
        cursorId: String = FIRST_CURSOR_ID,
        limit: Int = 10
    ): Flux<AggregateId>
}
