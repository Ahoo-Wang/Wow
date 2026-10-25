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

package me.ahoo.wow.mongo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The collections a store has made sure of (format claimed, indexes created, registered), by aggregate name: each is
 * made sure of once, on its first use, and then costs no command, as `EnsuredTables` does on PostgreSQL.
 *
 * Concurrent first uses of one aggregate wait for one ensure rather than each running its own: a node's first commands
 * of a type would otherwise send its schema commands a hundred times at once. They wait on a [ReentrantLock], not a
 * monitor (`computeIfAbsent`): a virtual thread blocked on a monitor pins its carrier (JDK 21), and the ensure runs
 * driver calls. An ensure that fails keeps nothing; the next use runs it again.
 */
internal class EnsuredCollections<T : Any> {
    private val collections = ConcurrentHashMap<String, T>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** The collections made sure of so far. */
    val values: Collection<T> get() = collections.values

    /** The collection of [aggregate], made sure of by [ensure] when it is not yet, once however many ask at once. */
    fun get(aggregate: String, ensure: () -> T): T = collections[aggregate]
        ?: locks.computeIfAbsent(aggregate) { ReentrantLock() }.withLock {
            collections[aggregate] ?: ensure().also { collections[aggregate] = it }
        }
}
