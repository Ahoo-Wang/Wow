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

package me.ahoo.wow.query.event

import me.ahoo.wow.api.modeling.NamedAggregate

fun interface EventStreamQueryServiceFactory {
    fun create(namedAggregate: NamedAggregate): EventStreamQueryService
}

abstract class AbstractEventStreamQueryServiceFactory : EventStreamQueryServiceFactory {
    private val queryServiceCache = mutableMapOf<NamedAggregate, EventStreamQueryService>()

    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        return queryServiceCache.computeIfAbsent(namedAggregate) {
            createQueryService(it)
        }
    }

    protected abstract fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService
}

object NoOpEventStreamQueryServiceFactory : EventStreamQueryServiceFactory {
    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        return NoOpEventStreamQueryService(namedAggregate = namedAggregate)
    }
}
