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

package me.ahoo.wow.clickhouse

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The one rule the Sink and the datasets find their ClickHouse client by. */
class ClickHouseClientsTest {
    private fun settings(vararg values: Pair<String, String>): (ProviderOption<String>) -> String? = { option ->
        mapOf(*values)[option.key]?.takeIf { it.isNotBlank() } ?: option.default
    }

    @Test
    fun `the application's client should be used and never closed by Wow`() {
        val application = ClickHouseClients.of(mapOf("endpoint" to "http://127.0.0.1:1"))
        application.use {
            val found = ClickHouseClients.clientOf(
                StoreResources.of(mapOf("clickhouse" to it)),
                "wow.clickhouse",
                "The ClickHouse Sink",
                settings("endpoint" to "http://other:8123"),
            )
            found.client.assert().isSameAs(it)
            found.owned.assert().isFalse()
            found.close()
        }
    }

    @Test
    fun `without the application's client one of the endpoint should be opened and owned, with or without credentials`() {
        listOf(
            settings("endpoint" to "http://127.0.0.1:1"),
            settings("endpoint" to "http://127.0.0.1:1", "username" to "wow", "password" to "secret"),
        ).forEach { setting ->
            val own = ClickHouseClients.clientOf(StoreResources.NONE, "wow.clickhouse", "The ClickHouse Sink", setting)
            own.owned.assert().isTrue()
            own.client.assert().isNotNull()
            own.close()
        }
    }

    @Test
    fun `neither the application's client nor an endpoint should fail, naming both ways`() {
        assertThrows<IllegalArgumentException> {
            ClickHouseClients.clientOf(StoreResources.NONE, "wow.clickhouse", "The ClickHouse datasets", settings())
        }.message.assert().contains(
            "The ClickHouse datasets needs a ClickHouse client",
            "a ClickHouse Client bean",
            "wow.clickhouse.endpoint",
        )
    }
}
