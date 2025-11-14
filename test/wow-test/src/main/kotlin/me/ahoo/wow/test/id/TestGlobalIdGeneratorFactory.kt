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

package me.ahoo.wow.test.id

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.id.GlobalIdGeneratorFactory

/**
 * A test implementation of [GlobalIdGeneratorFactory] that creates a [CosIdGenerator] with a fixed machine ID for testing purposes.
 *
 * This factory is ordered last ([ORDER_LAST]) to ensure it is used in test environments,
 * providing a deterministic and isolated ID generation for unit tests.
 * It creates a [Radix62CosIdGenerator] wrapped in a [ClockSyncCosIdGenerator] to ensure
 * clock synchronization and consistent ID generation across test runs.
 *
 * @see GlobalIdGeneratorFactory
 * @see CosIdGenerator
 */
@Order(ORDER_LAST)
class TestGlobalIdGeneratorFactory : GlobalIdGeneratorFactory {
    companion object {
        /**
         * Logger instance for logging ID generator creation events.
         */
        private val log = KotlinLogging.logger { }

        /**
         * Fixed machine ID used for test ID generation.
         *
         * This value (1048575) is the maximum possible machine ID in CosId,
         * ensuring it doesn't conflict with production machine IDs.
         */
        private const val TEST_MACHINE_ID: Int = 1048575
    }

    /**
     * Creates a new [CosIdGenerator] instance configured for testing.
     *
     * This method instantiates a [Radix62CosIdGenerator] with the fixed [TEST_MACHINE_ID]
     * and wraps it in a [ClockSyncCosIdGenerator] to ensure consistent ID generation
     * across different test runs. The creation is logged for debugging purposes.
     *
     * @return A [CosIdGenerator] suitable for generating unique IDs in test environments.
     *
     * @sample
     * ```
     * val factory = TestGlobalIdGeneratorFactory()
     * val generator = factory.create()
     * val id = generator.generate() // Generates a unique test ID
     * ```
     */
    override fun create(): CosIdGenerator {
        val idGenerator = Radix62CosIdGenerator(TEST_MACHINE_ID)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        log.info {
            "Create - [$clockSyncCosIdGenerator]."
        }
        return clockSyncCosIdGenerator
    }
}
