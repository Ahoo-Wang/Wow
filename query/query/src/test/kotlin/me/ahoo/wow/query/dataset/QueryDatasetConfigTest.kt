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

package me.ahoo.wow.query.dataset

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryDatasetConfigTest {
    private val database = ProviderOption.string("database", "the database.", "default")
    private val enabled = ProviderOption.boolean("query.enabled", "whether it serves datasets.")

    @Test
    fun `a dataset backend should read its options, its settings under a prefix, and name its key`() {
        val config = QueryDatasetConfig.of(
            "clickhouse",
            mapOf("database" to "bi", "query.settings.max_threads" to "4", "query.enabled" to "maybe"),
        )
        config.key.assert().isEqualTo("wow.clickhouse")
        config.option(database).assert().isEqualTo("bi")
        QueryDatasetConfig.of("clickhouse").option(database).assert().isEqualTo("default")
        config.options("query.settings").assert().isEqualTo(mapOf("max_threads" to "4"))
        assertThrows<WowConfigurationException> { config.option(enabled) }.key.assert()
            .isEqualTo("wow.clickhouse.query.enabled")
        config.toString().assert().isEqualTo(
            "QueryDatasetConfig(type=clickhouse, options=[database, query.settings.max_threads, query.enabled])",
        )
    }
}
