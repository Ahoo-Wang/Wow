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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.Wow
import me.ahoo.wow.query.QueryBudget
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Query settings shared by every entry.
 *
 * @property requireExplicitEntry rejects gateway queries that run without a query entry, so in-process callers must
 * say `IN_PROCESS` (HTTP routes always say `HTTP`).
 * @property http the budget of queries that arrive over HTTP, checked by the gateway at admission.
 * @property schema query schema maintenance.
 */
@ConfigurationProperties(prefix = QueryProperties.PREFIX)
class QueryProperties
@Autowired(required = false)
constructor(
    var requireExplicitEntry: Boolean = false,
    var http: Http = Http(),
    var schema: Schema = Schema(),
) {
    /**
     * @property revalidateInterval how often each query schema is reloaded so storage changes made outside a
     * deployment (indexes, mappings, validators) are picked up; `0s` disables periodic revalidation.
     */
    data class Schema(
        @DefaultValue("5m")
        var revalidateInterval: java.time.Duration = java.time.Duration.ofMinutes(5),
    )

    /** Limits of `0` are disabled. */
    data class Http(
        @DefaultValue("1000")
        var maxListSize: Int = 1000,
        @DefaultValue("100")
        var maxPageSize: Int = 100,
        @DefaultValue("10000")
        var maxPageWindow: Long = 10_000,
        @DefaultValue("${QueryBudget.DEFAULT_MAX_FILTER_NODES}")
        var maxFilterNodes: Int = QueryBudget.DEFAULT_MAX_FILTER_NODES,
        @DefaultValue("1000")
        var maxFilterValues: Int = 1000,
        @DefaultValue("true")
        var allowExpensiveOperators: Boolean = true,
    ) {
        fun toBudget(): QueryBudget = QueryBudget(
            label = QueryBudget.HTTP_LABEL,
            maxListSize = maxListSize,
            maxPageSize = maxPageSize,
            maxPageWindow = maxPageWindow,
            maxFilterNodes = maxFilterNodes,
            maxFilterValues = maxFilterValues,
            allowExpensiveOperators = allowExpensiveOperators,
        )
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}query"
    }
}
