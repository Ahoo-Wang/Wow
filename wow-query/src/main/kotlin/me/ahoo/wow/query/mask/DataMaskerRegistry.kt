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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import java.util.concurrent.ConcurrentHashMap

interface DataMaskerRegistry<MASKER : AggregateDynamicDocumentMasker> {
    fun register(masker: MASKER)
    fun unregister(masker: MASKER)
    fun getAggregateDataMasker(namedAggregate: NamedAggregate): AggregateDataMasker<MASKER>
}

abstract class AbstractDataMaskerRegistry<MASKER : AggregateDynamicDocumentMasker> : DataMaskerRegistry<MASKER> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val maskers = ConcurrentHashMap<NamedAggregate, AggregateDataMasker<MASKER>>()

    override fun register(masker: MASKER) {
        log.info {
            "Register - masker:[$masker]."
        }
        maskers.compute(masker.namedAggregate.materialize()) { _, aggregateDataMasker ->
            aggregateDataMasker?.addMasker(masker) ?: DefaultAggregateDataMasker(listOf(masker))
        }
    }

    override fun unregister(masker: MASKER) {
        log.info {
            "Unregister - masker:[$masker]."
        }
        maskers.compute(masker.namedAggregate.materialize()) { _, aggregateDataMasker ->
            aggregateDataMasker?.removeMasker(masker) ?: DefaultAggregateDataMasker.empty()
        }
    }

    override fun getAggregateDataMasker(namedAggregate: NamedAggregate): AggregateDataMasker<MASKER> {
        return maskers.computeIfAbsent(namedAggregate.materialize()) {
            DefaultAggregateDataMasker.empty<MASKER>()
        }
    }
}

class StateDataMaskerRegistry : AbstractDataMaskerRegistry<StateDynamicDocumentMasker>()

class EventStreamMaskerRegistry : AbstractDataMaskerRegistry<EventStreamDynamicDocumentMasker>()
