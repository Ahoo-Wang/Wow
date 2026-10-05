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

package me.ahoo.wow.configuration

import java.util.SortedMap

/**
 * Comparator for sorting package scopes by depth (number of dots) and length.
 * Used to prioritize more specific scopes over general ones.
 */
object ScopeComparator : Comparator<String> {
    /**
     * Compares two scope strings for ordering.
     * First compares by package depth (more dots = higher priority), then by string length, then lexicographically.
     *
     * @param o1 The first scope string.
     * @param o2 The second scope string.
     * @return A negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second.
     */
    override fun compare(
        o1: String,
        o2: String
    ): Int {
        val o1Size = dotSize(o1)
        val o2Size = dotSize(o2)
        var comparedValue = o2Size.compareTo(o1Size)
        if (comparedValue != 0) {
            return comparedValue
        }
        comparedValue = o2.length.compareTo(o1.length)
        if (comparedValue != 0) {
            return comparedValue
        }
        return o2.compareTo(o1)
    }

    private fun dotSize(value: String): Int = value.split('.').size
}

/**
 * Interface for searching values by package scope strings.
 * Supports hierarchical scope matching where more specific scopes take precedence.
 *
 * @param V The type of values stored in the searcher.
 */
interface ScopeSearcher<V : Any> : SortedMap<String, V> {
    /**
     * Searches for a value by scope, checking exact matches first, then prefix matches.
     *
     * @param scope The scope string to search for.
     * @return The matching value or null if not found.
     */
    fun search(scope: String): V? {
        this[scope]?.let {
            return it
        }

        forEach {
            if (scope.startsWith("${it.key}.")) {
                return it.value
            }
        }
        return null
    }

    /**
     * Searches for a value by scope, throwing an exception if not found.
     *
     * @param scope The scope string to search for.
     * @return The matching value.
     * @throws IllegalStateException if no matching value is found.
     */
    fun requiredSearch(scope: String): V = checkNotNull(search(scope)) {
        "Scope:[$scope] does not have a matching metadata definition."
    }
}
