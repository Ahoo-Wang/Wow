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

package me.ahoo.wow.spring.boot.starter.clickhouse

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetQueryBackendFactory
import me.ahoo.wow.clickhouse.query.ClickHouseQueryLimits
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class ClickHouseDatasetAutoConfigurationTest {
    private val runner = ApplicationContextRunner().enableWow()
        .withUserConfiguration(ClickHouseDatasetAutoConfiguration::class.java)

    @Test
    fun `ClickHouse serves datasets only when enabled`() {
        runner.run { context ->
            context.assert().hasNotFailed()
            context.getBeansOfType(DatasetQueryBackendFactory::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `an enabled ClickHouse backend reads the configured server and limits`() {
        runner.withPropertyValues(
            "wow.query.clickhouse.enabled=true",
            "wow.query.clickhouse.endpoints=http://localhost:8123",
            "wow.query.clickhouse.database=bi",
            "wow.query.clickhouse.http.max-rows-to-read=1000",
            "wow.query.clickhouse.in-process.max-execution-time=5m",
        ).run { context ->
            context.assert().hasNotFailed()
            context.getBean(Client::class.java).assert().isNotNull()
            context.getBean(DatasetQueryBackendFactory::class.java)
                .assert().isInstanceOf(ClickHouseDatasetQueryBackendFactory::class.java)
            val options = context.getBean(ClickHouseDatasetProperties::class.java).toOptions()
            options.defaultDatabase.assert().isEqualTo("bi")
            options.limits(QueryEntry.HTTP).assert().isEqualTo(
                ClickHouseQueryLimits(Duration.ofSeconds(30), 1000, null, 4L * 1024 * 1024 * 1024),
            )
            options.limits(QueryEntry.IN_PROCESS).maxExecutionTime.assert().isEqualTo(Duration.ofMinutes(5))
        }
    }

    @Test
    fun `named sources each get their own client and default database`() {
        runner.withPropertyValues(
            "wow.query.clickhouse.enabled=true",
            "wow.query.clickhouse.endpoints=http://localhost:8123",
            "wow.query.clickhouse.sources.warehouse.endpoints=http://dw:8123",
            "wow.query.clickhouse.sources.warehouse.database=dw",
            "wow.query.clickhouse.sources.warehouse.username=reader",
        ).run { context ->
            context.assert().hasNotFailed()
            val properties = context.getBean(ClickHouseDatasetProperties::class.java)
            val warehouse = properties.sources.getValue("warehouse")
            warehouse.username.assert().isEqualTo("reader")
            warehouse.maxConnections.assert().isEqualTo(16)
            properties.defaultSource().endpoints.single().toString().assert().isEqualTo("http://localhost:8123")
            val sources = context.getBean(ClickHouseDatasetSources::class.java)
            sources.sources.keys.assert().containsExactly("warehouse")
            sources.sources.getValue("warehouse").database.assert().isEqualTo("dw")
        }
        runner.withPropertyValues(
            "wow.query.clickhouse.enabled=true",
            "wow.query.clickhouse.endpoints=http://localhost:8123",
            "wow.query.clickhouse.sources.warehouse.database=dw",
        ).run { context ->
            context.assert().hasFailed()
            context.startupFailure.assert().rootCause().hasMessageContaining("sources.warehouse.endpoints")
        }
    }

    @Test
    fun `an enabled ClickHouse backend needs an endpoint`() {
        runner.withPropertyValues("wow.query.clickhouse.enabled=true").run { context ->
            context.assert().hasFailed()
            context.startupFailure.assert().rootCause().hasMessageContaining("endpoints")
        }
    }
}
