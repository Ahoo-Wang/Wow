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
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.hasArrayBranch
import org.bson.conversions.Bson

internal object MongoSortCompiler {

    fun compile(sort: List<Sort>, admitted: AdmittedQuery<*>): Bson? {
        val resolved = sort.map { admitted.field(it.field) }
        val physicalSort = sort.zip(resolved) { item, field -> item.copy(field = field.physicalField) }
        val compiled = compilePhysical(physicalSort)
        val arrays = resolved.mapNotNull { field ->
            field.physicalField.path.takeIf { field.value.hasArrayBranch() }
        }
        arrays.forEachIndexed { index, left ->
            if (arrays.drop(index + 1).any { right ->
                    left != right && !left.startsWith("$right.") && !right.startsWith("$left.")
                }
            ) {
                throw QuerySchemaValidationException("MongoDB cannot sort independent parallel arrays.")
            }
        }
        return compiled
    }

    internal fun compilePhysical(sort: List<Sort>): Bson? {
        if (sort.isEmpty()) return null
        if (sort.size > 32) throw QuerySchemaValidationException("MongoDB sort supports at most 32 keys.")
        if (sort.map { it.field }.distinct().size != sort.size) {
            throw QuerySchemaValidationException("MongoDB sort fields must map to unique physical fields.")
        }
        return Sorts.orderBy(
            sort.map {
                when (it.direction) {
                    Sort.Direction.ASC -> Sorts.ascending(it.field.path)
                    Sort.Direction.DESC -> Sorts.descending(it.field.path)
                }
            },
        )
    }
}
