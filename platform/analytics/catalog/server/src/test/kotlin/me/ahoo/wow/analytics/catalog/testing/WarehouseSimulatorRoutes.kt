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

package me.ahoo.wow.analytics.catalog.testing

import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.spi.json.WowJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/** A table the console's end-to-end suite puts in the warehouse: its name and its columns, in [MemoryWarehouse.DATABASE]. */
data class SimulatedTable(val name: String, val columns: List<SourceColumn>)

/**
 * The [MemoryWarehouse] over HTTP, for the analytics catalog console's end-to-end suite
 * (`platform/analytics/catalog/console/e2e`), which runs the server in its own JVM with this configuration among
 * `--spring.main.sources`: no ClickHouse is there, so the suite puts the tables it drafts sources of.
 *
 * - `POST /testing/warehouse/tables`: a [SimulatedTable]; answers `{"id": <its source's id>}`.
 * - `GET /testing/warehouse/views`: the datasets' views created, `["<database>.<view>"]`.
 *
 * It lives in the server's test classes only: nothing of it ships.
 */
@Configuration(proxyBeanMethods = false)
@Import(MemoryWarehouseConfiguration::class)
class WarehouseSimulatorRoutes {
    @Bean
    fun warehouseSimulatorRouter(
        warehouse: MemoryWarehouse,
        views: MemoryDatasetWarehouse,
    ): RouterFunction<ServerResponse> = coRouter {
        POST("$PATH/tables") { request ->
            val table = WowJson.mapper.readValue(request.awaitBody<String>(), SimulatedTable::class.java)
            warehouse.put(
                WarehouseTable(MemoryWarehouse.DATABASE, table.name, "uuid-${table.name}", "MergeTree", table.columns),
            )
            answer(mapOf("id" to "${warehouse.connection}:${MemoryWarehouse.DATABASE}:${table.name}"))
        }
        GET("$PATH/views") { answer(views.created.keys.sorted()) }
    }

    private suspend fun answer(body: Any): ServerResponse = ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValueAndAwait(WowJson.mapper.writeValueAsString(body))

    companion object {
        const val PATH: String = "/testing/warehouse"
    }
}
