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
 * How a paged query counts its total, the [PagedList.total] it returns.
 */
enum class TotalMode {
    /** Counts every match: [PagedList.total] is exact. */
    EXACT,

    /**
     * Counts up to the budget's count limit: [PagedList.total] is exact below it; otherwise it is the limit and
     * [PagedList.exact] is `false`, read as "at least" (shown as `1000+`). The default.
     */
    BOUNDED,

    /** Does not count: [PagedList.total] is `null`. Endless scrolling uses [CursorQuery] instead. */
    NONE,
}
