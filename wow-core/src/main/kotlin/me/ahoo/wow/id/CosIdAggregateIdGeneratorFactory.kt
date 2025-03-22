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

package me.ahoo.wow.id

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher

@Order(ORDER_LAST)
class CosIdAggregateIdGeneratorFactory(
    private val idProvider: IdGeneratorProvider = DefaultIdGeneratorProvider.INSTANCE
) :
    AggregateIdGeneratorFactory {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun create(namedAggregate: NamedAggregate): IdGenerator {
        val idGenName = MetadataSearcher.metadata
            .contexts[namedAggregate.contextName]
            ?.aggregates
            ?.get(namedAggregate.aggregateName)
            ?.id
            ?: namedAggregate.aggregateName

        val idGeneratorOp = idProvider.get(idGenName)
        if (idGeneratorOp.isPresent) {
            val idGenerator = idGeneratorOp.get()
            log.info {
                "Create $idGenerator to $namedAggregate from DefaultIdGeneratorProvider[$idGenName]."
            }
            return idGenerator
        }

        val idGenerator = Radix62CosIdGenerator(GlobalIdGenerator.machineId)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        log.info {
            "Create $clockSyncCosIdGenerator to $namedAggregate."
        }
        return clockSyncCosIdGenerator
    }
}
