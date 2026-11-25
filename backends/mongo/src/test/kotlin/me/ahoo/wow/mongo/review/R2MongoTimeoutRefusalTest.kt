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

package me.ahoo.wow.mongo.review

import com.mongodb.client.MongoClient
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.ServiceLoader

/**
 * Review r2 (configuration keys): a MongoDB store refuses a timeout that is not positive, but with the bare
 * `IllegalArgumentException` of `MongoEventLogOptions` ("The append timeout is positive.", "The operation timeout is
 * positive."), which names no key; the host passes it on from `wowBackends`' factory method. `operate/configuration.md`
 * says such a value is a `WowConfigurationException` naming the key and its value.
 */
class R2MongoTimeoutRefusalTest {
    private val provider: StoreProvider = ServiceLoader.load(StoreProvider::class.java).first { it.type == "mongo" }

    @ParameterizedTest
    @CsvSource("append-timeout,-5s", "append-timeout,0s", "operation-timeout,0s")
    fun `a timeout that is not positive should be refused naming its key`(option: String, value: String) {
        val config = StoreConfig.of(
            name = "app",
            type = "mongo",
            options = mapOf(option to value),
            resources = StoreResources.of(mapOf<String, Any>("client" to mockk<MongoClient>(relaxed = true))),
        )
        val failure = assertThrows<WowConfigurationException> { provider.open(config).close() }
        failure.key.assert().isEqualTo("wow.stores.app.$option")
    }
}
