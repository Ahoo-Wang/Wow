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

package me.ahoo.wow.example.shop.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.spring.boot.BackendKind
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer

/** The shop's query contract on PostgreSQL (`postgres` profile): the snapshots queried by the PostgreSQL backend. */
class ShopQueryPostgresTest : ShopQueryContract() {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }

    override val shop: ShopNode = startShop(
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "wow.log.partitions" to 4,
        profiles = listOf(PostgresDataSource.PROFILE),
    )

    override fun afterClose() {
        postgres.stop()
    }

    @Test
    fun `the snapshots should be served by the PostgreSQL query backend`() {
        shop.context.getBean(HostBackends::class.java).kind.assert().isEqualTo(BackendKind.POSTGRES)
        shop.context.getBean(HttpQueries::class.java).snapshots(AggregateType("example", "order")).assert().isNotNull()
    }
}
