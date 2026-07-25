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

package me.ahoo.wow.infra.sink

import me.ahoo.wow.infra.Decorator
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe [Sinks.Many] decorator.
 *
 * Reactor sinks reject concurrent emissions with [Sinks.EmitResult.FAIL_NON_SERIALIZED].
 * A [ReentrantLock] serializes every delegate call on its originating thread,
 * preserving synchronous results, exception identity, thread-local context and
 * downstream reentrancy.
 *
 * @param T non-null element type
 * @param delegate sink whose emissions are serialized
 */
class ConcurrentManySink<T : Any>(
    override val delegate: Sinks.Many<T>,
) : Sinks.Many<T>,
    Decorator<Sinks.Many<T>> {
    private val lock = ReentrantLock()

    override fun tryEmitNext(t: T): Sinks.EmitResult =
        lock.withLock {
            delegate.tryEmitNext(t)
        }

    override fun tryEmitComplete(): Sinks.EmitResult =
        lock.withLock(delegate::tryEmitComplete)

    override fun tryEmitError(error: Throwable): Sinks.EmitResult =
        lock.withLock {
            delegate.tryEmitError(error)
        }

    override fun emitNext(t: T, failureHandler: Sinks.EmitFailureHandler) {
        lock.withLock {
            delegate.emitNext(t, failureHandler)
        }
    }

    override fun emitComplete(failureHandler: Sinks.EmitFailureHandler) {
        lock.withLock {
            delegate.emitComplete(failureHandler)
        }
    }

    override fun emitError(error: Throwable, failureHandler: Sinks.EmitFailureHandler) {
        lock.withLock {
            delegate.emitError(error, failureHandler)
        }
    }

    override fun currentSubscriberCount(): Int = delegate.currentSubscriberCount()

    override fun asFlux(): Flux<T> = delegate.asFlux()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? = delegate.scanUnsafe(key)
}

/**
 * Makes every emission method of this sink safe for concurrent producers.
 *
 * Existing [ConcurrentManySink] instances are returned unchanged. Other sinks retain
 * their original subscription, backpressure, terminal and scan-attribute behavior while
 * delegate calls are serialized on their caller threads.
 */
fun <T : Any> Sinks.Many<T>.concurrent(): ConcurrentManySink<T> =
    if (this is ConcurrentManySink<T>) {
        this
    } else {
        ConcurrentManySink(this)
    }
