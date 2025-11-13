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

package me.ahoo.wow.api.query

/**
 * Interface representing a dynamic document that can store arbitrary key-value pairs.
 *
 * This interface extends [MutableMap] and provides additional convenience methods for
 * type-safe value retrieval and nested document access. It's designed for working with
 * dynamic data structures where the schema is not known at compile time.
 */
interface DynamicDocument : MutableMap<String, Any> {
    /**
     * Retrieves a value from the document with type casting.
     *
     * This method provides type-safe access to document values by performing an unchecked cast.
     * Use with caution and ensure the value is actually of the expected type.
     *
     * @param V The expected type of the value.
     * @param key The key to retrieve the value for.
     * @return The value cast to type V.
     * @throws ClassCastException if the value cannot be cast to the specified type.
     * @throws NoSuchElementException if the key is not present in the document.
     */
    @Suppress("UNCHECKED_CAST")
    fun <V> getValue(key: String): V = get(key) as V

    /**
     * Retrieves a nested document from the current document.
     *
     * This method is used to access nested dynamic documents within the current document.
     * The returned document can itself contain nested documents, allowing for deep traversal.
     *
     * @param key The key of the nested document.
     * @return The nested dynamic document.
     * @throws NoSuchElementException if the key is not present.
     * @throws ClassCastException if the value is not a DynamicDocument.
     */
    fun getNestedDocument(key: String): DynamicDocument
}

/**
 * A simple implementation of [DynamicDocument] that delegates to a [MutableMap].
 *
 * This class provides a concrete implementation of the DynamicDocument interface by
 * wrapping an existing mutable map. It supports all standard map operations and
 * provides the additional methods defined in the DynamicDocument interface.
 *
 * @property delegation The underlying mutable map that stores the document data.
 */
class SimpleDynamicDocument(
    val delegation: MutableMap<String, Any>
) : DynamicDocument,
    MutableMap<String, Any> by delegation {
    /**
     * Retrieves a nested document, converting it to a DynamicDocument if necessary.
     *
     * If the nested value is already a DynamicDocument, it is returned directly.
     * Otherwise, if it's a MutableMap, it is converted to a SimpleDynamicDocument.
     *
     * @param key The key of the nested document.
     * @return The nested dynamic document.
     * @throws NoSuchElementException if the key is not present.
     * @throws ClassCastException if the value cannot be converted to a DynamicDocument.
     */
    override fun getNestedDocument(key: String): DynamicDocument = getValue<DynamicDocument>(key).toDynamicDocument()

    companion object {
        /**
         * Extension function to convert a MutableMap to a SimpleDynamicDocument.
         *
         * This provides a convenient way to wrap existing maps as dynamic documents.
         *
         * @receiver The mutable map to convert.
         * @return A new SimpleDynamicDocument wrapping the map.
         */
        fun MutableMap<String, Any>.toDynamicDocument(): SimpleDynamicDocument = SimpleDynamicDocument(this)
    }
}
