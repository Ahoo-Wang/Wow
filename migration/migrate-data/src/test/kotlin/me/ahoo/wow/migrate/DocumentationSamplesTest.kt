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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.ReservationTenancy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The configuration and the map the documentation shows (`documentation/docs/{en,zh}/migrate/data.md` and
 * `reference/migrate-data.md`) read as this tool reads them, every property one it defines: the pages show the files
 * in `src/test/resources/docs/` as they are.
 */
class DocumentationSamplesTest {
    private val samples = Path.of("src/test/resources/docs")

    @Test
    fun `the configuration the pages show reads`() {
        val config = MigrationConfig.read(samples.resolve("migrate-data.json"))
        config.map.assert().isEqualTo(samples.toAbsolutePath().resolve("migration-map.json").toString())
        config.target.type.assert().isEqualTo(TargetConfig.POSTGRES)
        MigrationConfig.resolve(config.target.password) { "secret" }.assert().isEqualTo("secret")
        config.partitionsOf("example").assert().isEqualTo(64)
        config.compensation!!.service.assert().isEqualTo("order-service")
    }

    @Test
    fun `the map the pages show reads`() {
        val map = MigrationMap.read(Files.readString(samples.resolve("migration-map.json")))
        val order = map.aggregates.single()
        order.to.assert().isEqualTo("example.order")
        order.events.filter { it.retire }.map { it.to }.assert().containsExactly("OrderArchived")
        order.events.filter { it.drop }.map { it.from }.assert().containsExactly("order_noted")
        order.reservations.single().tenancy.assert().isEqualTo(ReservationTenancy.SYSTEM)
        order.state.ignore.assert().containsExactly("payable")
        map.subscriptions.map { it.from }
            .assert().containsExactly("order-service.OrderSaga", "order-service.OrderProjector")
        map.headers.assert().containsEntry("app_id", "app_id")
    }
}
