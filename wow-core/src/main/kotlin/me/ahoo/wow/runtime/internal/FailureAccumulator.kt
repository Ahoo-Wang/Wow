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

internal class FailureAccumulator {
    private val monitor = Any()
    private var primary: Throwable? = null
    private var sealed = false

    fun record(error: Throwable) {
        synchronized(monitor) {
            if (sealed) {
                return
            }
            val current = primary
            if (current == null) {
                primary = error
            } else if (current !== error && current.suppressed.none { it === error }) {
                current.addSuppressed(error)
            }
        }
    }

    fun current(): Throwable? =
        synchronized(monitor) {
            primary
        }

    fun seal(): Throwable? =
        synchronized(monitor) {
            sealed = true
            primary
        }
}
