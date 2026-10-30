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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** The settings the host derives from what the application already says: CoSec's mode, and its connections. */
class DerivedDefaultsEnvironmentPostProcessorTest {
    private fun environment(vararg properties: Pair<String, Any>): StandardEnvironment = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("app", properties.toMap()))
    }

    private fun StandardEnvironment.coSec() = also {
        CoSecModeEnvironmentPostProcessor().postProcessEnvironment(it, SpringApplication())
    }

    private fun StandardEnvironment.connections() = also {
        ConnectionDefaultsEnvironmentPostProcessor().postProcessEnvironment(it, SpringApplication())
    }

    @Test
    fun `the gateway mode should set exactly CoSec's gateway-mode keys`() {
        // Pinned, so a CoSec upgrade that renames one of them fails here rather than in a deployment.
        CoSecModeEnvironmentPostProcessor.GATEWAY.assert().isEqualTo(
            mapOf(
                "cosec.enabled" to "true",
                "cosec.pipeline.mode" to "gateway",
                "cosec.jwt.enabled" to "false",
            ),
        )
        val gateway = environment(CoSecModeEnvironmentPostProcessor.MODE to "gateway").coSec()
        CoSecModeEnvironmentPostProcessor.GATEWAY.forEach { (key, value) ->
            gateway.getProperty(key).assert().isEqualTo(value)
        }
        // Lowest precedence: after every other source.
        gateway.propertySources.last().name.assert().isEqualTo(CoSecModeEnvironmentPostProcessor.SOURCE)
    }

    @Test
    fun `a cosec key the application sets should win over the mode`() {
        val own = environment(
            CoSecModeEnvironmentPostProcessor.MODE to " Gateway ",
            "cosec.jwt.enabled" to "true",
        ).coSec()
        own.getProperty("cosec.jwt.enabled").assert().isEqualTo("true")
        own.getProperty("cosec.pipeline.mode").assert().isEqualTo("gateway")
    }

    @Test
    fun `the disabled mode should turn CoSec off, and no mode should leave it to CoSec`() {
        val off = environment(CoSecModeEnvironmentPostProcessor.MODE to "disabled").coSec()
        off.getProperty("cosec.enabled").assert().isEqualTo("false")
        off.getProperty("cosec.pipeline.mode").assert().isNull()
        val unset = environment().coSec()
        unset.propertySources.contains(CoSecModeEnvironmentPostProcessor.SOURCE).assert().isFalse()
    }

    @Test
    fun `an unknown mode should fail the startup, naming the key`() {
        assertThrownBy<BindException> {
            environment(CoSecModeEnvironmentPostProcessor.MODE to "off").coSec()
        }.actual().message.assert().contains(CoSecModeEnvironmentPostProcessor.MODE)
    }

    @Test
    fun `the mongo database should default to the one the connection string names`() {
        val derived = environment(
            ConnectionDefaultsEnvironmentPostProcessor.MONGO_URI to "mongodb://mongo:27017/shop?replicaSet=rs0",
        ).connections()
        derived.getProperty(ConnectionDefaultsEnvironmentPostProcessor.MONGO_DATABASE).assert().isEqualTo("shop")
        derived.propertySources.last().name.assert().isEqualTo(ConnectionDefaultsEnvironmentPostProcessor.SOURCE)
        // The application's own wins.
        environment(
            ConnectionDefaultsEnvironmentPostProcessor.MONGO_URI to "mongodb://mongo:27017/shop",
            ConnectionDefaultsEnvironmentPostProcessor.MONGO_DATABASE to "orders",
        ).connections().getProperty(ConnectionDefaultsEnvironmentPostProcessor.MONGO_DATABASE).assert()
            .isEqualTo("orders")
        // A connection string that names none leaves the default to the MongoDB stores' own option.
        val none = environment(
            ConnectionDefaultsEnvironmentPostProcessor.MONGO_URI to "mongodb://mongo:27017/?replicaSet=rs0",
        ).connections()
        none.getProperty(ConnectionDefaultsEnvironmentPostProcessor.MONGO_DATABASE).assert().isNull()
    }

    @Test
    fun `the database of a connection string is the path after its hosts`() {
        listOf(
            "mongodb://h1:1,h2:2/shop" to "shop",
            "mongodb+srv://user:p%40ss@cluster.example/shop_db?retryWrites=true" to "shop_db",
            "mongodb://h/my%20db" to "my db",
            "mongodb://h/" to null,
            "mongodb://h" to null,
            "mongodb://h/?w=majority" to null,
            "not a uri" to null,
        ).forEach { (uri, database) ->
            ConnectionDefaultsEnvironmentPostProcessor.databaseOf(uri).assert().describedAs(uri).isEqualTo(database)
        }
    }

    @Test
    fun `the kafka brokers should default to spring's, as a list or a string`() {
        environment(ConnectionDefaultsEnvironmentPostProcessor.SPRING_KAFKA_SERVERS to "k1:9092, k2:9092")
            .connections().getProperty(ConnectionDefaultsEnvironmentPostProcessor.KAFKA_SERVERS).assert()
            .isEqualTo("k1:9092,k2:9092")
        environment(
            "${ConnectionDefaultsEnvironmentPostProcessor.SPRING_KAFKA_SERVERS}[0]" to "k1:9092",
            "${ConnectionDefaultsEnvironmentPostProcessor.SPRING_KAFKA_SERVERS}[1]" to "k2:9092",
        ).connections().getProperty(ConnectionDefaultsEnvironmentPostProcessor.KAFKA_SERVERS).assert()
            .isEqualTo("k1:9092,k2:9092")
        environment(
            ConnectionDefaultsEnvironmentPostProcessor.SPRING_KAFKA_SERVERS to "spring:9092",
            ConnectionDefaultsEnvironmentPostProcessor.KAFKA_SERVERS to "wow:9092",
        ).connections().getProperty(ConnectionDefaultsEnvironmentPostProcessor.KAFKA_SERVERS).assert()
            .isEqualTo("wow:9092")
        environment().connections().propertySources.contains(ConnectionDefaultsEnvironmentPostProcessor.SOURCE)
            .assert().isFalse()
    }
}
