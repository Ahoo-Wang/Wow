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

import com.mongodb.client.model.Projections
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.query.converter.AbstractProjectionConverter
import me.ahoo.wow.query.converter.FieldConverter
import org.bson.conversions.Bson

class MongoProjectionConverter(override val fieldConverter: FieldConverter) : AbstractProjectionConverter<Bson?>() {

    internal fun cursorProjection(projection: Projection, sortFields: List<String>): MongoCursorProjection {
        val physical = if (projection.isEmpty()) {
            projection
        } else {
            Projection(
                include = projection.include.map(fieldConverter::convert),
                exclude = projection.exclude.map(fieldConverter::convert),
            )
        }
        return physical.withCursorFields(sortFields)
    }

    internal fun convertCursor(projection: MongoCursorProjection): Bson? = internalConvert(projection.queryProjection)

    override fun internalConvert(projection: Projection): Bson? {
        if (projection.isEmpty()) return null
        if (projection.include.isNotEmpty() && projection.exclude.isNotEmpty()) {
            return Projections.fields(Projections.include(projection.include), Projections.exclude(projection.exclude))
        }
        if (projection.include.isNotEmpty()) {
            return Projections.include(projection.include)
        }
        return Projections.exclude(projection.exclude)
    }
}
