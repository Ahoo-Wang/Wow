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
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.CosIdIdStateParser
import me.ahoo.cosid.cosid.CosIdState
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.infra.Decorator
import java.util.*

/**
 * Global ID generator that provides unique identifier generation across the application.
 *
 * This object acts as a singleton wrapper around a [CosIdGenerator], loading the actual generator
 * lazily using [GlobalIdGeneratorFactory] implementations discovered via [ServiceLoader].
 * It implements [Decorator] to delegate all operations to the underlying generator.
 *
 * The generator is initialized on first access, and if no factory can provide a generator,
 * a [NotInitializedGlobalIdGeneratorError] is thrown.
 *
 * @see CosIdGenerator
 * @see Decorator
 * @see GlobalIdGeneratorFactory
 */
object GlobalIdGenerator : CosIdGenerator, Decorator<CosIdGenerator> {
    private val log = KotlinLogging.logger {}

    /**
     * The underlying [CosIdGenerator] that handles the actual ID generation.
     *
     * This property is lazily initialized by loading a generator using [GlobalIdGeneratorFactory] implementations.
     * If no generator can be loaded, accessing this property throws [NotInitializedGlobalIdGeneratorError].
     */
    override val delegate: CosIdGenerator by lazy {
        return@lazy loadGlobalIdGenerator() ?: throw NotInitializedGlobalIdGeneratorError()
    }

    /**
     * Loads a global [CosIdGenerator] using available [GlobalIdGeneratorFactory] implementations.
     *
     * Uses [ServiceLoader] to discover factories, sorts them by order, and attempts to create a generator
     * with each factory until one succeeds. Logs the loading process for debugging.
     *
     * @return the first non-null [CosIdGenerator] created by a factory, or null if none succeed
     */
    private fun loadGlobalIdGenerator(): CosIdGenerator? =
        ServiceLoader
            .load(GlobalIdGeneratorFactory::class.java)
            .sortedByOrder()
            .firstNotNullOfOrNull {
                log.info {
                    "Load $it to create GlobalIdGenerator."
                }
                val idGenerator = it.create()
                if (idGenerator == null) {
                    log.info {
                        "$it create GlobalIdGenerator is null."
                    }
                } else {
                    log.info {
                        "Setup $idGenerator to GlobalIdGenerator."
                    }
                }
                idGenerator
            }

    override fun getMachineId(): Int = delegate.machineId

    override fun getLastTimestamp(): Long = delegate.lastTimestamp

    override fun getStateParser(): CosIdIdStateParser = delegate.stateParser

    override fun generateAsState(): CosIdState = delegate.generateAsState()
}

/**
 * Generates a unique global ID string.
 *
 * This function provides a convenient way to generate a globally unique identifier using the [GlobalIdGenerator].
 * The ID is generated as a string representation suitable for use in various contexts such as message IDs.
 *
 * @return a unique global ID as a string
 * @see GlobalIdGenerator
 * @sample
 * val id = generateGlobalId() // Generates a unique string like "0H1F2G3H4I5J6K7L8M9N0O1P2Q3"
 */
fun generateGlobalId(): String = GlobalIdGenerator.generateAsString()

/**
 * Exception thrown when the [GlobalIdGenerator] cannot be initialized.
 *
 * This error occurs when no [GlobalIdGeneratorFactory] implementations are able to provide a valid [CosIdGenerator]
 * during the lazy initialization of the global generator.
 *
 * @see GlobalIdGenerator
 * @see GlobalIdGeneratorFactory
 */
class NotInitializedGlobalIdGeneratorError : WowException(
    "NotInitializedGlobalIdGenerator",
    "GlobalIdGenerator is not initialized."
)
