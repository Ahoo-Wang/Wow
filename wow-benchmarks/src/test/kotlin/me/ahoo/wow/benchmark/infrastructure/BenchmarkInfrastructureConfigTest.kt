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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BenchmarkInfrastructureConfigTest {

    @Test
    fun `should load default infrastructure config`() {
        val config = BenchmarkInfrastructureConfig.load(
            property = { null },
            environment = { null },
        )

        config.redis.endpoint.host.assert().isEqualTo("localhost")
        config.redis.endpoint.port.assert().isEqualTo(6379)
        config.mongo.endpoint.host.assert().isEqualTo("localhost")
        config.mongo.endpoint.port.assert().isEqualTo(27017)
        config.mongo.username.assert().isEqualTo("root")
        config.mongo.password.assert().isEqualTo("root")
        config.mongo.connectionString().assert().isEqualTo("mongodb://root:root@localhost:27017")
    }

    @Test
    fun `should load infrastructure config from environment`() {
        val config = BenchmarkInfrastructureConfig.load(
            property = { null },
            environment = { name ->
                mapOf(
                    "WOW_BENCHMARK_REDIS_HOST" to "redis.local",
                    "WOW_BENCHMARK_REDIS_HOST_PORT" to "6380",
                    "WOW_BENCHMARK_MONGO_HOST" to "mongo.local",
                    "WOW_BENCHMARK_MONGO_HOST_PORT" to "27018",
                    "WOW_BENCHMARK_MONGO_ROOT_USERNAME" to "admin",
                    "WOW_BENCHMARK_MONGO_ROOT_PASSWORD" to "secret",
                )[name]
            },
        )

        config.redis.endpoint.host.assert().isEqualTo("redis.local")
        config.redis.endpoint.port.assert().isEqualTo(6380)
        config.mongo.endpoint.host.assert().isEqualTo("mongo.local")
        config.mongo.endpoint.port.assert().isEqualTo(27018)
        config.mongo.username.assert().isEqualTo("admin")
        config.mongo.password.assert().isEqualTo("secret")
        config.mongo.connectionString().assert().isEqualTo("mongodb://admin:secret@mongo.local:27018")
    }

    @Test
    fun `should prefer system property over environment`() {
        val config = BenchmarkInfrastructureConfig.load(
            property = { name ->
                mapOf(
                    "wow.benchmark.redis.host" to "property.redis",
                    "wow.benchmark.redis.port" to "6381",
                )[name]
            },
            environment = { name ->
                mapOf(
                    "WOW_BENCHMARK_REDIS_HOST" to "env.redis",
                    "WOW_BENCHMARK_REDIS_HOST_PORT" to "6380",
                )[name]
            },
        )

        config.redis.endpoint.host.assert().isEqualTo("property.redis")
        config.redis.endpoint.port.assert().isEqualTo(6381)
    }

    @Test
    fun `should encode mongo credentials in connection string`() {
        val config = BenchmarkInfrastructureConfig.load(
            property = { name ->
                mapOf(
                    "wow.benchmark.mongo.username" to "bench user",
                    "wow.benchmark.mongo.password" to "p@ss/word",
                )[name]
            },
            environment = { null },
        )

        config.mongo.connectionString().assert()
            .isEqualTo("mongodb://bench%20user:p%40ss%2Fword@localhost:27017")
    }

    @Test
    fun `should reject invalid port`() {
        val error = assertThrows<IllegalArgumentException> {
            BenchmarkInfrastructureConfig.load(
                property = { name ->
                    mapOf("wow.benchmark.mongo.port" to "invalid")[name]
                },
                environment = { null },
            )
        }

        error.message.assert().isEqualTo("wow.benchmark.mongo.port must be a positive integer.")
    }
}
