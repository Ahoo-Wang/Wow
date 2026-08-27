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

package me.ahoo.wow.api.query

/**
 * Interface for query objects that support field projection.
 *
 * This interface extends [RewritableProjection] and provides access to the projection
 * settings that control which fields are included or excluded from query results.
 *
 * @param Q The type of the query object that implements this interface, enabling method chaining.
 */
interface ProjectionCapable<Q : RewritableProjection<Q>> : RewritableProjection<Q> {
    /**
     * The projection settings that control which fields are included in or excluded from the query results.
     */
    val projection: Projection
}
