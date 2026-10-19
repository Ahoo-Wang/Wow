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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder

/**
 * How many connections this client opens to one node: the `maxConnPerRoute` of the HTTP client's pool (Apache
 * HttpClient 5's `PoolingAsyncClientConnectionManager.getDefaultMaxPerRoute`), which the client exposes only through
 * the HTTP client it wraps, so it is read from that client's connection manager without depending on HttpClient. A
 * client whose pool cannot be read is taken to have the pool `Rest5ClientBuilder` gives one by default
 * ([Rest5ClientBuilder.DEFAULT_MAX_CONN_PER_ROUTE]).
 */
internal fun Rest5Client.maxConnectionsPerRoute(): Int =
    poolOf(httpClient)?.let(::defaultMaxPerRoute) ?: Rest5ClientBuilder.DEFAULT_MAX_CONN_PER_ROUTE

/** The connection manager of [httpClient]: the first field, up its class hierarchy, holding a sized pool. */
internal fun poolOf(httpClient: Any): Any? = generateSequence<Class<*>>(httpClient.javaClass) { it.superclass }
    .flatMap { it.declaredFields.asSequence() }
    .filter { !it.type.isPrimitive }
    .firstNotNullOfOrNull { field ->
        runCatching {
            field.isAccessible = true
            field.get(httpClient)?.takeIf { defaultMaxPerRoute(it) != null }
        }.getOrNull()
    }

/** The `getDefaultMaxPerRoute()` of [pool], or null when it has none or it is not positive. */
internal fun defaultMaxPerRoute(pool: Any): Int? = try {
    (pool.javaClass.getMethod("getDefaultMaxPerRoute").invoke(pool) as? Int)?.takeIf { it > 0 }
} catch (ignored: ReflectiveOperationException) {
    null
}
