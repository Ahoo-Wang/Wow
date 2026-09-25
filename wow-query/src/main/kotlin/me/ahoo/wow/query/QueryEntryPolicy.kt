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

package me.ahoo.wow.query

/**
 * What a gateway requires of a query's [QueryEntry] before admitting it.
 *
 * @property requireExplicitEntry rejects [QueryEntry.UNSPECIFIED] queries, so every caller must say whether it is
 * HTTP or in-process. Off by default: an unspecified entry is treated as in-process, which keeps existing
 * QueryGateway callers working unchanged.
 */
data class QueryEntryPolicy(
    val requireExplicitEntry: Boolean = false,
) {
    /** The entry the query runs under, after this policy accepted it. */
    fun admit(entry: QueryEntry): QueryEntry {
        check(!(requireExplicitEntry && entry == QueryEntry.UNSPECIFIED)) {
            "Query entry must be explicit: run the query under QueryEntry.HTTP or QueryEntry.IN_PROCESS."
        }
        return entry
    }

    companion object {
        val DEFAULT = QueryEntryPolicy()
    }
}
