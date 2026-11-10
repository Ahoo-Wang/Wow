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

package me.ahoo.wow.manifest.tools.shadowing

// A domain whose own classes are named as Kotlin's implicitly imported types are: legal, and a view copies them.

data class List(val title: kotlin.String)

data class String(val text: kotlin.String)

class CatalogState {
    var grouping: List? = null
    var tag: String? = null
    var values: kotlin.collections.List<kotlin.String> = emptyList()
    var codes: Set<Int> = emptySet()
    var counts: Map<kotlin.String, Long> = emptyMap()
}

/** The control: the same state, its classes named otherwise. */
data class Entry(val title: kotlin.String)

class EntryState {
    var grouping: Entry? = null
    var values: kotlin.collections.List<kotlin.String> = emptyList()
}
