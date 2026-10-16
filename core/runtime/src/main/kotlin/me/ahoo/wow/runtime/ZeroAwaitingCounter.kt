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

package me.ahoo.wow.runtime

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A count of work in flight that a closing component waits to reach zero (review A5-3): an atomic on the hot path,
 * where a `MutableStateFlow` takes its monitor on every update. Only a waiter allocates, the deferred that the update
 * bringing the count to zero completes.
 */
internal class ZeroAwaitingCounter {
    private val count = AtomicInteger()
    private val zero = AtomicReference<CompletableDeferred<Unit>?>()

    val value: Int get() = count.get()

    fun increment() {
        count.incrementAndGet()
    }

    fun decrement() {
        if (count.decrementAndGet() <= 0) {
            zero.get()?.complete(Unit)
        }
    }

    /** Sets the count, for a component that knows it outright, such as a queue's size under its own lock. */
    fun set(value: Int) {
        count.set(value)
        if (value <= 0) {
            zero.get()?.complete(Unit)
        }
    }

    /** Returns once the count is zero or less; at once when it already is. */
    suspend fun awaitZero() {
        while (count.get() > 0) {
            val current = zero.get()
            val waiter = if (current == null || current.isCompleted) {
                val fresh = CompletableDeferred<Unit>()
                if (!zero.compareAndSet(current, fresh)) {
                    continue
                }
                fresh
            } else {
                current
            }
            // The count may have reached zero before the waiter was registered for the update to complete.
            if (count.get() <= 0) {
                return
            }
            waiter.await()
        }
    }
}
