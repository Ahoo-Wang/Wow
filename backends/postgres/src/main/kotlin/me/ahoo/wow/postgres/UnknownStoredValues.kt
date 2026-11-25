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

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Warns once per stored value this version of Wow does not know, naming the column and the value, not once per row or
 * per read: a value a later version wrote, which the store reads as it can and keeps as it is, for a node that knows it.
 */
internal class UnknownStoredValues(private val where: String) {
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The `column=value`s warned of so far. */
    val seen: Set<String> get() = warned

    /** Warns of [value] in [column] the first time this store reads it; whether it warned. */
    fun warn(column: String, value: String): Boolean {
        val first = warned.add("$column=$value")
        if (first) {
            log.warn(
                "{}'s {} holds {}, a value this version of Wow does not know (a later version's): it is read as it can " +
                    "be and kept as it is.",
                where,
                column,
                value,
            )
        }
        return first
    }

    private companion object {
        val log = LoggerFactory.getLogger(UnknownStoredValues::class.java)
    }
}
