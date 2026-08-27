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

package me.ahoo.wow.metadata

import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract base class for metadata parsers that provides caching functionality to improve performance
 * by avoiding repeated parsing of the same class metadata. This class implements a thread-safe
 * caching mechanism using ConcurrentHashMap to store parsed metadata.
 *
 * Subclasses must implement the [parseToMetadata] method to provide the actual parsing logic.
 */
abstract class CacheableMetadataParser {
    /**
     * Thread-safe cache that stores parsed metadata keyed by the class type.
     * Uses ConcurrentHashMap to ensure safe concurrent access and updates.
     */
    private val cache: ConcurrentHashMap<Class<*>, Metadata> = ConcurrentHashMap()

    /**
     * Parses metadata for the given class type, utilizing caching to avoid redundant parsing operations.
     * If metadata for the class has already been parsed and cached, returns the cached instance.
     * Otherwise, parses the metadata using [parseToMetadata] and stores it in the cache.
     *
     * example:
     * ```
     * class MyMetadataParser : CacheableMetadataParser() {
     *     override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
     *         // Implementation here
     *     }
     * }
     *
     * val parser = MyMetadataParser()
     * val metadata = parser.parse<MyClass, MyMetadata>(MyClass::class.java)
     * ```
     * @param TYPE the type of the class being parsed
     * @param M the specific metadata type being returned
     * @param type the Class object representing the type to parse metadata for
     * @return the parsed metadata of type M
     * @throws ClassCastException if the cached metadata cannot be cast to the expected type M
     */
    fun <TYPE : Any, M : Metadata> parse(type: Class<TYPE>): M {
        @Suppress("UNCHECKED_CAST")
        return cache.computeIfAbsent(type) {
            parseToMetadata(type)
        } as M
    }

    /**
     * Abstract method that must be implemented by subclasses to provide the actual metadata parsing logic.
     * This method is called only when metadata for a class is not already cached.
     *
     * @param TYPE the type of the class being parsed
     * @param M the specific metadata type to return
     * @param type the Class object representing the type to parse metadata for
     * @return the parsed metadata of type M
     */
    abstract fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M
}
