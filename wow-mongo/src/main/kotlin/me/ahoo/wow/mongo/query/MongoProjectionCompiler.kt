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
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.projectionField
import org.bson.conversions.Bson

internal object MongoProjectionCompiler {

    fun compile(projection: Projection, schema: QueryModelSchema): Bson? =
        compilePhysical(physicalProjection(projection, schema))

    internal fun cursorProjection(
        projection: Projection,
        sortFields: List<String>,
        schema: QueryModelSchema,
    ): MongoCursorProjection = physicalProjection(projection, schema).withCursorFields(sortFields)

    internal fun compile(projection: MongoCursorProjection): Bson? =
        compilePhysical(projection.queryProjection.normalizeAndValidate())

    private fun physicalProjection(projection: Projection, schema: QueryModelSchema): Projection =
        Projection(
            include = projection.include.map { field -> schema.projectionField(field) },
            exclude = projection.exclude.map { field -> schema.projectionField(field) },
        ).normalizeAndValidate()

    private fun compilePhysical(projection: Projection): Bson? {
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

    private fun Projection.normalizeAndValidate(): Projection {
        val normalized = Projection(
            include = include.withoutRedundantDescendants(),
            exclude = exclude.withoutRedundantDescendants(),
        )
        val includesOnlyId = normalized.include.all { it.path == Documents.ID_FIELD } &&
            normalized.exclude.none { it.isIdPath() }
        val excludesOnlyId = normalized.exclude.all { it.path == Documents.ID_FIELD } &&
            normalized.include.none { it.isIdPath() }
        require(
            normalized.include.isEmpty() ||
                normalized.exclude.isEmpty() ||
                includesOnlyId ||
                excludesOnlyId,
        ) { "MongoDB projection cannot mix inclusion and exclusion except when one side only controls [_id]." }
        return if (normalized.include.isNotEmpty() && normalized.exclude.isNotEmpty() && includesOnlyId) {
            normalized.copy(include = emptyList())
        } else {
            normalized
        }
    }

    private fun QueryField.isIdPath(): Boolean =
        path == Documents.ID_FIELD || path.startsWith("${Documents.ID_FIELD}.")

    private fun List<QueryField>.withoutRedundantDescendants(): List<QueryField> {
        val paths = mapTo(HashSet(size), QueryField::path)
        return distinct().filterNot { field -> field.path.hasAncestorIn(paths) }
    }

    private fun String.hasAncestorIn(paths: Set<String>): Boolean {
        var separator = indexOf('.')
        while (separator >= 0) {
            if (substring(0, separator) in paths) return true
            separator = indexOf('.', separator + 1)
        }
        return false
    }
}
