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

interface DynamicDocument : Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    fun <V> getValue(key: String): V {
        return get(key) as V
    }

    fun getNestedDocument(key: String): DynamicDocument
}

class SimpleDynamicDocument(val delegation: Map<String, Any>) : DynamicDocument, Map<String, Any> by delegation {

    override fun getNestedDocument(key: String): DynamicDocument {
        return getValue<DynamicDocument>(key).toDynamicDocument()
    }

    companion object {
        fun Map<String, Any>.toDynamicDocument(): SimpleDynamicDocument = SimpleDynamicDocument(this)
    }
}
