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

package me.ahoo.wow.benchmark.infrastructure

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class BenchmarkInfrastructure(
    val redis: BenchmarkRedisConfig,
    val mongo: BenchmarkMongoConfig,
)

data class BenchmarkServiceEndpoint(
    val host: String,
    val port: Int,
)

data class BenchmarkRedisConfig(
    val endpoint: BenchmarkServiceEndpoint,
)

data class BenchmarkMongoConfig(
    val endpoint: BenchmarkServiceEndpoint,
    val username: String,
    val password: String,
) {
    fun connectionString(): String {
        return "mongodb://${username.encodeUriUserInfo()}:${password.encodeUriUserInfo()}@" +
            "${endpoint.host}:${endpoint.port}"
    }

    private fun String.encodeUriUserInfo(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8)
            .replace("+", "%20")
    }
}

object BenchmarkInfrastructureConfig {
    private const val DEFAULT_HOST = "localhost"
    private const val DEFAULT_REDIS_PORT = "6379"
    private const val DEFAULT_MONGO_PORT = "27017"
    private const val DEFAULT_MONGO_USERNAME = "root"
    private const val DEFAULT_MONGO_PASSWORD = "root"

    private const val REDIS_HOST_PROPERTY = "wow.benchmark.redis.host"
    private const val REDIS_PORT_PROPERTY = "wow.benchmark.redis.port"
    private const val MONGO_HOST_PROPERTY = "wow.benchmark.mongo.host"
    private const val MONGO_PORT_PROPERTY = "wow.benchmark.mongo.port"
    private const val MONGO_USERNAME_PROPERTY = "wow.benchmark.mongo.username"
    private const val MONGO_PASSWORD_PROPERTY = "wow.benchmark.mongo.password"

    private const val REDIS_HOST_ENV = "WOW_BENCHMARK_REDIS_HOST"
    private const val REDIS_PORT_ENV = "WOW_BENCHMARK_REDIS_HOST_PORT"
    private const val MONGO_HOST_ENV = "WOW_BENCHMARK_MONGO_HOST"
    private const val MONGO_PORT_ENV = "WOW_BENCHMARK_MONGO_HOST_PORT"
    private const val MONGO_USERNAME_ENV = "WOW_BENCHMARK_MONGO_ROOT_USERNAME"
    private const val MONGO_PASSWORD_ENV = "WOW_BENCHMARK_MONGO_ROOT_PASSWORD"

    fun load(
        property: (String) -> String? = System::getProperty,
        environment: (String) -> String? = System::getenv,
    ): BenchmarkInfrastructure {
        return BenchmarkInfrastructure(
            redis = BenchmarkRedisConfig(
                endpoint = BenchmarkServiceEndpoint(
                    host = configValue(REDIS_HOST_PROPERTY, REDIS_HOST_ENV, DEFAULT_HOST, property, environment),
                    port = portValue(REDIS_PORT_PROPERTY, REDIS_PORT_ENV, DEFAULT_REDIS_PORT, property, environment),
                ),
            ),
            mongo = BenchmarkMongoConfig(
                endpoint = BenchmarkServiceEndpoint(
                    host = configValue(MONGO_HOST_PROPERTY, MONGO_HOST_ENV, DEFAULT_HOST, property, environment),
                    port = portValue(MONGO_PORT_PROPERTY, MONGO_PORT_ENV, DEFAULT_MONGO_PORT, property, environment),
                ),
                username = configValue(
                    MONGO_USERNAME_PROPERTY,
                    MONGO_USERNAME_ENV,
                    DEFAULT_MONGO_USERNAME,
                    property,
                    environment,
                ),
                password = configValue(
                    MONGO_PASSWORD_PROPERTY,
                    MONGO_PASSWORD_ENV,
                    DEFAULT_MONGO_PASSWORD,
                    property,
                    environment,
                ),
            ),
        )
    }

    private fun configValue(
        propertyName: String,
        environmentName: String,
        defaultValue: String,
        property: (String) -> String?,
        environment: (String) -> String?,
    ): String {
        return property(propertyName)?.takeIf { it.isNotBlank() }
            ?: environment(environmentName)?.takeIf { it.isNotBlank() }
            ?: defaultValue
    }

    private fun portValue(
        propertyName: String,
        environmentName: String,
        defaultValue: String,
        property: (String) -> String?,
        environment: (String) -> String?,
    ): Int {
        val configuredPort = configValue(propertyName, environmentName, defaultValue, property, environment)
        val port = configuredPort.toIntOrNull()
        require(port != null && port > 0) {
            "$propertyName must be a positive integer."
        }
        return port
    }
}
