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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.query.schema.QueryModelSchema
import org.bson.conversions.Bson

class MongoProjectionCompiler {

    fun compile(projection: Projection, schema: QueryModelSchema): Bson? =
        compile(physicalProjection(projection, schema))

    internal fun cursorProjection(
        projection: Projection,
        sortFields: List<String>,
        schema: QueryModelSchema,
    ): MongoCursorProjection = physicalProjection(projection, schema).withCursorFields(sortFields)

    internal fun compile(projection: MongoCursorProjection): Bson? = compile(projection.queryProjection)

    private fun physicalProjection(projection: Projection, schema: QueryModelSchema): Projection =
        Projection(
            include = projection.include.map { field -> schema.field(field)?.projectionField ?: field },
            exclude = projection.exclude.map { field -> schema.field(field)?.projectionField ?: field },
        )

    private fun compile(projection: Projection): Bson? {
        if (projection.isEmpty()) return null
        if (projection.include.isNotEmpty() && projection.exclude.isNotEmpty()) {
            return Projections.fields(
                Projections.include(projection.include.map(QueryField::path)),
                Projections.exclude(projection.exclude.map(QueryField::path))
            )
        }
        if (projection.include.isNotEmpty()) {
            return Projections.include(projection.include.map(QueryField::path))
        }
        return Projections.exclude(projection.exclude.map(QueryField::path))
    }
}
