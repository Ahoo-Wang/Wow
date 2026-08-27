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

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Interface for query objects that support sorting capabilities.
 *
 * This interface provides access to sorting criteria that determine the order
 * in which query results are returned.
 */
interface SortCapable {
    /**
     * The list of sort criteria to apply to the query results.
     * Each sort criterion specifies a field and direction (ascending/descending).
     * Sorts are applied in the order they appear in the list.
     */
    @get:Schema(defaultValue = "[]")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val sort: List<Sort>
}
