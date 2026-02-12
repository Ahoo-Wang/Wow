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

import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.SingleQuery

/**
 * Represents a DSL for constructing a single query. This class extends [QueryableDsl] and is specifically
 * designed to build instances of [ISingleQuery]. It allows for the fluent and type-safe construction of queries,
 * including setting up projections, conditions, and sorting.
 *
 * Example usage:
 * ```kotlin
 * val query = singleQuery {
 *     condition {
 *         "name" eq "John Doe"
 *     }
 *     projection {
 *         include("id", "name")
 *     }
 *     sort {
 *         "name".asc()
 *         "age".desc()
 *     }
 * }
 * ```
 *
 * @see QueryableDsl
 * @see ISingleQuery
 */
@QueryDslMarker
class SingleQueryDsl : QueryableDsl<ISingleQuery>() {

    override fun build(): ISingleQuery {
        return SingleQuery(condition, projection, sort)
    }
}
