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

package me.ahoo.wow.runtime.internal

import reactor.core.Exceptions

/**
 * Retains one primary failure and atomically freezes its suppressed-error set.
 *
 * Failure paths are deliberately serialized: mutating [Throwable.suppressed]
 * outside the seal boundary would otherwise let a detached asynchronous
 * pipeline change an error that has already been published to observers.
 */
internal class SealableFailureAccumulator {
    private val monitor = Any()
    private var primaryFailure: Throwable? = null
    private var sealed = false

    val failure: Throwable?
        get() = synchronized(monitor) {
            primaryFailure
        }

    fun record(error: Throwable): Throwable {
        Exceptions.throwIfFatal(error)
        return synchronized(monitor) {
            val current = primaryFailure
            when {
                sealed -> current ?: error

                current == null -> {
                    primaryFailure = error
                    error
                }

                else -> {
                    current.addSuppressedIfAbsent(error)
                    current
                }
            }
        }
    }

    fun seal(): Throwable? =
        synchronized(monitor) {
            sealed = true
            primaryFailure
        }
}
