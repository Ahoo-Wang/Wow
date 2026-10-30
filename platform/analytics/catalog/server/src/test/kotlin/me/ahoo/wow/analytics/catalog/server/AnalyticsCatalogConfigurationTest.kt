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

package me.ahoo.wow.analytics.catalog.server

import com.clickhouse.client.api.Client
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouse
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTableInfo
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.health.contributor.Status
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Duration

@OptIn(InternalWowApi::class) // The host's settings, as the configuration reads them.
class AnalyticsCatalogConfigurationTest {
    private val configuration = AnalyticsCatalogConfiguration()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> provider(value: T?): ObjectProvider<T> = (mockk<ObjectProvider<T>>()).also {
        every { it.ifAvailable } returns value
        every { it.ifUnique } returns value
        every { it.getObject() } answers { checkNotNull(value) }
    }

    private fun environment(vararg properties: Pair<String, Any>) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
    }

    @Test
    fun `the service's ClickHouse client should be one of the endpoint and credentials of wow clickhouse`() {
        val environment = environment(
            "wow.clickhouse.endpoint" to "http://127.0.0.1:1",
            "wow.clickhouse.username" to "catalog",
        )
        configuration.analyticsCatalogClickHouseClient(environment).use { client ->
            client.assert().isNotNull()
        }
        assertThrows<IllegalArgumentException> { configuration.analyticsCatalogClickHouseClient(environment()) }
            .message.assert().contains("wow.clickhouse.endpoint")
    }

    @Test
    fun `the catalog should be ClickHouse's over the configured databases, or none without a client`() {
        val clickhouse = environment("wow.clickhouse.database" to "wow")
        configuration.warehouseCatalog(provider<Client>(null), AnalyticsCatalogProperties(), clickhouse)
            .assert().isSameAs(WarehouseCatalog.NONE)
        val client = mockk<Client>()
        val defaulted = configuration.warehouseCatalog(provider(client), AnalyticsCatalogProperties(), clickhouse)
        defaulted.databases.assert().containsExactly("wow")
        // Without wow.clickhouse.database, ClickHouse's own default database.
        configuration.warehouseCatalog(provider(client), AnalyticsCatalogProperties(), environment())
            .databases.assert().containsExactly("default")
        defaulted.engine.assert().isEqualTo("clickhouse")
        val listed = configuration.warehouseCatalog(
            provider(client),
            AnalyticsCatalogProperties(connection = "dw", databases = mutableListOf("a", " ", "b")),
            clickhouse,
        )
        listed.connection.assert().isEqualTo("dw")
        listed.databases.assert().containsExactly("a", "b")
    }

    @Test
    @OptIn(ExperimentalWowApi::class)
    fun `the datasets should be published into the configured database and served with their metrics`() {
        configuration.datasetWarehouse(provider<Client>(null)).assert().isSameAs(DatasetWarehouse.NONE)
        configuration.datasetWarehouse(provider(mockk<Client>())).assert()
            .isInstanceOf(ClickHouseDatasetWarehouse::class.java)
        val properties = AnalyticsCatalogProperties()
        properties.datasets.database = "bi"
        properties.datasets.retention = Duration.ofDays(1)
        properties.requirePublicationApproval = true
        properties.requireSourceApproval = true
        val policy = configuration.datasetPolicy(properties)
        policy.database.assert().isEqualTo("bi")
        policy.approvePublications.assert().isTrue()
        policy.approveSources.assert().isTrue()
        val registry = SimpleMeterRegistry()
        val watcher = configuration.datasetPublicationWatcher(
            provider(mockk<NodeQueryApi>()),
            QueryDatasets(),
            properties,
            provider(registry),
        )
        registry.get("wow.analytics.catalog.datasets.sync.age").gauge().value().isNaN().assert().isTrue()
        registry.get("wow.analytics.catalog.datasets.publication.lag").gauge().value().assert().isEqualTo(0.0)
        configuration.datasetPublicationsHealthIndicator(watcher).health(false)!!.status.assert()
            .isEqualTo(Status.OUT_OF_SERVICE)
        configuration.datasetPublicationWatcher(
            provider(mockk<NodeQueryApi>()),
            QueryDatasets(),
            properties,
            provider<io.micrometer.core.instrument.MeterRegistry>(null),
        ).synced.assert().isFalse()
    }

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `the catalog's roles should decide only where CoSec does not authorize the commands, as its settings say`(): Unit = runBlocking {
        configuration.analyticsCatalogAuthorizer(provider<CoSecCommandAuthorizer>(null)).assert()
            .isInstanceOf(AnalyticsCatalogAuthorizer::class.java)
        // With CoSec authorizing them, its policies decide alone: the roles have no objection.
        configuration.analyticsCatalogAuthorizer(provider(mockk<CoSecCommandAuthorizer>())).assert()
            .isSameAs(CommandAuthorizer.ALLOW_ALL)
        // A console is told the same: it rules out by role only where the roles decide.
        suspend fun rolesDecide(cosec: CoSecCommandAuthorizer?) = configuration.catalogSettingsRoute(
            WarehouseCatalog.NONE,
            AnalyticsCatalogProperties(),
            provider(cosec),
        ).settings(mockk()).rolesDecide
        rolesDecide(null).assert().isTrue()
        rolesDecide(mockk()).assert().isFalse()
        configuration.trustedWriters(
            AnalyticsCatalogProperties(trustedWriters = mutableMapOf("w" to mutableListOf("c")))
        )
            .trusts("w", "c").assert().isTrue()
    }

    @Test
    fun `the watcher should go on after a failed look, and stop`() {
        var looks = 0
        val failing = object : WarehouseCatalog by WarehouseCatalog.NONE {
            override val databases: List<String> = listOf("db")

            override suspend fun tables(database: String): List<WarehouseTableInfo> {
                looks++
                error("the warehouse is down")
            }
        }
        val watcher = CatalogWatcher(failing, { mockk<CommandClient>() }, Duration.ofMillis(10))
        watcher.phase.assert().isEqualTo(org.springframework.context.SmartLifecycle.DEFAULT_PHASE)
        watcher.isRunning.assert().isFalse()
        watcher.start()
        eventually("two failed looks") { looks >= 2 }
        watcher.isRunning.assert().isTrue()
        watcher.stop()
        watcher.isRunning.assert().isFalse()
    }
}
