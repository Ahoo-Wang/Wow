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

package me.ahoo.wow.spring.boot

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetQueryBackendFactory
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/** The ClickHouse dataset backend wired by [ClickHouseAutoConfiguration] (decision D145). */
class HostClickHouseDatasetsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ClickHouseAutoConfiguration::class.java))
        .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })

    @Test
    fun `with an endpoint the dataset backend should be wired over the client`() {
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1").run { context ->
            context.getBean(DatasetQueryBackendFactory::class.java).assert()
                .isInstanceOf(ClickHouseDatasetQueryBackendFactory::class.java)
        }
        runner.run { context ->
            context.getBeansOfType(DatasetQueryBackendFactory::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `the dataset backend should be switched off by its property or the application's own`() {
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1", "wow.clickhouse.query.enabled=false")
            .run { context ->
                context.getBeansOfType(Client::class.java).assert().hasSize(1)
                context.getBeansOfType(DatasetQueryBackendFactory::class.java).assert().isEmpty()
            }
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1")
            .withBean(InMemoryDatasetStorage::class.java, { InMemoryDatasetStorage() })
            .run { context ->
                context.getBean(DatasetQueryBackendFactory::class.java).assert()
                    .isInstanceOf(InMemoryDatasetStorage::class.java)
            }
    }

    @Test
    fun `the properties should become the dataset backend's options`() {
        runner.withPropertyValues(
            "wow.clickhouse.endpoint=http://127.0.0.1:1",
            "wow.clickhouse.database=analytics",
            "wow.clickhouse.query.metadata-ttl=10s",
            "wow.clickhouse.query.settings.max_memory_usage=1000000",
        ).run { context ->
            val options = ClickHouseAutoConfiguration.datasetOptions(context.getBean(ClickHouseProperties::class.java))
            options.database.assert().isEqualTo("analytics")
            options.metadataTtl.assert().isEqualTo(Duration.ofSeconds(10))
            options.settings.assert().isEqualTo(mapOf("max_memory_usage" to "1000000"))
        }
        ClickHouseAutoConfiguration.datasetOptions(ClickHouseProperties()).metadataTtl.assert()
            .isEqualTo(Duration.ofMinutes(1))
    }
}
