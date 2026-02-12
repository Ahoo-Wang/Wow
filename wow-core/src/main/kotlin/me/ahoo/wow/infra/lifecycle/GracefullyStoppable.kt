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

import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Interface for components that support graceful shutdown.
 *
 * Implementations of this interface provide both synchronous and asynchronous
 * graceful stop operations. The synchronous `close()` method blocks until
 * all ongoing operations complete, while `stopGracefully()` returns a reactive
 * stream that completes when shutdown is finished.
 *
 * This is particularly useful for message dispatchers, connection pools, and
 * other resources that need to complete in-flight operations before terminating.
 *
 * Example usage:
 * ```kotlin
 * class MyDispatcher : GracefullyStoppable {
 *     private val activeOperations = mutableListOf<Mono<Void>>()
 *
 *     override fun stopGracefully(): Mono<Void> {
 *         // Cancel new operations and wait for active ones
 *         stopAcceptingNewOperations()
 *         return if (activeOperations.isNotEmpty()) {
 *             Flux.merge(activeOperations).then()
 *         } else {
 *             Mono.empty()
 *         }
 *     }
 * }
 *
 * // Usage
 * val dispatcher = MyDispatcher()
 * // ... use dispatcher ...
 * dispatcher.close() // Blocks until graceful shutdown completes
 * ```
 *
 * @see AutoCloseable for the standard close contract
 */
interface GracefullyStoppable : AutoCloseable {
    /**
     * Closes this resource by performing a graceful shutdown.
     *
     * This method implements the [AutoCloseable] contract and delegates
     * to the [stop] method to ensure all ongoing operations complete
     * before the resource is closed.
     *
     * @throws Exception if an error occurs during shutdown
     * @see AutoCloseable.close
     * @see stop
     */
    override fun close() {
        stop()
    }

    /**
     * Synchronously closes this resource with graceful shutdown.
     *
     * This method blocks the calling thread until all ongoing operations
     * complete and the resource is fully shut down. It delegates to the
     * asynchronous `closeGracefully()` method and waits for its completion.
     *
     * This implementation ensures that `close()` adheres to the `AutoCloseable`
     * contract while providing graceful shutdown behavior.
     *
     * @throws IllegalStateException if the underlying reactive stream fails
     * @see stopGracefully for the asynchronous version
     */
    fun stop() {
        stopGracefully().block()
    }

    /**
     * Synchronously closes this resource with graceful shutdown within a specified timeout.
     *
     * This method blocks the calling thread until either all ongoing operations complete
     * and the resource is fully shut down, or the specified timeout expires. It delegates
     * to the asynchronous `closeGracefully()` method and waits for its completion with
     * a time limit.
     *
     * If the timeout expires before shutdown completes, the method returns without
     * guaranteeing that all operations have finished. Implementations should handle
     * any necessary cleanup in such cases.
     *
     * @param timeout The maximum duration to wait for graceful shutdown to complete
     * @throws IllegalStateException if the underlying reactive stream fails
     * @see stopGracefully for the asynchronous version
     * @see stop for the version without timeout
     */
    fun stop(timeout: Duration) {
        stopGracefully().block(timeout)
    }

    /**
     * Asynchronously closes this resource with graceful shutdown.
     *
     * Returns a `Mono<Void>` that completes when all ongoing operations have
     * finished and the resource has been cleanly shut down. Implementations
     * should ensure that:
     * - No new operations are accepted after shutdown begins
     * - All in-flight operations are allowed to complete
     * - Resources are properly released
     *
     * This method enables non-blocking shutdown in reactive applications.
     *
     * @return A `Mono<Void>` that completes when shutdown is finished
     * @see stop for the synchronous blocking version
     */
    fun stopGracefully(): Mono<Void>
}
