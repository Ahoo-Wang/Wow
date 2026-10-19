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

package me.ahoo.wow.migrate

import java.util.concurrent.CopyOnWriteArrayList

/** 9.x `PrepareKey`s in memory, ordered as the MongoDB store reads them: by key. */
class InMemoryNineXReservationSource(keys: Collection<NineXPreparedKey> = emptyList()) : NineXReservationSource {
    private val keys = CopyOnWriteArrayList(keys)

    fun add(key: NineXPreparedKey) {
        keys += key
    }

    /** Removes the key [key] of [name], as 9.x's `rollback` does. */
    fun remove(name: String, key: String) {
        keys.removeIf { it.name == name && it.key == key }
    }

    /** Replaces the key of [key]'s name and key, as 9.x's `reprepare` does. */
    fun update(key: NineXPreparedKey) {
        remove(key.name, key.key)
        keys += key
    }

    override fun prepareKeys(): Map<String, Long> =
        keys.groupingBy { it.name }.eachCount().mapValues { it.value.toLong() }.toSortedMap()

    override fun keys(name: String, after: String?, limit: Int): List<NineXPreparedKey> =
        keys.filter { it.name == name && (after == null || it.key > after) }.sortedBy { it.key }.take(limit)

    override fun key(name: String, key: String): NineXPreparedKey? =
        keys.lastOrNull { it.name == name && it.key == key }
}
