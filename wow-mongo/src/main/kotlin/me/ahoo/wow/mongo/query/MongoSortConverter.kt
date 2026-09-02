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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.converter.AbstractSortConverter
import me.ahoo.wow.query.converter.FieldConverter
import org.bson.conversions.Bson

class MongoSortConverter(override val fieldConverter: FieldConverter) : AbstractSortConverter<Bson?>() {

    internal fun convertField(field: String): String = fieldConverter.convert(field)

    override fun internalConvert(sort: List<Sort>): Bson? {
        if (sort.isEmpty()) return null
        return sort.map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field.path)
                Sort.Direction.DESC -> Sorts.descending(it.field.path)
            }
        }.toList().let {
            Sorts.orderBy(it)
        }
    }
}
