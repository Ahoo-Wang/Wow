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

package me.ahoo.wow.clickhouse.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.dataset.QueryDatasetConfig
import org.junit.jupiter.api.Test
import java.time.Duration

class ClickHouseDatasetProviderTest {
    private val provider = ClickHouseDatasetProvider()

    @Test
    fun `the settings should become the dataset options, each with its default`() {
        val options = provider.optionsOf(
            QueryDatasetConfig.of(
                "clickhouse",
                mapOf(
                    "database" to "analytics",
                    "query.metadata-ttl" to "10s",
                    "query.settings.max_memory_usage" to "1000000",
                ),
            ),
        )
        options.database.assert().isEqualTo("analytics")
        options.metadataTtl.assert().isEqualTo(Duration.ofSeconds(10))
        options.settings.assert().isEqualTo(mapOf("max_memory_usage" to "1000000"))
        val defaults = provider.optionsOf(QueryDatasetConfig.of("clickhouse"))
        defaults.database.assert().isEqualTo("default")
        defaults.metadataTtl.assert().isEqualTo(Duration.ofMinutes(1))
        provider.options.map { it.key }.assert().containsExactly(
            "endpoint",
            "username",
            "password",
            "database",
            "query.enabled",
            "query.metadata-ttl",
            "query.settings",
        )
    }

    @Test
    fun `the backend should be available with the application's client or an endpoint, unless switched off`() {
        provider.available(QueryDatasetConfig.of("clickhouse")).assert().isFalse()
        provider.available(QueryDatasetConfig.of("clickhouse", mapOf("endpoint" to "http://127.0.0.1:1"))).assert()
            .isTrue()
        val off = mapOf("endpoint" to "http://127.0.0.1:1", "query.enabled" to "false")
        provider.available(QueryDatasetConfig.of("clickhouse", off)).assert().isFalse()
        val own = provider.open(QueryDatasetConfig.of("clickhouse", mapOf("endpoint" to "http://127.0.0.1:1")))
        own.assert().isInstanceOf(AutoCloseable::class.java)
        (own as AutoCloseable).close()
    }
}
