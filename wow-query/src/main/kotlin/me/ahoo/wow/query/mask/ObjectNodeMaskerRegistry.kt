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

package me.ahoo.wow.query.mask

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import java.util.concurrent.ConcurrentHashMap

interface ObjectNodeMaskerRegistry<MASKER : AggregateObjectNodeMasker> {
    fun register(masker: MASKER)
    fun unregister(masker: MASKER)
    fun getMasker(namedAggregate: NamedAggregate): CompositeObjectNodeMasker<MASKER>
}

abstract class AbstractObjectNodeMaskerRegistry<MASKER : AggregateObjectNodeMasker> :
    ObjectNodeMaskerRegistry<MASKER> {
    private val maskers = ConcurrentHashMap<NamedAggregate, List<MASKER>>()

    override fun register(masker: MASKER) {
        log.info { "Register - masker:[$masker]." }
        maskers.compute(masker.namedAggregate.materialize()) { _, current ->
            (current.orEmpty() + masker).sortedByOrder()
        }
    }

    override fun unregister(masker: MASKER) {
        log.info { "Unregister - masker:[$masker]." }
        maskers.compute(masker.namedAggregate.materialize()) { _, current ->
            current.orEmpty().toMutableList().apply { remove(masker) }.sortedByOrder()
        }
    }

    override fun getMasker(namedAggregate: NamedAggregate): CompositeObjectNodeMasker<MASKER> =
        DefaultCompositeObjectNodeMasker(maskers[namedAggregate.materialize()].orEmpty())

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

class StateObjectNodeMaskerRegistry : AbstractObjectNodeMaskerRegistry<StateObjectNodeMasker>()

class EventStreamObjectNodeMaskerRegistry : AbstractObjectNodeMaskerRegistry<EventStreamObjectNodeMasker>()
