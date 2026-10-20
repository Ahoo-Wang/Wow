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

package me.ahoo.wow.datasetregistry.starter

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.datasetregistry.api.DatasetRegistryErrorCodes
import me.ahoo.wow.datasetregistry.domain.DatasetPublicationCheck
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.datasetregistry.domain.DatasetTableInspector
import me.ahoo.wow.spring.boot.starter.clickhouse.ClickHouseDatasetProperties
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import reactor.kotlin.test.test
import java.time.Duration

class DatasetRegistryAutoConfigurationTest {
    private fun runner() = ApplicationContextRunner()
        .withPropertyValues("wow.context-name=dataset-registry-test")
        .withConfiguration(
            AutoConfigurations.of(
                QuerySchemaAutoConfiguration::class.java,
                QueryAutoConfiguration::class.java,
                DatasetRegistryAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `the registry is embedded with its ports, and refuses to draft without a backend that reads tables`() {
        runner().withPropertyValues("wow.dataset-registry.refresh-interval=5m").run { context ->
            context.assert().hasNotFailed()
            context.getBean(DatasetRegistryProjection::class.java).assert().isNotNull()
            context.getBean(DatasetRegistryProperties::class.java).refreshInterval
                .assert().isEqualTo(Duration.ofMinutes(5))
            context.getBean(DatasetPublicationCheck::class.java)
                .assert().isInstanceOf(CompilingDatasetPublicationCheck::class.java)
            context.getBean(PublishedDatasetSource::class.java)
                .assert().isInstanceOf(SnapshotPublishedDatasetSource::class.java)
            context.getBean(DatasetTableInspector::class.java).draft("bi.orders", null).test().expectErrorSatisfies {
                (it as DatasetRegistryException).errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_INVALID)
            }.verify()
            DatasetRegistryErrorStatuses.STATUSES.forEach { (code, status) ->
                ErrorHttpStatusMapping.getHttpStatus(code).assert().isEqualTo(status)
            }
            DatasetRegistryErrorStatuses.STATUSES[DatasetRegistryErrorCodes.DATASET_NAME_TAKEN]
                .assert().isEqualTo(HttpStatus.CONFLICT)
        }
    }

    @Test
    fun `a ClickHouse executor drafts from ClickHouse tables`() {
        runner()
            .withBean(ClickHouseExecutor::class.java, { ClickHouseExecutor(client()) })
            .withBean(ClickHouseDatasetProperties::class.java, { ClickHouseDatasetProperties() })
            .run { context ->
                context.assert().hasNotFailed()
                context.getBean(DatasetTableInspector::class.java)
                    .assert().isInstanceOf(ClickHouseDatasetTableInspector::class.java)
            }
    }

    private fun client(): Client =
        Client.Builder().addEndpoint("http://localhost:1").setUsername("default").setPassword("").build()

    @Test
    fun `the registry can be turned off`() {
        runner().withPropertyValues("wow.dataset-registry.enabled=false").run { context ->
            context.assert().hasNotFailed()
            context.assert().doesNotHaveBean(DatasetRegistryProjection::class.java)
        }
    }
}
