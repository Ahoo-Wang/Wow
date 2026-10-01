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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger { }

/** The 9.1 prefix of the HTTP query limits, which 9.2 moved to `wow.query.http`. */
const val LEGACY_HTTP_QUERY_PREFIX = "wow.webflux.query"

/*
 * compat(wow<9.2): 9.1 read the HTTP query limits from `wow.webflux.query.*`. A configuration written for 9.1, and one
 * shared by 9.1 and 9.2 nodes of a mixed cluster, must keep its limits on 9.2, so each old key still applies, with a
 * warning, unless its `wow.query.http.*` replacement is also set.
 */
private val LEGACY_HTTP_QUERY_KEYS: List<LegacyHttpQueryKey<*>> = listOf(
    LegacyHttpQueryKey("max-list-size", Int::class.javaObjectType) { copy(maxListSize = it) },
    LegacyHttpQueryKey("max-page-size", Int::class.javaObjectType) { copy(maxPageSize = it) },
    LegacyHttpQueryKey("max-page-window", Long::class.javaObjectType) { copy(maxPageWindow = it) },
    LegacyHttpQueryKey("max-filter-nodes", Int::class.javaObjectType) { copy(maxFilterNodes = it) },
    LegacyHttpQueryKey("max-filter-values", Int::class.javaObjectType) { copy(maxFilterValues = it) },
    LegacyHttpQueryKey("allow-expensive-operators", Boolean::class.javaObjectType) {
        copy(allowExpensiveOperators = it)
    },
)

private class LegacyHttpQueryKey<T : Any>(
    val name: String,
    val type: Class<T>,
    val apply: QueryProperties.Http.(T) -> QueryProperties.Http,
) {
    val legacy = "$LEGACY_HTTP_QUERY_PREFIX.$name"
    val replacement = "${QueryProperties.PREFIX}.http.$name"

    fun applyTo(http: QueryProperties.Http, binder: Binder): QueryProperties.Http {
        val value = binder.bind(legacy, type).orElse(null) ?: return http
        if (binder.bind(replacement, type).isBound) {
            log.warn { "Ignoring deprecated [$legacy]: [$replacement] is also set. Remove [$legacy]." }
            return http
        }
        log.warn { "[$legacy] is deprecated and scheduled for removal in 10.0.0; rename it to [$replacement]." }
        return http.apply(value)
    }
}

/**
 * This budget with each deprecated `wow.webflux.query.*` limit of [environment] applied, where its
 * `wow.query.http.*` replacement is not set.
 */
fun QueryProperties.Http.withLegacyKeys(environment: Environment): QueryProperties.Http {
    val binder = Binder.get(environment)
    return LEGACY_HTTP_QUERY_KEYS.fold(this) { http, key -> key.applyTo(http, binder) }
}
