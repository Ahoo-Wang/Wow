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

/**
 * Factory for creating [IdGenerator] instances for [NamedAggregate] using the CosId library.
 *
 * This factory attempts to retrieve an existing [IdGenerator] from the [IdGeneratorProvider] based on the aggregate's
 * metadata ID or name. If no generator is found, it creates a new [ClockSyncCosIdGenerator] with a [Radix62CosIdGenerator]
 * using the global machine ID.
 *
 * The factory is ordered with [ORDER_LAST] to serve as a fallback when other factories cannot provide a generator.
 *
 * @property idProvider the [IdGeneratorProvider] used to retrieve or store ID generators. Defaults to [DefaultIdGeneratorProvider.INSTANCE].
 * @see AggregateIdGeneratorFactory
 * @see NamedAggregate
 * @see IdGenerator
 */
@Order(ORDER_LAST)
class CosIdAggregateIdGeneratorFactory(
    private val idProvider: IdGeneratorProvider = DefaultIdGeneratorProvider.INSTANCE
) : AggregateIdGeneratorFactory {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Creates an [IdGenerator] for the specified [NamedAggregate].
     *
     * This method first attempts to retrieve an existing [IdGenerator] from the [idProvider] using the aggregate's
     * ID from metadata or the aggregate name as the key. If found, it returns the existing generator.
     * If not found, it creates a new [ClockSyncCosIdGenerator] wrapping a [Radix62CosIdGenerator] initialized
     * with the global machine ID.
     *
     * @param namedAggregate the [NamedAggregate] for which to create the ID generator
     * @return the [IdGenerator] for the aggregate, either retrieved from the provider or newly created
     */
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
