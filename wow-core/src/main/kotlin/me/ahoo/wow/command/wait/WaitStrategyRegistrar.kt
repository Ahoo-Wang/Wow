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

package me.ahoo.wow.command.wait

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing wait strategies.
 * Provides thread-safe operations for registering, unregistering, and retrieving
 * wait strategies by their command IDs.
 */
interface WaitStrategyRegistrar {
    /**
     * Registers a wait strategy if not already present.
     * Similar to Map.putIfAbsent - returns existing strategy if one exists for the same ID.
     *
     * @param waitStrategy The wait strategy to register.
     * @return The existing wait strategy if one was already registered for this ID, null otherwise.
     * @see java.util.Map.putIfAbsent
     */
    fun register(waitStrategy: WaitStrategy): WaitStrategy?

    /**
     * Unregisters a wait strategy by its command ID.
     * Similar to Map.remove - removes and returns the strategy if it exists.
     *
     * @param waitCommandId The command ID of the wait strategy to remove.
     * @return The removed wait strategy, or null if none was registered for this ID.
     * @see java.util.Map.remove
     */
    fun unregister(waitCommandId: String): WaitStrategy?

    /**
     * Retrieves a wait strategy by its command ID.
     *
     * @param waitCommandId The command ID to look up.
     * @return The wait strategy associated with the ID, or null if not found.
     */
    fun get(waitCommandId: String): WaitStrategy?

    /**
     * Checks if a wait strategy is registered for the given command ID.
     *
     * @param waitCommandId The command ID to check.
     * @return true if a wait strategy is registered for this ID, false otherwise.
     */
    operator fun contains(waitCommandId: String): Boolean

    /**
     * Forwards a wait signal to the appropriate wait strategy.
     * Retrieves the strategy by signal's wait command ID and calls next() on it.
     *
     * @param signal The wait signal to forward.
     * @return true if a strategy was found and notified, false otherwise.
     */
    fun next(signal: WaitSignal): Boolean {
        val waitStrategy = get(signal.waitCommandId) ?: return false
        waitStrategy.next(signal)
        return true
    }
}

/**
 * Simple thread-safe implementation of WaitStrategyRegistrar using ConcurrentHashMap.
 * Provides concurrent access to wait strategies with atomic operations.
 */
object SimpleWaitStrategyRegistrar : WaitStrategyRegistrar {
    private val log = KotlinLogging.logger {}
    private val waitStrategies: ConcurrentHashMap<String, WaitStrategy> = ConcurrentHashMap()

    override fun register(waitStrategy: WaitStrategy): WaitStrategy? {
        log.debug {
            "Register - waitCommandId[${waitStrategy.waitCommandId}] WaitStrategy."
        }
        return waitStrategies.putIfAbsent(waitStrategy.waitCommandId, waitStrategy)
    }

    override fun unregister(waitCommandId: String): WaitStrategy? {
        val value = waitStrategies.remove(waitCommandId)
        log.debug {
            "Unregister - remove waitCommandId[$waitCommandId] WaitStrategy - [${value != null}]."
        }
        return value
    }

    override fun get(waitCommandId: String): WaitStrategy? = waitStrategies[waitCommandId]

    override fun contains(waitCommandId: String): Boolean = waitStrategies.containsKey(waitCommandId)
}
