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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField

/**
 * Builds a [Projection]: the fields to return ([include]) or to leave out ([exclude]); with neither, every field.
 *
 * ```kotlin
 * projection {
 *     include("aggregateId", "state.name")
 *     exclude(OrderStateProperties.STATE__ADDRESS)
 * }
 * ```
 */
@QueryDslMarker
class ProjectionDsl : NestedFieldDsl() {
    private val include = mutableListOf<QueryField<*>>()
    private val exclude = mutableListOf<QueryField<*>>()

    fun include(vararg fields: String) {
        fields.mapTo(include) { QueryField(it.withNestedField()) }
    }

    fun include(vararg fields: QueryField<*>) {
        fields.mapTo(include) { QueryField(it.path.withNestedField()) }
    }

    fun exclude(vararg fields: String) {
        fields.mapTo(exclude) { QueryField(it.withNestedField()) }
    }

    fun exclude(vararg fields: QueryField<*>) {
        fields.mapTo(exclude) { QueryField(it.path.withNestedField()) }
    }

    fun build(): Projection = Projection(include.toList(), exclude.toList())
}
