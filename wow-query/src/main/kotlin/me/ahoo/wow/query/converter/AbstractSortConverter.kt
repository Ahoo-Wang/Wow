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

package me.ahoo.wow.query.converter

import me.ahoo.wow.api.query.Sort

abstract class AbstractSortConverter<T> : SortConverter<T> {

    protected abstract val fieldConverter: FieldConverter

    override fun convert(sort: List<Sort>): T {
        if (sort.isEmpty()) {
            return internalConvert(sort)
        }
        val converted = sort.map {
            it.copy(field = fieldConverter.convert(it.field))
        }
        return internalConvert(converted)
    }

    protected abstract fun internalConvert(sort: List<Sort>): T
}
