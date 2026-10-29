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
import me.ahoo.wow.spi.id.GlobalIdGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

private val logger = KotlinLogging.logger {}

/**
 * The global ids of CosId: the text ids of [runtime]'s generator [name] (`default` unless
 * `wow.cosid.global-generator` names another), whatever its strategy. The generator is looked up when this is
 * built, so a node whose CosId has no generator of that name does not start.
 *
 * @throws WowConfigurationException naming `wow.cosid.global-generator` when [runtime] has no generator [name].
 */
class CosIdGlobalIdGenerator(
    runtime: CosIdRuntime,
    val name: String = CosIdProperties().globalGenerator,
) : GlobalIdGenerator {
    private val ids: Supplier<String> = textOf(runtime, name) ?: throw WowConfigurationException(
        CosIdProperties.GLOBAL_GENERATOR,
        name,
        "CosId has no generator named $name for the node's global ids (${runtime.describe().keys.sorted()}).",
        "Configure cosid.generators.$name (a cosid80 with a radix-62 codec, say), or name one of CosId's " +
            "generators in ${CosIdProperties.GLOBAL_GENERATOR}.",
    )

    override fun next(): String = ids.get()

    override fun toString(): String = "CosIdGlobalIdGenerator($name)"
}

/**
 * The aggregate ids of CosId (as 9.x `CosIdAggregateIdGeneratorFactory`). Wow builds no generator: CosId's
 * configuration decides which exist, and an aggregate type's generator is found by name, in this order:
 *
 * 1. `wow.aggregates.[<context>.<aggregate>].id-generator`, which names any of CosId's generators: the host finds it
 *    through this [AggregateIdGeneratorSource] ([find]) and does not start when CosId has none of that name;
 * 2. else the generator named after the aggregate's qualified name, `<context>.<aggregate>` (configured as
 *    `cosid.generators."[<context>.<aggregate>]"`), which gives a type a sequence space or a layout of its own;
 * 3. else the [global] ids (`wow.cosid.global-generator`).
 *
 * Ids of the global generator are as unique and time-ordered as a type's own; only a per-type sequence is gone,
 * which nothing stored depends on. Each type's generator is resolved once, at its first id, and logged at INFO.
 */
class CosIdAggregateIdGenerator(
    private val runtime: CosIdRuntime,
    private val global: GlobalIdGenerator,
) : AggregateIdGenerator, AggregateIdGeneratorSource {
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
                "The ids of $type come from the global ids ($global): CosId has no generator ${nameOf(type)}."
            }
        }
        return own ?: Supplier(global::next)
    }

    companion object {
        /** The name of the CosId generator of [type]'s own ids, by convention: `<context>.<aggregate>`. */
        @JvmStatic
        fun nameOf(type: AggregateType): String = type.toString()
    }
}

/** The text ids of [runtime]'s generator [name], whatever its strategy; null when it has none of that name. */
private fun textOf(runtime: CosIdRuntime, name: String): Supplier<String>? =
    if (name in runtime.describe()) runtime.text(name) else null
