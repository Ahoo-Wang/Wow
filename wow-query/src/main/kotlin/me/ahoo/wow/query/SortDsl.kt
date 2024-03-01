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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.Sort

/**
 * ``` kotlin
 * sort {
 *  "field1" eq "value1"
 *  "field2" eq "value2"
 * }
 * ```
 */
class SortDsl {

    private val sorts: MutableList<Sort> = mutableListOf()

    fun sort(sort: Sort) {
        sorts.add(sort)
    }

    fun String.asc() {
        sorts.add(Sort(this, Sort.Direction.ASC))
    }

    fun String.desc() {
        sorts.add(Sort(this, Sort.Direction.DESC))
    }

    fun build(): List<Sort> {
        return sorts.toList()
    }
}
