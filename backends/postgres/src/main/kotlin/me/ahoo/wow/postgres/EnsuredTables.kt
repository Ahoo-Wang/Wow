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

package me.ahoo.wow.postgres

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The tables a store has made sure of (created, registered, format checked), by key: each is made sure of once, on
 * its first use, and then costs no statement (B.O-1).
 *
 * Concurrent first uses of one key wait for one ensure rather than each running its own, which queued them all on
 * [PostgresFormat.creating]'s advisory lock. They wait on a [ReentrantLock], not a monitor (`computeIfAbsent`): a
 * virtual thread blocked on a monitor pins its carrier (JDK 21), and the ensure runs JDBC calls.
 */
internal class EnsuredTables<T : Any> {
    private val tables = ConcurrentHashMap<String, T>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** The tables made sure of so far. */
    val values: Collection<T> get() = tables.values

    /** The table of [key], made sure of by [ensure] when it is not yet, once however many ask at once. */
    fun get(key: String, ensure: () -> T): T = tables[key]
        ?: locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            tables[key] ?: ensure().also { tables[key] = it }
        }

    /** Forgets [table], the table of [key] that turned out to be gone, so that its next use makes sure of it again. */
    fun forget(key: String, table: T) {
        tables.remove(key, table)
    }
}
