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

package me.ahoo.wow.postgres.review

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.ServiceLoader
import javax.sql.DataSource

/**
 * Review r2 (configuration keys): `wow.postgres.operation-timeout` (and `wow.stores.<name>.operation-timeout`) takes
 * any duration. The store's `TimedDataSource` sets each borrowed connection's network timeout to its whole milliseconds
 * as an `Int` (`PostgresTimeouts.kt:174`, `:184`):
 * - `0` sets no timeout at all, over a pool's own `socketTimeout`, and drops the server bounds (`serverMillis(0)`):
 *   the "reply lost on the way fails the call ... instead of blocking it forever" of the key's description is gone;
 * - a negative value, or one past `Int.MAX_VALUE` milliseconds (about 24.8 days, which `toInt()` wraps negative),
 *   starts the node, and every borrow then fails in pgjdbc's `setNetworkTimeout` ("Network timeout must be a value
 *   greater than or equal to 0.", pgjdbc 42.7.14), so every store call fails at run time.
 *
 * MongoDB's and Elasticsearch's stores refuse a timeout that is not positive (`MongoEventLogOptions`,
 * `BoundedTransport`). The PostgreSQL store should refuse it when it opens, naming the key.
 */
class R2PostgresOperationTimeoutTest {
    private val provider: StoreProvider = ServiceLoader.load(StoreProvider::class.java).first { it.type == "postgres" }

    private fun config(timeout: String) = StoreConfig.of(
        name = "app",
        type = "postgres",
        options = mapOf("operation-timeout" to timeout),
        resources = StoreResources.of(mapOf("dataSource" to mockk<DataSource>(relaxed = true))),
    )

    @ParameterizedTest
    @ValueSource(strings = ["0s", "-5s", "25d"])
    fun `an operation timeout that bounds nothing should refuse the store, naming the key`(timeout: String) {
        val failure = assertThrows<WowConfigurationException> { provider.open(config(timeout)).close() }
        failure.key.assert().isEqualTo("wow.stores.app.operation-timeout")
    }
}
