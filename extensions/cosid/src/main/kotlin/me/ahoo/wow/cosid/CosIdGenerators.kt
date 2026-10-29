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
import me.ahoo.cosid.runtime.CosIdRuntime
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

private val logger = KotlinLogging.logger {}

/**
 * The aggregate ids of CosId (as 9.x `CosIdAggregateIdGeneratorFactory`). Wow builds no generator: CosId's
 * configuration decides which exist, and an aggregate type's generator is found by name, in this order:
 *
 * 1. `wow.aggregates.[<context>.<aggregate>].id-generator`, which names any of CosId's generators: the host finds it
 *    through this [AggregateIdGeneratorSource] ([find]) and does not start when CosId has none of that name;
 * 2. else the generator named after the aggregate's qualified name, `<context>.<aggregate>` (configured as
 *    `cosid.generators."[<context>.<aggregate>]"`), which gives a type a sequence space or a layout of its own;
 * 3. else CosId's generator [fallback], `wow.cosid.aggregate-generator` (`default` by default).
 *
 * Each type's generator is resolved once, at its first id, and logged at INFO. A CosId id is unique within its
 * namespace, by default the service's; an aggregate id needs no more, since only the context that owns an aggregate
 * type creates its aggregates. The node's own ids, which other services' requests meet in one context's
 * idempotency key, stay Wow's UUIDv7: this module does not replace them.
 *
 * @throws WowConfigurationException naming `wow.cosid.aggregate-generator` when [runtime] has no generator [fallback].
 */
class CosIdAggregateIdGenerator(
    private val runtime: CosIdRuntime,
    val fallback: String = CosIdProperties().aggregateGenerator,
) : AggregateIdGenerator, AggregateIdGeneratorSource {
    private val fallbackIds: Supplier<String> = textOf(runtime, fallback) ?: throw WowConfigurationException(
        CosIdProperties.AGGREGATE_GENERATOR,
        fallback,
        "CosId has no generator named $fallback for the aggregate types that have none of their own " +
            "(${runtime.describe().keys.sorted()}).",
        "Configure cosid.generators.$fallback (a cosid80 with a radix-62 codec, say), or name one of CosId's " +
            "generators in ${CosIdProperties.AGGREGATE_GENERATOR}.",
    )
    private val generators = ConcurrentHashMap<AggregateType, Supplier<String>>()

    override fun next(type: AggregateType): String = generators.computeIfAbsent(type, ::generatorOf).get()

    override fun find(name: String): AggregateIdGenerator? =
        textOf(runtime, name)?.let { ids -> AggregateIdGenerator { ids.get() } }

    private fun generatorOf(type: AggregateType): Supplier<String> {
        val own = textOf(runtime, nameOf(type))
        logger.info {
            if (own != null) {
                "The ids of $type come from CosId's generator ${nameOf(type)}, named after it."
            } else {
                "The ids of $type come from CosId's generator $fallback: CosId has no generator ${nameOf(type)}."
            }
        }
        return own ?: fallbackIds
    }

    override fun toString(): String = "CosIdAggregateIdGenerator($fallback)"

    companion object {
        /** The name of the CosId generator of [type]'s own ids, by convention: `<context>.<aggregate>`. */
        @JvmStatic
        fun nameOf(type: AggregateType): String = type.toString()
    }
}

/** The text ids of [runtime]'s generator [name], whatever its strategy; null when it has none of that name. */
private fun textOf(runtime: CosIdRuntime, name: String): Supplier<String>? =
    if (name in runtime.describe()) runtime.text(name) else null
