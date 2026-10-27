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

package me.ahoo.wow.cosid

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosid.CosId
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.cosid.snowflake.SnowflakeId
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * The global ids of CosId: the generator [provider] has under [name] (`cosid` by default, CosId's own
 * [CosIdGenerator]), or its shared generator when it has none by that name. The generator is looked up once, at the
 * first id, so CosId may register it after this is built.
 */
class CosIdGlobalIdGenerator(
    private val provider: IdGeneratorProvider,
    val name: String = CosId.COSID,
) : GlobalIdGenerator {
    /** The CosId generator the ids come from. */
    val generator: IdGenerator by lazy {
        provider.get(name).orElseGet {
            logger.info { "CosId has no generator named $name: the global ids come from its shared generator." }
            provider.share
        }
    }

    override fun next(): String = generator.generateAsString()
}

/**
 * The aggregate ids of CosId (as 9.x `CosIdAggregateIdGeneratorFactory`): an aggregate type's ids come
 * from the generator [provider] has under the aggregate's name, or, when it has none, from a
 * `ClockSyncCosIdGenerator(Radix62CosIdGenerator(machineId))` of the type's own, its machine id from [machineId]
 * (CosId's machine id allocation, which the global generator's carries).
 *
 * As an [AggregateIdGeneratorSource] it finds [provider]'s generators by name, so
 * `wow.aggregates.[<context>.<aggregate>].id-generator` may name one: such a generator must be in [provider] when the
 * host assembles the node.
 */
class CosIdAggregateIdGenerator(
    private val provider: IdGeneratorProvider,
    private val machineId: () -> Int,
) : AggregateIdGenerator, AggregateIdGeneratorSource {
    private val generators = ConcurrentHashMap<AggregateType, IdGenerator>()

    override fun next(type: AggregateType): String = generators.computeIfAbsent(type, ::generatorOf).generateAsString()

    override fun find(name: String): AggregateIdGenerator? =
        provider.get(name).map { generator -> AggregateIdGenerator { generator.generateAsString() } }.orElse(null)

    private fun generatorOf(type: AggregateType): IdGenerator = provider.get(type.name).orElseGet {
        ClockSyncCosIdGenerator(Radix62CosIdGenerator(machineId()))
    }.also { logger.info { "The ids of $type come from $it." } }

    companion object {
        /**
         * The machine id of [generator], a [CosIdGenerator] or a [SnowflakeId] (clock-synchronized or not); it fails
         * for a generator that has none, such as a segment generator.
         */
        @JvmStatic
        fun machineIdOf(generator: IdGenerator): Int = when (generator) {
            is CosIdGenerator -> generator.machineId
            is SnowflakeId -> generator.machineId
            else -> throw IllegalStateException(
                "CosId's $generator has no machine id: an aggregate type without a CosId generator of its own " +
                    "needs one, from a CosIdGenerator or a SnowflakeId (CosId's machine id allocation).",
            )
        }
    }
}
