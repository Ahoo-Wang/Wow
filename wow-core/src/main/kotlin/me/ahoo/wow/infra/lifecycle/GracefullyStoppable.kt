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
 * graceful stop operations. The synchronous [stop] method has a default timeout
 * of 30 seconds; if shutdown does not complete within that window, the call returns
 * without guaranteeing all in-flight operations have finished. Use [stop] with a
 * custom [Duration] to adjust the timeout, or call [stopGracefully] directly for
 * fully asynchronous shutdown.
 *
 * This is particularly useful for message dispatchers, connection pools, and
 * other resources that need to complete in-flight operations before terminating.
 *
 * @see AutoCloseable for the standard close contract
 */
interface GracefullyStoppable : AutoCloseable {
    /**
     * Closes this resource by performing a graceful shutdown with a 30-second timeout.
     *
     * This method implements the [AutoCloseable] contract and delegates to [stop].
     *
     * @throws Exception if an error occurs during shutdown
     * @see AutoCloseable.close
     * @see stop
     */
    override fun close() {
        stop()
    }

    /**
     * Synchronously stops this resource with a 30-second timeout.
     *
     * Blocks the calling thread until all ongoing operations complete or the
     * 30-second timeout expires — whichever comes first. If the timeout expires,
     * this method returns without guaranteeing that all operations have finished.
     *
     * @throws IllegalStateException if the underlying reactive stream fails
     * @see stopGracefully for the asynchronous version
     */
    fun stop() {
        stop(Duration.ofSeconds(30))
    }

    /**
     * Synchronously stops this resource within the specified [timeout].
     *
     * Blocks the calling thread until all ongoing operations complete or the
     * timeout expires. If the timeout expires, this method returns without
     * guaranteeing that all operations have finished.
     *
     * @param timeout The maximum duration to wait for graceful shutdown to complete
     * @throws IllegalStateException if the underlying reactive stream fails
     * @see stopGracefully for the asynchronous version
     * @see stop for the version with the default 30-second timeout
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
