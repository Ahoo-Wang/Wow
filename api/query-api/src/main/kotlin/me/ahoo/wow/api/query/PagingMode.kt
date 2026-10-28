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
 * How a query model's records may be read, as its descriptor lists them (`record.paging`), by wire name
 * (`list`, `paged`, `cursor`): a list of the first records ([ListQuery]), a page by number ([PagedQuery]), or a
 * page after a cursor ([CursorQuery]), which only a model with a unique sort offers.
 */
enum class PagingMode {
    LIST,
    PAGED,
    CURSOR,
}
