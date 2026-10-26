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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouse
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouseConfiguration
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test

/** The catalog on PostgreSQL (decision D196): the sources' log and snapshots, and their queries, in a container. */
class AnalyticsCatalogPostgresTest {
    @Test
    fun `an analyst's source should be kept and listed on PostgreSQL`() {
        val postgres = WowContainers.postgres
        startCatalog(
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "wow.backend" to "postgres",
            "wow.analytics.catalog.watch.enabled" to false,
            profiles = listOf("postgres"),
            sources = listOf(MemoryWarehouseConfiguration::class.java),
        ).use { node ->
            node.context.getBean(MemoryWarehouse::class.java).put(
                WarehouseTable(MemoryWarehouse.DATABASE, "orders", "u-1", "MergeTree", listOf(SourceColumn("t", "UInt8"))),
            )
            val id = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, "orders")
            node.command(id, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"orders"}""", Who.EDITOR)
                .assertOk()
            eventually("the source listed") {
                node.sources().json.values().any { it["aggregateId"].asString() == id }
            }
            node.state(id)["state"]["tableEngine"].asString().assert().isEqualTo("MergeTree")
        }
    }
}
