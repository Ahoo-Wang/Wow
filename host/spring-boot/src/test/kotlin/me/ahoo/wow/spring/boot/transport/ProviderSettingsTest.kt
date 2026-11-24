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

package me.ahoo.wow.spring.boot.transport

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.ProviderSettings
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.store.AcmeStoreProvider
import me.ahoo.wow.spring.boot.store.StoreInstances
import me.ahoo.wow.spring.boot.store.StoreProperties
import me.ahoo.wow.spring.boot.store.StoreProviders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.time.Duration.Companion.seconds

/**
 * A provider's options read through Spring's binding (#6850): every form the configuration keys' check accepts, an
 * environment variable's or camelCase, means what the option's kebab-case key means, as the environment orders them.
 */
class ProviderSettingsTest {
    /** An environment as Spring Boot attaches an application's: the operating system's [variables], then [file]. */
    private fun environment(variables: Map<String, String>, file: Map<String, String> = emptyMap()) =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("application.yaml", file))
            propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", variables))
            ConfigurationPropertySources.attach(this)
        }

    private fun transport(environment: StandardEnvironment) = ProviderSettings(
        "wow.kafka",
        PortProviders.bound(environment, "wow.kafka", TopicTransportOptions.ALL),
    )

    @Test
    fun `a provider's option set by an environment variable, in either form, should be read as its key is`() {
        val settings = transport(
            environment(
                mapOf(
                    "WOW_KAFKA_COMMANDINTAKE_ENABLED" to "true",
                    "WOW_KAFKA_TOPICS_PREFIX" to "shop.",
                    // Spring's legacy form: the key's dash written as an underscore.
                    "WOW_KAFKA_TOPICS_PARTITION_REFRESH" to "5s",
                ),
            ),
        )
        settings.option(TopicTransportOptions.COMMAND_INTAKE).assert().isTrue()
        settings.option(TopicTransportOptions.TOPIC_PREFIX).assert().isEqualTo("shop.")
        settings.option(TopicTransportOptions.PARTITION_REFRESH).assert().isEqualTo(5.seconds)
    }

    @Test
    fun `a provider's option in camelCase should be read as its key is, and a map's entries each as written`() {
        val options = listOf(TopicTransportOptions.COMMAND_INTAKE, ProviderOption.map("properties", "the client's."))
        val bound = PortProviders.bound(
            environment(
                mapOf("WOW_KAFKA_PROPERTIES_LINGER_MS" to "5"),
                mapOf("wow.kafka.commandIntake.enabled" to "true", "wow.kafka.properties.[sasl.mechanism]" to "PLAIN"),
            ),
            "wow.kafka",
            options,
        )
        bound.assert().isEqualTo(
            mapOf(
                "command-intake.enabled" to "true",
                "properties.linger.ms" to "5",
                "properties.sasl.mechanism" to "PLAIN",
            ),
        )
    }

    @Test
    fun `a variable should win over the application's file, as the environment orders them`() {
        val settings = transport(
            environment(
                mapOf("WOW_KAFKA_COMMANDINTAKE_ENABLED" to "true"),
                mapOf("wow.kafka.command-intake.enabled" to "false"),
            ),
        )
        settings.option(TopicTransportOptions.COMMAND_INTAKE).assert().isTrue()
    }

    @Test
    fun `the node's needs and the transport's settings should read the command intake alike`() {
        val environment = environment(mapOf("WOW_KAFKA_COMMANDINTAKE_ENABLED" to "true"))
        TransportNeeds.intake(environment, listOf("kafka")).assert().containsExactly("wow.kafka.command-intake.enabled")
        transport(environment).option(TopicTransportOptions.COMMAND_INTAKE).assert().isTrue()
    }

    @Test
    fun `a store's own option set by a variable or in camelCase should be read, and one no option names refused`() {
        val providers = StoreProviders.load(javaClass.classLoader)
        fun open(variables: Map<String, String>, file: Map<String, String> = emptyMap()) = StoreInstances.open(
            WowProperties().apply { stores["acme"] = StoreProperties().apply { type = "acme" } },
            providers,
            StoreResources.NONE,
            settings = ProviderSettingsSource.of(
                environment(variables, file + mapOf("wow.stores.acme.type" to "acme"))
            ),
        ).getValue("acme")
        open(mapOf("WOW_STORES_ACME_REGION" to "eu-2")).config.settings.option(AcmeStoreProvider.REGION).assert()
            .isEqualTo("eu-2")
        open(emptyMap(), mapOf("wow.acme.Region" to "us-3")).config.settings.option(AcmeStoreProvider.REGION).assert()
            .isEqualTo("us-3")
        // A store's own option in another form is the option, not refused as one its provider does not declare.
        open(emptyMap(), mapOf("wow.stores.acme.Region" to "ap-4")).config.settings.option(AcmeStoreProvider.REGION)
            .assert().isEqualTo("ap-4")
        assertThrows<WowConfigurationException> { open(mapOf("WOW_STORES_ACME_COLOUR" to "red")) }.key.assert()
            .isEqualTo("wow.stores.acme.colour")
    }
}
