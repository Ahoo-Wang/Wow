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

package me.ahoo.wow.infra.lifecycle

/**
 * Interface for components that have a lifecycle with start and stop operations.
 *
 * Implementations of this interface can be started and stopped gracefully.
 * The start method initializes the component, while stop methods handle shutdown.
 *
 * This is useful for services, dispatchers, and other managed components.
 *
 * Example usage:
 * ```kotlin
 * class MyService : Lifecycle {
 *     override fun start() {
 *         // Initialize resources
 *         println("Service started")
 *     }
 *
 *     override fun stopGracefully(): Mono<Void> {
 *         // Clean up
 *         return Mono.empty()
 *     }
 * }
 *
 * val service = MyService()
 * service.start()
 * // ... use service ...
 * service.stop() // Graceful shutdown
 * ```
 *
 * @see GracefullyStoppable for stop operations
 */
interface Lifecycle : GracefullyStoppable {
    /**
     * Starts this component, initializing resources and beginning operations.
     *
     * This method should be called to bring the component to an operational state.
     * Implementations should ensure that all necessary initialization is performed.
     *
     * Calling start on an already started component may have undefined behavior.
     *
     * @throws Exception if initialization fails
     */
    fun start()
}
