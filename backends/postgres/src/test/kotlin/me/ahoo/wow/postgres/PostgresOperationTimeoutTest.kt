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

package me.ahoo.wow.postgres

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.sql.DataSource

/**
 * The operation timeout of a PostgreSQL store (#7035): JDBC's network timeout is whole milliseconds in an `Int`, 0 none,
 * so a timeout of zero bounded nothing and a negative one, or one past `Int.MAX_VALUE` milliseconds, failed every
 * borrow in pgjdbc at run time. The store refuses them when it opens, naming the key.
 */
class PostgresOperationTimeoutTest {
    private fun open(timeout: String) = PostgresStoreProvider().open(
        StoreConfig.of(
            name = "app",
            type = PostgresStoreProvider.TYPE,
            options = mapOf(PostgresStoreProvider.OPERATION_TIMEOUT.key to timeout),
            resources = StoreResources.of(mapOf("dataSource" to mockk<DataSource>(relaxed = true))),
        ),
    )

    @Test
    fun `an operation timeout that bounds nothing, or past an Int of milliseconds, should refuse the store`() {
        listOf("0s", "-5s", "25d").forEach { refused ->
            val failure = assertThrows<WowConfigurationException> { open(refused).close() }
            failure.key.assert().isEqualTo("wow.stores.app.operation-timeout")
            failure.fix.assert().contains("wow.postgres.operation-timeout")
        }
    }

    @Test
    fun `an operation timeout from a millisecond to an Int of milliseconds should open the store`() {
        listOf("1ms", "24d").forEach { open(it).close() }
    }
}
