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

package me.ahoo.wow.bi

import java.net.URI
import java.time.Duration

/**
 * Typed configuration for the ClickHouse Java client owned by [ClickHouseBiDeploymentInspector].
 *
 * Property names intentionally follow their corresponding `Client.Builder` methods.
 */
data class ClickHouseClientOptions(
    val endpoints: List<URI>,
    val username: String = DEFAULT_USERNAME,
    val password: String = "",
    val connectionPoolEnabled: Boolean = true,
    val connectionTimeout: Duration = Duration.ofSeconds(3),
    val connectionRequestTimeout: Duration = Duration.ofSeconds(10),
    val socketTimeout: Duration = Duration.ofSeconds(10),
    val executionTimeout: Duration = Duration.ofSeconds(10),
    val maxConnections: Int = 10,
    val maxRetries: Int = 0,
) {
    init {
        require(endpoints.isNotEmpty()) { "endpoints must not be empty" }
        endpoints.forEachIndexed { index, endpoint -> endpoint.validate(index) }
        require(endpoints.distinct().size == endpoints.size) { "endpoints must not contain duplicates" }
        require(username.isNotBlank()) { "username must not be blank" }
        connectionTimeout.requireValidTimeout("connectionTimeout", requireAtLeastOneMillisecond = true)
        connectionRequestTimeout.requireValidTimeout(
            "connectionRequestTimeout",
            requireAtLeastOneMillisecond = true,
        )
        socketTimeout.requireValidTimeout(
            "socketTimeout",
            maxMillis = Int.MAX_VALUE.toLong(),
            requireAtLeastOneMillisecond = true,
        )
        executionTimeout.requireValidTimeout(
            "executionTimeout",
            allowZero = true,
            maxMillis = Int.MAX_VALUE.toLong(),
            requireAtLeastOneMillisecond = true,
        )
        require(maxConnections > 0) { "maxConnections must be greater than zero" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    override fun toString(): String =
        "ClickHouseClientOptions(" +
            "endpoints=$endpoints, " +
            "username=$username, " +
            "password=******, " +
            "connectionPoolEnabled=$connectionPoolEnabled, " +
            "connectionTimeout=$connectionTimeout, " +
            "connectionRequestTimeout=$connectionRequestTimeout, " +
            "socketTimeout=$socketTimeout, " +
            "executionTimeout=$executionTimeout, " +
            "maxConnections=$maxConnections, " +
            "maxRetries=$maxRetries)"

    private companion object {
        const val DEFAULT_USERNAME: String = "default"
    }
}

private fun URI.validate(index: Int) {
    val supportedScheme = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    require(supportedScheme) { "endpoints[$index] must use http or https" }
    require(!host.isNullOrBlank()) { "endpoints[$index] must contain a host" }
    require(port in 1..65535) { "endpoints[$index] must contain an explicit valid port" }
    require(userInfo == null && query == null && fragment == null) {
        "endpoints[$index] must not contain user info, query, or fragment"
    }
}

internal fun Duration.requireValidTimeout(
    name: String,
    allowZero: Boolean = false,
    maxMillis: Long = Long.MAX_VALUE,
    requireAtLeastOneMillisecond: Boolean = false,
): Long {
    require(!isNegative && (allowZero || !isZero)) {
        if (allowZero) "$name must not be negative" else "$name must be greater than zero"
    }
    val millis = try {
        toMillis()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$name is too large", error)
    }
    require(!requireAtLeastOneMillisecond || isZero || millis >= 1) {
        if (allowZero) {
            "$name must be zero or at least 1 millisecond"
        } else {
            "$name must be at least 1 millisecond"
        }
    }
    require(millis <= maxMillis) { "$name must not exceed $maxMillis milliseconds" }
    return millis
}
