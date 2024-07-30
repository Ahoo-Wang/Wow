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

package me.ahoo.wow.infra.idempotency

import me.ahoo.wow.api.modeling.NamedAggregate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

fun interface AggregateIdempotencyCheckerProvider {
    fun getChecker(namedAggregate: NamedAggregate): IdempotencyChecker
}

class DefaultAggregateIdempotencyCheckerProvider(
    private val checkerSupplier: (NamedAggregate) -> IdempotencyChecker
) : AggregateIdempotencyCheckerProvider {
    private val idempotencyCheckers: ConcurrentMap<NamedAggregate, IdempotencyChecker> = ConcurrentHashMap()
    override fun getChecker(namedAggregate: NamedAggregate): IdempotencyChecker {
        return idempotencyCheckers.computeIfAbsent(namedAggregate) { checkerSupplier(namedAggregate) }
    }
}
