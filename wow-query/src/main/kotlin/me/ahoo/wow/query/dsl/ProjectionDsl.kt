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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.Projection

/**
 * DSL for building a [Projection] that defines which fields to include or exclude in the query result.
 *
 * This class extends [NestedFieldDsl], allowing for nested field handling. It provides methods to specify
 * fields to be included or excluded from the projection, and then build a [Projection] object.
 *
 * Example usage:
 * ```kotlin
 * val projection = projection {
 *     include("name", "email")
 *     exclude("password")
 * }
 * ```
 *
 * @see NestedFieldDsl
 * @see Projection
 */
@QueryDslMarker
class ProjectionDsl : NestedFieldDsl() {

    private val include = mutableListOf<String>()
    private val exclude = mutableListOf<String>()

    fun include(vararg fields: String) {
        fields.forEach {
            include.add(it.withNestedField())
        }
    }

    fun exclude(vararg fields: String) {
        fields.forEach {
            exclude.add(it.withNestedField())
        }
    }

    fun build(): Projection {
        return Projection(include, exclude)
    }
}
