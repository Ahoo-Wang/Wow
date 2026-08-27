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

package me.ahoo.wow.infra

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe utility for mapping fully qualified class names to Class objects with caching.
 * This object provides efficient type resolution by caching Class instances to avoid
 * repeated Class.forName() calls, which can be expensive.
 *
 * The mapper uses a ConcurrentHashMap to ensure thread-safe operations and lazy loading
 * of Class objects only when first requested.
 */
object TypeNameMapper {
    /**
     * Thread-safe cache mapping fully qualified class names to their corresponding Class objects.
     * Uses ConcurrentHashMap to support concurrent access without external synchronization.
     */
    private val mapper: ConcurrentHashMap<String, Class<*>> = ConcurrentHashMap()

    /**
     * Converts a fully qualified class name string to its corresponding Class object.
     * The result is cached for future lookups to improve performance.
     *
     * @param T the type to cast the resulting Class to
     * @return the Class object corresponding to the class name
     * @throws ClassNotFoundException if the class cannot be found
     *
     * Example usage:
     * ```
     * val stringClass = "java.lang.String".toType<String>() // returns String::class.java
     * val listClass = "java.util.List".toType<List<*>>() // returns List::class.java
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> String.toType(): Class<T> =
        mapper.computeIfAbsent(this) {
            Class.forName(this)
        } as Class<T>
}
