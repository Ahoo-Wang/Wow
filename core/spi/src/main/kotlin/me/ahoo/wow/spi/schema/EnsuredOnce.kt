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

package me.ahoo.wow.spi.schema

import me.ahoo.wow.api.annotation.WowSpi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The structures a store has made sure of (created, registered, format checked), by key: each is made sure of once, on
 * its first use, and then costs the store nothing.
 *
 * Concurrent first uses of one key wait for one ensure rather than each running its own, which would send its schema
 * statements as many times at once. They wait on a [ReentrantLock], not a monitor (`computeIfAbsent`): a virtual
 * thread blocked on a monitor pins its carrier, and the ensure runs blocking driver calls. An ensure that fails keeps
 * nothing; the next use runs it again.
 */
@WowSpi
class EnsuredOnce<K : Any, T : Any> {
    private val ensured = ConcurrentHashMap<K, T>()
    private val locks = ConcurrentHashMap<K, ReentrantLock>()

    /** What was made sure of so far. */
    val values: Collection<T> get() = ensured.values

    /** The structure of [key], made sure of by [ensure] when it is not yet, once however many ask at once. */
    fun get(key: K, ensure: () -> T): T = ensured[key]
        ?: locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            ensured[key] ?: ensure().also { ensured[key] = it }
        }

    /** Forgets [value], the structure of [key] that turned out to be gone, so that its next use makes sure of it again. */
    fun forget(key: K, value: T) {
        ensured.remove(key, value)
    }
}
