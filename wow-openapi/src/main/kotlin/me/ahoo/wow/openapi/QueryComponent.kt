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

package me.ahoo.wow.openapi

import me.ahoo.wow.api.Wow

object QueryComponent {
    const val SINGLE_QUERY_SUFFIX = ".SingleQuery"
    const val COUNT_QUERY_SUFFIX = ".CountQuery"
    const val LIST_QUERY_SUFFIX = ".ListQuery"
    const val PAGED_QUERY_SUFFIX = ".PagedQuery"
    const val CURSOR_QUERY_SUFFIX = ".CursorQuery"
    const val AGGREGATION_QUERY_SUFFIX = ".AggregationQuery"
    const val AGGREGATED_FIELDS_SUFFIX = "AggregatedFields"
    const val QUERY_FIELDS_EXTENSION = "x-wow-query-fields"
    const val SINGLE_QUERY_KEY = Wow.WOW + SINGLE_QUERY_SUFFIX
    const val COUNT_QUERY_KEY = Wow.WOW + COUNT_QUERY_SUFFIX
    const val LIST_QUERY_KEY = Wow.WOW + LIST_QUERY_SUFFIX
    const val PAGED_QUERY_KEY = Wow.WOW + PAGED_QUERY_SUFFIX
    const val CURSOR_QUERY_KEY = Wow.WOW + CURSOR_QUERY_SUFFIX
    const val AGGREGATION_QUERY_KEY = Wow.WOW + AGGREGATION_QUERY_SUFFIX
}
