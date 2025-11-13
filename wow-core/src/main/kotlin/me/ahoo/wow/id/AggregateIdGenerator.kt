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
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.AggregateIdGeneratorRegistrar.factories
import me.ahoo.wow.id.AggregateIdGeneratorRegistrar.log
import me.ahoo.wow.modeling.materialize
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A [ConcurrentHashMap] that maps [NamedAggregate] instances to their corresponding [IdGenerator]s.
 * This map is used to store and manage the ID generators for different named aggregates, ensuring
 * that each aggregate can have its own unique ID generation strategy. The use of a concurrent hash
 * map allows for thread-safe operations, making it suitable for environments where multiple threads
 * might access or modify the map simultaneously.
 *
 * @see NamedAggregate
 * @see IdGenerator
 */
private val AGGREGATE_ID_GENERATORS: MutableMap<NamedAggregate, IdGenerator> =
    ConcurrentHashMap<NamedAggregate, IdGenerator>()

/**
 * Registers and provides [IdGenerator] instances for [NamedAggregate] types.
 *
 * This object acts as a registrar and provider for ID generators, allowing for the dynamic
 * initialization of ID generators based on the type of [NamedAggregate]. It leverages
 * [AggregateIdGeneratorFactory] implementations to create and register these generators.
 *
 * @property factories A lazily initialized list of [AggregateIdGeneratorFactory] implementations,
 * sorted by their order. These factories are used to create [IdGenerator] instances for specific
 * [NamedAggregate] types.
 * @property log A logger instance for logging information during the process of loading and
 * initializing ID generators.
 */
object AggregateIdGeneratorRegistrar :
    Map<NamedAggregate, IdGenerator> by AGGREGATE_ID_GENERATORS {
    private val log = KotlinLogging.logger {}
    private val factories: List<AggregateIdGeneratorFactory> by lazy {
        return@lazy ServiceLoader
            .load(AggregateIdGeneratorFactory::class.java)
            .sortedByOrder()
    }

    /**
     * Retrieves or initializes an [IdGenerator] for the given [NamedAggregate].
     *
     * This method checks if an [IdGenerator] is already associated with the provided [key]. If not, it attempts to create
     * one using the available factories. The first factory that successfully creates a non-null [IdGenerator] is used.
     * If no factory can create an [IdGenerator], an [IllegalStateException] is thrown.
     *
     * @param key the [NamedAggregate] for which to retrieve or initialize the [IdGenerator]
     * @return the [IdGenerator] associated with the [key]
     * @throws IllegalStateException if no [AggregateIdGeneratorFactory] can create an [IdGenerator] for the [key]
     */
    fun getOrInitialize(key: NamedAggregate): IdGenerator =
        AGGREGATE_ID_GENERATORS.computeIfAbsent(key) { _ ->
            factories.firstNotNullOfOrNull {
                log.info {
                    "Load $it to create [$key]'s AggregateIdGenerator."
                }
                val idGenerator = it.create(key)
                if (idGenerator == null) {
                    log.info {
                        "Ignore: $it create [$key]'s AggregateIdGenerator is null."
                    }
                } else {
                    log.info {
                        "Setup $idGenerator to [$key]'s AggregateIdGenerator."
                    }
                }
                idGenerator
            } ?: throw IllegalStateException("No AggregateIdGeneratorFactory found for [$key]'s AggregateIdGenerator.")
        }

    /**
     * Generates a unique ID string for the given [key].
     *
     * @param key the [NamedAggregate] for which to generate the ID
     * @return the generated ID as a string
     */
    fun generateId(key: NamedAggregate): String {
        return getOrInitialize(key).generateAsString()
    }
}

/**
 * Generates a unique ID string for this [NamedAggregate].
 *
 * This extension function provides a convenient way to generate IDs directly on aggregate instances.
 * It materializes the aggregate and delegates to [AggregateIdGeneratorRegistrar.generateId].
 *
 * @return the generated ID as a string
 * @sample
 * val aggregate: NamedAggregate = // some aggregate instance
 * val id = aggregate.generateId() // Generates a unique ID for this aggregate
 */
fun NamedAggregate.generateId(): String {
    return AggregateIdGeneratorRegistrar.generateId(materialize())
}
